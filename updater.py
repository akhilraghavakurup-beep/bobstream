#!/usr/bin/env python3
"""
BobStream Telegram Sync Updater
Runs on GitHub Actions via cron every 5 minutes.
Scrapes streaming links posted in the last 12 hours from Telegram,
fetches dynamic match thumbnails via Yandex Images (with reliable fallback),
and syncs changes into config.json via PyGithub only if differences are detected.
"""

import os
import re
import json
import asyncio
import urllib.request
import urllib.parse
from datetime import datetime, timezone, timedelta
from telethon import TelegramClient
from telethon.sessions import StringSession
from github import Github, Auth

# --- CONFIGURATION & SECRETS ---
API_ID = int(os.environ.get("API_ID", "33673228"))
API_HASH = os.environ.get("API_HASH", "b86b954873058e3766fe17187ce87fd6")
STRING_SESSION = os.environ.get("STRING_SESSION", "")
GH_TOKEN = os.environ.get("GH_TOKEN", "")
TARGET_CHANNEL = os.environ.get("TARGET_CHANNEL", "-1001850610022")
GITHUB_REPOSITORY = os.environ.get("GITHUB_REPOSITORY", "akhilraghavakurup-beep/bobstream")
FILE_PATH = "config.json"

DEFAULT_FALLBACK_IMAGE = "https://images.unsplash.com/photo-1540747913346-19e32dc3e97e?w=800&auto=format&fit=crop&q=80"

# URL Detection Regex (matching http/https, including pipes and query parameters)
URL_REGEX = re.compile(r'(https?://[^\s]+)')

def fetch_yandex_thumbnail(query: str) -> str:
    """
    Search Yandex Images for match query to grab an action thumbnail.
    Falls back gracefully to DEFAULT_FALLBACK_IMAGE if not found or blocked.
    """
    if not query or len(query.strip()) < 3:
        return DEFAULT_FALLBACK_IMAGE

    # Clean the query of stream metadata
    clean_query = re.sub(r'https?://\S+', '', query)
    clean_query = re.sub(r'\((English|Hindi|Malayalam|Tamil|Telugu|1080p|720p|Stream\s*\d*)\)', '', clean_query, flags=re.IGNORECASE)
    clean_query = re.sub(r'[^\w\s]', ' ', clean_query).strip()

    search_term = f"{clean_query} sports"
    try:
        url = "https://yandex.com/images/search?text=" + urllib.parse.quote(search_term)
        req = urllib.request.Request(
            url,
            headers={
                "User-Agent": (
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    "AppleWebKit/537.36 (KHTML, like Gecko) "
                    "Chrome/120.0.0.0 Safari/537.36"
                ),
                "Accept-Language": "en-US,en;q=0.9",
            }
        )
        with urllib.request.urlopen(req, timeout=6) as response:
            html = response.read().decode("utf-8", errors="ignore")

            # Look for direct image URL in img_url parameter
            matches = re.findall(r'img_url=([^&"\'\s]+)', html)
            for m in matches:
                decoded = urllib.parse.unquote(m)
                if decoded.startswith("http") and not decoded.endswith(".svg"):
                    return decoded

            # Look for Yandex CDN image avatar
            cdn_matches = re.findall(r'https?://avatars\.mds\.yandex\.net/[^\s"\'<>]+', html)
            if cdn_matches:
                return cdn_matches[0]

    except Exception as e:
        print(f"[-] Yandex thumbnail lookup failed for '{query}': {e}")

    return DEFAULT_FALLBACK_IMAGE

def parse_messages_to_matches(text: str) -> list:
    """
    Extracts all stream links from a message, capturing the overall match title
    and any language/quality label above each URL.
    """
    if not text:
        return []

    url_matches = list(URL_REGEX.finditer(text))
    if not url_matches:
        return []

    # Get overall title from text before the first URL
    first_text = text[:url_matches[0].start()]
    first_lines = [l.strip() for l in first_text.splitlines() if l.strip()]
    overall_title = first_lines[0] if first_lines else "Live Match"
    overall_title = re.sub(r'[*_`~]', '', overall_title).strip()

    items = []
    for idx, m in enumerate(url_matches):
        full_link = m.group(0).strip().rstrip(')>]')
        
        # Determine name for this specific stream
        prev_end = url_matches[idx-1].end() if idx > 0 else 0
        between_text = text[prev_end:m.start()]
        lines = [l.strip() for l in between_text.splitlines() if l.strip()]

        if idx == 0:
            name = overall_title
        else:
            label = lines[-1] if lines else f"Stream {idx+1}"
            label = re.sub(r'[*_`~]', '', label).strip()
            name = f"{overall_title} ({label})" if overall_title else label

        items.append({
            "name": name,
            "link": full_link
        })

    return items

async def resolve_target_entity(client, target):
    """
    Resolves the Telegram entity either by integer channel ID,
    username, or title search across joined dialogs.
    """
    target_str = str(target).strip()
    
    # 1. Try numeric ID
    if target_str.startswith("-100") or target_str.isdigit() or (target_str.startswith("-") and target_str[1:].isdigit()):
        try:
            return await client.get_entity(int(target_str))
        except Exception:
            pass

    # 2. Try username directly
    try:
        clean_user = target_str.lstrip("@")
        return await client.get_entity(clean_user)
    except Exception:
        pass

    # 3. Match against joined dialog titles
    print(f"[*] Searching joined dialogs for target: '{target_str}'...")
    async for dialog in client.iter_dialogs(limit=100):
        if dialog.is_channel or dialog.is_group:
            title_norm = re.sub(r'[^\w\s]', '', dialog.title).lower()
            target_norm = re.sub(r'[^\w\s]', '', target_str).lower()
            if target_norm in title_norm or title_norm in target_norm:
                print(f"[✓] Matched dialog: '{dialog.title}' (ID: {dialog.id})")
                return dialog.entity

    raise ValueError(f"Could not resolve target channel/group: '{target_str}' in your Telegram account.")

async def fetch_telegram_matches() -> list:
    """
    Connects to Telegram using Telethon StringSession,
    fetches messages from the last 12 hours from the target channel,
    and returns a list of match items.
    """
    if not STRING_SESSION:
        raise ValueError("STRING_SESSION environment variable is missing or empty!")

    print(f"[*] Connecting to Telegram client...")
    client = TelegramClient(StringSession(STRING_SESSION), API_ID, API_HASH)
    await client.connect()

    if not await client.is_user_authorized():
        await client.disconnect()
        raise PermissionError("Telethon StringSession is not authorized or has expired!")

    cutoff_time = datetime.now(timezone.utc) - timedelta(hours=12)
    print(f"[*] Fetching messages from target '{TARGET_CHANNEL}' since {cutoff_time.isoformat()} (12-hour window)...")

    matches = []
    seen_links = set()

    try:
        entity = await resolve_target_entity(client, TARGET_CHANNEL)
        print(f"[✓] Resolved channel entity: {getattr(entity, 'title', entity.id)}")

        async for msg in client.iter_messages(entity, limit=100):
            if msg.date < cutoff_time:
                break

            if msg.text:
                parsed_items = parse_messages_to_matches(msg.text)
                for item in parsed_items:
                    if item["link"] not in seen_links:
                        seen_links.add(item["link"])
                        print(f"  [+] Found match: '{item['name']}'")
                        thumb = fetch_yandex_thumbnail(item["name"])
                        matches.append({
                            "name": item["name"],
                            "image": thumb,
                            "link": item["link"]
                        })
    finally:
        await client.disconnect()

    return matches

def sync_to_github(new_matches: list):
    """
    Compares new matches with current config.json on GitHub.
    Commits changes only if there's an actual update.
    """
    if not GH_TOKEN:
        raise ValueError("GH_TOKEN is missing! Cannot push changes.")

    auth = Auth.Token(GH_TOKEN)
    g = Github(auth=auth)
    repo = g.get_repo(GITHUB_REPOSITORY)

    try:
        file_content = repo.get_contents(FILE_PATH)
        current_data = json.loads(file_content.decoded_content.decode("utf-8"))
        sha = file_content.sha
    except Exception as e:
        print(f"[!] Could not fetch existing {FILE_PATH}: {e}. Creating new.")
        current_data = {"matches": []}
        sha = None

    current_matches = current_data.get("matches", [])

    def normalize_for_comparison(items):
        return [{"name": m.get("name", "").strip(), "link": m.get("link", "").strip()} for m in items]

    curr_norm = normalize_for_comparison(current_matches)
    new_norm = normalize_for_comparison(new_matches)

    if curr_norm == new_norm:
        print("[✓] config.json is already up to date. No changes to commit.")
        return

    print(f"[*] Changes detected! Current: {len(current_matches)} items, New: {len(new_matches)} items.")
    updated_payload = {"matches": new_matches}
    new_content_str = json.dumps(updated_payload, indent=2, ensure_ascii=False) + "\n"

    commit_msg = f"sync(streams): auto-update {len(new_matches)} active matches from Telegram [skip ci]"

    if sha:
        repo.update_file(
            path=FILE_PATH,
            message=commit_msg,
            content=new_content_str,
            sha=sha
        )
    else:
        repo.create_file(
            path=FILE_PATH,
            message=commit_msg,
            content=new_content_str
        )

    print(f"[✓] Successfully updated {FILE_PATH} on {GITHUB_REPOSITORY}!")

async def main():
    print("=" * 60)
    print("BobStream Telegram Stream Sync Agent Starting...")
    print("=" * 60)

    try:
        new_matches = await fetch_telegram_matches()
        print(f"[*] Processed {len(new_matches)} streaming links from Telegram.")
        sync_to_github(new_matches)
    except Exception as e:
        print(f"[ERROR] Sync run failed: {e}")
        raise

if __name__ == "__main__":
    asyncio.run(main())
