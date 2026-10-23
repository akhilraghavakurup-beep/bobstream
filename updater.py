#!/usr/bin/env python3
"""
BobStream Telegram Stream Sync Agent
Runs on GitHub Actions via cron every 5 minutes.
Features:
1. Connects to Telegram via Telethon StringSession.
2. Extracts live streams from the last 12 hours from Telegram channel/group.
3. Intelligent Context Awareness:
   - Resolves thread replies (e.g. if link is sent replying to "Fancode link Al Nassr?").
   - Extracts overall match title + quality/language tags (e.g. "TKFC Vs TMFC [English 1080p]").
4. Robust URL Sanitization:
   - Strips backticks (```), brackets, quotation marks, and trailing punctuation.
5. De-duplication:
   - Prevents duplicate links by base URL & token normalization. Keeps newest/best tagged entry.
6. Link Health Verification:
   - Tests links using fast lightweight network probe. Filters out dead/broken (404, 410, DNS drop, timeout) links.
   - Ignores non-video spam links (social media, youtube watch pages, ads).
7. Yandex Image Matching:
   - Queries Yandex Images for sports action posters with graceful fallback to Unsplash sports banners.
8. Differential Git Sync:
   - Compares new list with current config.json. Only pushes a commit if items change.
"""

import os
import re
import json
import asyncio
import urllib.request
import urllib.parse
import urllib.error
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

# Match http/https URLs
URL_REGEX = re.compile(r'(https?://[^\s]+)')

# Domains that are not playable direct video streams in BobStream
SPAM_DOMAINS = [
    "youtube.com", "youtu.be", "t.me", "telegram.me",
    "instagram.com", "facebook.com", "twitter.com", "x.com"
]

def sanitize_link(raw_link: str) -> str:
    """Clean markdown backticks, brackets, quotes from URLs while preserving query params & pipe DRM."""
    cleaned = raw_link.strip().strip('`"\'').rstrip('`"\'\)>],')
    return cleaned

def is_valid_stream_format(url: str) -> bool:
    """Filter out web pages/social links; prioritize playable streams (.m3u8, .mpd, .mp4, cmaf, live CDN paths)."""
    base = url.split('|')[0].lower()
    for spam in SPAM_DOMAINS:
        if spam in base:
            return False

    valid_indicators = [
        ".m3u8", ".mpd", ".mp4", ".mkv", "live", "cmaf", "stream", "chunk",
        "hls", "dash", "manifest", "master", "fancode", "hotstar", "zee5", "sony"
    ]
    return any(ind in base for ind in valid_indicators)

def check_link_alive(raw_link: str) -> bool:
    """
    Probe the stream URL with lightweight HTTP request.
    Returns False if link is 404 Not Found, 410 Gone, 502/503/504, or DNS dead.
    """
    clean = sanitize_link(raw_link)
    base_url = clean.split('|')[0]

    try:
        req = urllib.request.Request(
            base_url,
            headers={
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            }
        )
        with urllib.request.urlopen(req, timeout=4) as resp:
            return resp.status < 400
    except urllib.error.HTTPError as e:
        # HTTP 403 or 401 often means token expired or requires Referer/Token - we reject hard 404/410/502
        if e.code in [404, 410, 500, 502, 504]:
            return False
        return True
    except Exception:
        # Timeout or unreachable domain
        return False

def clean_title_text(text: str) -> str:
    """Clean markdown formatting and unwanted noise from titles."""
    if not text:
        return ""
    cleaned = re.sub(r'[*_`~#]', '', text)
    cleaned = re.sub(r'https?://\S+', '', cleaned)
    cleaned = re.sub(r'\s+', ' ', cleaned).strip()
    return cleaned

def extract_quality_or_lang(text: str) -> str:
    """Extract tags like [English], [Hindi], [1080p], [720p]."""
    tags = []
    text_lower = text.lower()
    for lang in ["english", "hindi", "malayalam", "tamil", "telugu", "bengali", "kannada"]:
        if lang in text_lower:
            tags.append(lang.capitalize())
            break
    for q in ["1080p", "720p", "540p", "480p", "360p", "50fps"]:
        if q in text_lower:
            tags.append(q)
            break
    return " ".join(tags)

def fetch_yandex_thumbnail(query: str) -> str:
    """Search Yandex Images for sports match title."""
    if not query or len(query.strip()) < 3:
        return DEFAULT_FALLBACK_IMAGE

    clean_query = re.sub(r'https?://\S+', '', query)
    clean_query = re.sub(r'\[.*?\]|\(.*?\)', '', clean_query)
    clean_query = re.sub(r'[^\w\s]', ' ', clean_query).strip()

    if not clean_query:
        return DEFAULT_FALLBACK_IMAGE

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
        with urllib.request.urlopen(req, timeout=5) as response:
            html = response.read().decode("utf-8", errors="ignore")
            matches = re.findall(r'img_url=([^&"\'\s]+)', html)
            for m in matches:
                decoded = urllib.parse.unquote(m)
                if decoded.startswith("http") and not decoded.endswith(".svg"):
                    return decoded

            cdn_matches = re.findall(r'https?://avatars\.mds\.yandex\.net/[^\s"\'<>]+', html)
            if cdn_matches:
                return cdn_matches[0]
    except Exception:
        pass

    return DEFAULT_FALLBACK_IMAGE

async def resolve_target_entity(client, target):
    """Resolve Telegram channel/group entity by ID or title."""
    target_str = str(target).strip()
    if target_str.startswith("-100") or target_str.isdigit() or (target_str.startswith("-") and target_str[1:].isdigit()):
        try:
            return await client.get_entity(int(target_str))
        except Exception:
            pass

    try:
        clean_user = target_str.lstrip("@")
        return await client.get_entity(clean_user)
    except Exception:
        pass

    async for dialog in client.iter_dialogs(limit=100):
        if dialog.is_channel or dialog.is_group:
            title_norm = re.sub(r'[^\w\s]', '', dialog.title).lower()
            target_norm = re.sub(r'[^\w\s]', '', target_str).lower()
            if target_norm in title_norm or title_norm in target_norm:
                return dialog.entity

    raise ValueError(f"Could not resolve channel {target_str}")

async def fetch_telegram_matches() -> list:
    """Fetch messages from the last 12 hours, resolve context/replies, test link health & de-duplicate."""
    if not STRING_SESSION:
        raise ValueError("STRING_SESSION environment variable is missing!")

    print("[*] Connecting to Telegram client...")
    client = TelegramClient(StringSession(STRING_SESSION), API_ID, API_HASH)
    await client.connect()

    if not await client.is_user_authorized():
        await client.disconnect()
        raise PermissionError("Telethon StringSession is not authorized or expired!")

    cutoff_time = datetime.now(timezone.utc) - timedelta(hours=12)
    print(f"[*] Fetching messages from target {TARGET_CHANNEL} (since {cutoff_time.isoformat()})...")

    raw_candidates = []
    reply_cache = {}

    try:
        entity = await resolve_target_entity(client, TARGET_CHANNEL)
        print(f"[✓] Connected to: {getattr(entity, 'title', entity.id)}")

        # Fetch messages in reverse chronological order
        async for msg in client.iter_messages(entity, limit=120):
            if msg.date < cutoff_time:
                break

            text = msg.text or ""
            if not text or "http" not in text:
                continue

            url_matches = list(URL_REGEX.finditer(text))
            if not url_matches:
                continue

            # Check if this message was a reply to another message (for title context)
            parent_text = ""
            if msg.reply_to and msg.reply_to.reply_to_msg_id:
                p_id = msg.reply_to.reply_to_msg_id
                if p_id not in reply_cache:
                    try:
                        parent_msg = await client.get_messages(entity, ids=p_id)
                        reply_cache[p_id] = parent_msg.text if parent_msg else ""
                    except Exception:
                        reply_cache[p_id] = ""
                parent_text = reply_cache.get(p_id, "")

            # Determine title context
            first_text = text[:url_matches[0].start()]
            first_lines = [l.strip() for l in first_text.splitlines() if l.strip()]

            overall_title = ""
            if first_lines:
                overall_title = clean_title_text(first_lines[0])
            elif parent_text:
                p_lines = [l.strip() for l in parent_text.splitlines() if l.strip() and not l.startswith("http")]
                if p_lines:
                    overall_title = clean_title_text(p_lines[0])

            if not overall_title:
                overall_title = "Live Stream"

            for idx, m in enumerate(url_matches):
                sanitized_url = sanitize_link(m.group(0))

                if not is_valid_stream_format(sanitized_url):
                    continue

                # Context label (language or quality)
                prev_end = url_matches[idx-1].end() if idx > 0 else 0
                between_text = text[prev_end:m.start()]
                tag = extract_quality_or_lang(between_text)

                if not tag:
                    # Check text immediately after URL if line exists
                    next_start = m.end()
                    next_lines = [l.strip() for l in text[next_start:].splitlines() if l.strip()]
                    if next_lines and not next_lines[0].startswith("http"):
                        tag = extract_quality_or_lang(next_lines[0])

                final_name = f"{overall_title} [{tag}]" if tag else overall_title

                raw_candidates.append({
                    "name": final_name,
                    "link": sanitized_url,
                    "date": msg.date
                })

    finally:
        await client.disconnect()

    print(f"[*] Found {len(raw_candidates)} candidate stream links. De-duplicating and verifying health...")

    # De-duplicate by base URL (prevent multiple entries of identical stream)
    unique_matches = []
    seen_base_urls = set()

    for item in raw_candidates:
        link = item["link"]
        base_clean = link.split('|')[0].split('?')[0].lower().rstrip('/')

        if base_clean in seen_base_urls:
            continue
        seen_base_urls.add(base_clean)

        # Health Check: verify if stream is alive
        print(f"  [~] Probing: {item['name']} -> {link[:65]}...")
        if not check_link_alive(link):
            print(f"      [X] Skipped broken/dead stream.")
            continue

        print(f"      [✓] Link is ACTIVE!")
        thumb = fetch_yandex_thumbnail(item["name"])
        unique_matches.append({
            "name": item["name"],
            "image": thumb,
            "link": link
        })

    return unique_matches

def sync_to_github(new_matches: list):
    """Compares new matches with current config.json and pushes update only if changed."""
    if not GH_TOKEN:
        raise ValueError("GH_TOKEN is missing!")

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

    if normalize_for_comparison(current_matches) == normalize_for_comparison(new_matches):
        print("[✓] config.json is already identical. No commit needed.")
        return

    print(f"[*] Committing update: {len(new_matches)} active verified streams.")
    payload = {"matches": new_matches}
    content_str = json.dumps(payload, indent=2, ensure_ascii=False) + "\n"

    commit_msg = f"sync(streams): update {len(new_matches)} verified active streams from Telegram [skip ci]"

    if sha:
        repo.update_file(path=FILE_PATH, message=commit_msg, content=content_str, sha=sha)
    else:
        repo.create_file(path=FILE_PATH, message=commit_msg, content=content_str)

    print(f"[✓] config.json successfully synchronized on GitHub!")

async def main():
    print("=" * 60)
    print("BobStream Telegram Stream Sync Agent Starting...")
    print("=" * 60)
    try:
        new_matches = await fetch_telegram_matches()
        sync_to_github(new_matches)
    except Exception as e:
        print(f"[ERROR] Sync run failed: {e}")
        raise

if __name__ == "__main__":
    asyncio.run(main())
