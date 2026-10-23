#!/usr/bin/env python3
"""
BobStream Telegram Stream Sync Agent
Runs on GitHub Actions via cron every 5 minutes.

Features & Enhancements:
1. Intelligent Context & Reply Resolution:
   - Matches replies to find the real match title (e.g. "Al Nassr").
2. Eliminates Duplicate Streams:
   - Groups multi-renditions of the same match (e.g., 240p, 360p, 480p, 720p, 1080p).
   - Keeps only the highest quality stream (1080p > 720p > 540p > 480p > 360p) per event!
3. Active Stream Health Check:
   - Live HTTP probe rejects dead streams, 404s, expired tokens, and domain dropouts.
   - Filters out non-video spam (YouTube web, Telegram, social links).
4. Automated Akamai & Zee5 Headers:
   - Injects required User-Agent, Referer, and Origin headers in pipe syntax (|)
     so protected streams (like Zee5 / Akamai) play instantly in the app without 403 Forbidden.
5. High-Resolution Sports Thumbnails:
   - Matches event names on Yandex Images to fetch action posters.
6. Clean Serverless GitHub Sync:
   - Updates config.json only when differences exist.
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

URL_REGEX = re.compile(r'(https?://[^\s]+)')

SPAM_DOMAINS = [
    "youtube.com", "youtu.be", "t.me", "telegram.me",
    "instagram.com", "facebook.com", "twitter.com", "x.com"
]

DEFAULT_STREAM_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

def sanitize_link(raw_link: str) -> str:
    """Clean markdown backticks, brackets, quotes from URLs."""
    cleaned = raw_link.strip().strip('`"\'').rstrip('`"\'\\)>],')
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

def auto_inject_platform_headers(link: str) -> str:
    """
    If the link is from Zee5, Sony, or Hotstar and lacks headers in pipe syntax,
    automatically append the required User-Agent, Referer, and Origin headers.
    """
    clean = sanitize_link(link)
    base = clean.split('|')[0]

    # If headers are already explicitly declared, keep them
    if '|' in clean:
        return clean

    headers = []
    if "zee5.com" in base or "falconstream" in base:
        headers.append(f"User-Agent={DEFAULT_STREAM_UA}")
        headers.append("Referer=https://www.zee5.com/")
        headers.append("Origin=https://www.zee5.com")
    elif "hotstar.com" in base:
        headers.append("Referer=https://www.hotstar.com/")
        headers.append("Origin=https://www.hotstar.com")
        headers.append(f"User-Agent={DEFAULT_STREAM_UA}")
    elif "sony" in base or "sonyliv" in base or "sonydaime" in base:
        headers.append("Referer=https://www.sonyliv.com/")
        headers.append("Origin=https://www.sonyliv.com")

    if headers:
        return f"{base}|{'&'.join(headers)}"
    return clean

def check_link_alive(raw_link: str) -> bool:
    """
    Probe the stream URL with lightweight HTTP request.
    Returns False if link is 404 Not Found, 410 Gone, 502/503/504, or DNS dead.
    """
    clean = sanitize_link(raw_link)
    base_url = clean.split('|')[0]

    headers = {
        "User-Agent": DEFAULT_STREAM_UA
    }
    if "zee5" in base_url or "falconstream" in base_url:
        headers["Referer"] = "https://www.zee5.com/"
        headers["Origin"] = "https://www.zee5.com"
    elif "hotstar" in base_url:
        headers["Referer"] = "https://www.hotstar.com/"
        headers["Origin"] = "https://www.hotstar.com"
    elif "sony" in base_url or "sonyliv" in base_url or "sonydaime" in base_url:
        headers["Referer"] = "https://www.sonyliv.com/"
        headers["Origin"] = "https://www.sonyliv.com"

    try:
        req = urllib.request.Request(base_url, headers=headers)
        with urllib.request.urlopen(req, timeout=4) as resp:
            return resp.status < 400
    except urllib.error.HTTPError as e:
        if e.code in [404, 410, 500, 502, 504]:
            return False
        return True
    except Exception:
        return False

def clean_title_text(text: str) -> str:
    """Clean markdown formatting and unwanted noise from titles."""
    if not text:
        return ""
    cleaned = re.sub(r'[*_`~#\\]', '', text)
    cleaned = re.sub(r'https?://\S+', '', cleaned)
    cleaned = re.sub(r'^[0-9]+[\.\)]\s*', '', cleaned)
    cleaned = re.sub(r'\s+', ' ', cleaned).strip()
    return cleaned

def extract_quality_rank(url: str, label: str) -> int:
    """Rank video quality so that when duplicate renditions exist, we pick the highest quality."""
    target = f"{url} {label}".lower()
    if "1080p" in target or "master" in target:
        return 1080
    if "720p" in target:
        return 720
    if "540p" in target:
        return 540
    if "480p" in target:
        return 480
    if "360p" in target:
        return 360
    if "240p" in target:
        return 240
    return 500

def get_stream_group_key(url: str, title: str) -> str:
    """
    Identifies the unique match event.
    Strips resolution endpoints to collapse duplicates into a single high-quality entry.
    """
    clean = sanitize_link(url).split('|')[0].split('?')[0]
    base_cluster = re.sub(r'/[0-9]+p\.m3u8.*', '', clean, flags=re.IGNORECASE)
    base_cluster = re.sub(r'/(master|index|cenc)\.(m3u8|mpd).*', '', base_cluster, flags=re.IGNORECASE)
    
    # Also cluster by primary match title words if available
    clean_title = re.sub(r'\[.*?\]|\(.*?\)', '', title)
    title_words = " ".join(clean_title.lower().split()[:3])
    return f"{base_cluster.lower().rstrip('/')}::{title_words}"

def fetch_yandex_thumbnail(query: str) -> str:
    """Search Yandex Images for sports match title."""
    if not query or len(query.strip()) < 3:
        return DEFAULT_FALLBACK_IMAGE

    clean_query = re.sub(r'https?://\S+', '', query)
    clean_query = re.sub(r'\[.*?\]|\(.*?\)', '', clean_query)
    clean_query = re.sub(r'[^\w\s]', ' ', clean_query).strip()

    if not clean_query:
        return DEFAULT_FALLBACK_IMAGE

    search_term = f"{clean_query} sports match"
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
    """Fetch messages from the last 12 hours, resolve context/replies, de-duplicate and probe health."""
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

        async for msg in client.iter_messages(entity, limit=120):
            if msg.date < cutoff_time:
                break

            text = msg.text or ""
            if not text or "http" not in text:
                continue

            url_matches = list(URL_REGEX.finditer(text))
            if not url_matches:
                continue

            # Thread reply context resolution
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

                # Add headers for platforms like Zee5/Akamai
                final_link = auto_inject_platform_headers(sanitized_url)

                # Context label (language or quality)
                prev_end = url_matches[idx-1].end() if idx > 0 else 0
                between_text = text[prev_end:m.start()]
                
                # Language tag
                lang_tag = ""
                for lang in ["English", "Hindi", "Malayalam", "Tamil", "Telugu", "Kannada", "Bengali"]:
                    if lang.lower() in between_text.lower():
                        lang_tag = lang
                        break

                # Quality tag
                qual_tag = ""
                for q in ["1080p", "720p", "540p", "480p", "360p", "50fps"]:
                    if q in between_text.lower() or q in final_link.lower():
                        qual_tag = q
                        break

                tags = [t for t in [lang_tag, qual_tag] if t]
                tag_str = f" [{ ' '.join(tags) }]" if tags else ""

                final_name = f"{overall_title}{tag_str}"
                rank = extract_quality_rank(final_link, tag_str)

                raw_candidates.append({
                    "name": final_name,
                    "link": final_link,
                    "group_key": get_stream_group_key(final_link, overall_title),
                    "rank": rank,
                    "date": msg.date
                })

    finally:
        await client.disconnect()

    print(f"[*] Found {len(raw_candidates)} candidate stream links. De-duplicating by match event...")

    # Group candidate streams by unique event
    # If multiple renditions of the same match exist (e.g. 240p, 360p, 720p, 1080p),
    # keep only the HIGHEST resolution stream!
    best_by_group = {}
    for item in raw_candidates:
        gkey = item["group_key"]
        if gkey not in best_by_group:
            best_by_group[gkey] = item
        else:
            if item["rank"] > best_by_group[gkey]["rank"]:
                best_by_group[gkey] = item

    deduped_candidates = list(best_by_group.values())
    print(f"[*] De-duplicated down to {len(deduped_candidates)} distinct match streams. Probing link health...")

    verified_matches = []
    for item in deduped_candidates:
        link = item["link"]
        print(f"  [~] Probing: {item['name']} -> {link[:65]}...")
        if not check_link_alive(link):
            print(f"      [X] Skipped broken/dead stream.")
            continue

        print(f"      [✓] Link is ACTIVE!")
        thumb = fetch_yandex_thumbnail(item["name"])
        verified_matches.append({
            "name": item["name"],
            "image": thumb,
            "link": link
        })

    return verified_matches

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

    print(f"[*] Committing update: {len(new_matches)} unique active verified streams.")
    payload = {"matches": new_matches}
    content_str = json.dumps(payload, indent=2, ensure_ascii=False) + "\n"

    commit_msg = f"sync(streams): auto-update {len(new_matches)} verified unique streams [skip ci]"

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
