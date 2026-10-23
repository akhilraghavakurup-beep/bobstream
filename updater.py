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
TARGET_CHANNEL = os.environ.get("TARGET_CHANNEL", "sportlovers")
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

    clean_query = re.sub(r'https?://\S+', '', query)
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

def parse_message_to_match(text: str) -> dict | None:
    """
    Extracts title above URL and the complete streaming link.
    """
    if not text:
        return None

    url_match = URL_REGEX.search(text)
    if not url_match:
        return None

    full_link = url_match.group(0).strip()
    # If link contains trailing brackets/punctuation, clean it up but keep pipe syntax intact
    full_link = full_link.rstrip(')>]')

    # Extract text ABOVE the URL
    url_start_pos = url_match.start()
    text_before_url = text[:url_start_pos]

    # Split lines and extract title
    lines = [line.strip() for line in text_before_url.splitlines() if line.strip()]

    # If lines found before URL, join the last 1 or 2 lines as name
    if lines:
        name = " - ".join(lines[-2:]) if len(lines) >= 2 and len(lines[-1]) < 10 else lines[-1]
    else:
        # Fallback to text after or generic title
        remaining_lines = [l.strip() for l in text[url_match.end():].splitlines() if l.strip()]
        name = remaining_lines[0] if remaining_lines else "Live Match Stream"

    # Clean formatting markdown/emojis if overly noisy
    name = re.sub(r'[*_`~]', '', name).strip()

    return {
        "name": name,
        "link": full_link
    }

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
    print(f"[*] Fetching messages from @{TARGET_CHANNEL} since {cutoff_time.isoformat()} (12-hour window)...")

    matches = []
    seen_links = set()

    try:
        entity = await client.get_entity(TARGET_CHANNEL)
        async for msg in client.iter_messages(entity, limit=100):
            if msg.date < cutoff_time:
                break

            if msg.text:
                parsed = parse_message_to_match(msg.text)
                if parsed and parsed["link"] not in seen_links:
                    seen_links.add(parsed["link"])
                    # Fetch thumbnail for match
                    print(f"  [+] Found match: '{parsed['name']}'")
                    thumb = fetch_yandex_thumbnail(parsed["name"])
                    matches.append({
                        "name": parsed["name"],
                        "image": thumb,
                        "link": parsed["link"]
                    })
    finally:
        await client.disconnect()

    # Oldest to newest or newest first
    return matches

def sync_to_github(new_matches: list):
    """
    Compares new matches with the current config.json on GitHub.
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

    # Check for functional equivalence (comparing name and link)
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
