#!/usr/bin/env python3
"""
BobStream Telegram Stream Sync Agent (Enhanced Edition)
Runs on GitHub Actions via cron every 5 minutes and manual dispatch.

Key Upgrades & Features:
1. Smart Title Resolution:
   - Filters out chat noise ("please give link", "eventcategory", "50fps chiye", "Multi quality").
   - Multi-tier search: checks message text, reply context, and semantic URL paths (INDNZ -> India vs New Zealand).
2. Fixes Asterisk (***) Bug:
   - Thoroughly sanitizes URLs, stripping trailing markdown asterisks, brackets, quotes, and query malformations (?|).
   - Validates ClearKey DRM key hex format ([0-9a-f]{32}:[0-9a-f]{32}) to avoid unplayable streams.
3. Standalone DRM Key Auto-Stitching:
   - Detects standalone KID:KEY posted in messages or replies and stitches them into DASH (.mpd) streams.
4. Token Expiry Pre-Check:
   - Discards links whose 'exp=' or 'Expires=' UNIX timestamp has already expired.
5. De-duplication:
   - Keeps only the highest quality stream (1080p > 720p > 540p > 480p > 360p) per event cluster.
6. Auto-Injected Platform Headers:
   - Injects required headers for Zee5, SonyLIV (slivcdn, sonymtm, sonyeventsglobal, sonydaime), and Hotstar.
7. High-Resolution Poster Match:
   - Queries Yandex Images for sports posters with fallback to Unsplash.
"""

import os
import re
import json
import time
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
CLEARKEY_REGEX = re.compile(r'\b([0-9a-fA-F]{32}):([0-9a-fA-F]{32})\b')
STANDALONE_KID_KEY_REGEX = re.compile(
    r'(?:kid|key)[:=\s]+([0-9a-fA-F]{32})[\s\S]{0,30}?(?:kid|key)[:=\s]+([0-9a-fA-F]{32})',
    re.IGNORECASE
)

SPAM_DOMAINS = [
    "youtube.com", "youtu.be", "t.me", "telegram.me",
    "instagram.com", "facebook.com", "twitter.com", "x.com"
]

DEFAULT_STREAM_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

GARBAGE_TITLE_PATTERNS = [
    r'^(please\s+)?(give\s+)?link',
    r'link\s*(anyone|plz|pls|bhai)?',
    r'eventcategory',
    r'50fps\s*chiye',
    r'multi\s*quality',
    r'live\s*stream$',
    r'^\s*[\{\}\[\]"\'\:\,\;]+\s*$',
    r'^\s*curl\s+',
    r'^\s*match\s*\d+\s*$',
    r'^\s*\d+[\.\)]\s*$',
    r'working\s*link',
    r'best\s*quality'
]

SEMANTIC_URL_PATTERNS = [
    (r'(?:indnz|indvsnz|nzvsind)', "India vs New Zealand"),
    (r'(?:afgzim|afgvszim|zimvsafg)', "Afghanistan vs Zimbabwe"),
    (r'slk-m0?(\d+)', r'Super League Kerala Match \1'),
    (r'cricodi', "Cricket ODI Live"),
    (r'criclive', "Cricket Live"),
    (r'golf_gen', "Golf Live"),
    (r'4250266', "Al Nassr Live"),
    (r'4249697', "AFG vs ZIM Final Live"),
    (r'east_bengal|inter_kashi', "Inter Kashi vs East Bengal FC"),
    (r'isl', "Indian Super League"),
    (r'f1|grand_prix', "F1 Grand Prix Live")
]

def sanitize_link(raw_link: str) -> str:
    """Clean markdown backticks, asterisks, brackets, quotes and malformed query separators from URLs."""
    if not raw_link:
        return ""
    
    cleaned = raw_link.strip().strip('*_`"\'><()[]\\;,')
    cleaned = cleaned.rstrip('*_`"\'><()[]\\;,.')

    cleaned = re.sub(r'[\?\&]\|', '|', cleaned)

    if "drmLicense=" in cleaned or "drm_license=" in cleaned:
        cleaned = re.sub(r'((?:drmLicense|drm_license)=[0-9a-fA-F]{32}:[0-9a-fA-F]{32})[*_`"\'><\)]+', r'\1', cleaned)

    return cleaned.strip()

def is_valid_stream_format(url: str) -> bool:
    """Filter out web pages/social links; prioritize playable streams (.m3u8, .mpd, .mp4, cmaf, live CDN paths)."""
    base = url.split('|')[0].lower()
    for spam in SPAM_DOMAINS:
        if spam in base:
            return False

    valid_indicators = [
        ".m3u8", ".mpd", ".mp4", ".mkv", "live", "cmaf", "stream", "chunk",
        "hls", "dash", "manifest", "master", "fancode", "hotstar", "zee5", "sony",
        "akamaized", "slivcdn", "pv-cdn", "aiv-cdn", "fastly-edge"
    ]
    return any(ind in base for ind in valid_indicators)

def is_token_expired(url: str) -> bool:
    """Checks if temporary authorization tokens (exp= or Expires=) in the URL have passed."""
    clean = sanitize_link(url).split('|')[0]
    m = re.search(r'(?:exp|Expires|expire)=([0-9]{10})', clean, flags=re.IGNORECASE)
    if m:
        token_exp = int(m.group(1))
        current_time = int(time.time())
        if token_exp < (current_time - 60):
            return True
    return False

def auto_inject_platform_headers(link: str) -> str:
    """
    If the link is from Zee5, Sony, or Hotstar and lacks headers in pipe syntax,
    automatically append the required User-Agent, Referer, and Origin headers.
    """
    clean = sanitize_link(link)
    base = clean.split('|')[0]

    if '|' in clean:
        return clean

    headers = []
    base_lower = base.lower()

    if "zee5.com" in base_lower or "falconstream" in base_lower:
        headers.append(f"User-Agent={DEFAULT_STREAM_UA}")
        headers.append("Referer=https://www.zee5.com/")
        headers.append("Origin=https://www.zee5.com")
    elif "hotstar.com" in base_lower:
        headers.append("Referer=https://www.hotstar.com/")
        headers.append("Origin=https://www.hotstar.com")
        headers.append(f"User-Agent={DEFAULT_STREAM_UA}")
    elif any(s in base_lower for s in ["sony", "sonyliv", "slivcdn", "sonyeventsglobal", "sonymtmnew", "sonydaime"]):
        headers.append("Referer=https://www.sonyliv.com/")
        headers.append("Origin=https://www.sonyliv.com")

    if headers:
        return f"{base}|{'&'.join(headers)}"
    return clean

def extract_drm_key_from_context(text: str, parent_text: str = "") -> str:
    """Scans message text and parent reply context for standalone ClearKey hex pairs."""
    combined = f"{text}\n{parent_text}"
    
    m = CLEARKEY_REGEX.search(combined)
    if m:
        return f"{m.group(1).lower()}:{m.group(2).lower()}"

    m2 = STANDALONE_KID_KEY_REGEX.search(combined)
    if m2:
        return f"{m2.group(1).lower()}:{m2.group(2).lower()}"

    return ""

def check_link_alive(raw_link: str) -> bool:
    """
    Probe the stream URL with lightweight HTTP request.
    Returns False if link is expired, 404 Not Found, 410 Gone, 502/503/504, or DNS dead.
    """
    clean = sanitize_link(raw_link)
    base_url = clean.split('|')[0]

    if is_token_expired(base_url):
        print(f"      [!] Discarded: Token expired in UNIX timestamp.")
        return False

    headers = {
        "User-Agent": DEFAULT_STREAM_UA
    }
    base_lower = base_url.lower()

    if "zee5" in base_lower or "falconstream" in base_lower:
        headers["Referer"] = "https://www.zee5.com/"
        headers["Origin"] = "https://www.zee5.com"
    elif "hotstar" in base_lower:
        headers["Referer"] = "https://www.hotstar.com/"
        headers["Origin"] = "https://www.hotstar.com"
    elif any(s in base_lower for s in ["sony", "sonyliv", "slivcdn", "sonyeventsglobal", "sonymtmnew", "sonydaime"]):
        headers["Referer"] = "https://www.sonyliv.com/"
        headers["Origin"] = "https://www.sonyliv.com"

    try:
        req = urllib.request.Request(base_url, headers=headers)
        with urllib.request.urlopen(req, timeout=4.5) as resp:
            return resp.status < 400
    except urllib.error.HTTPError as e:
        if e.code in [401, 403, 404, 410, 500, 502, 503, 504]:
            return False
        return True
    except Exception:
        return False

def is_garbage_title(text: str) -> bool:
    """Checks if a title string contains only generic chat noise rather than a match name."""
    if not text or len(text.strip()) < 3:
        return True
    cleaned = text.strip().lower()
    for pat in GARBAGE_TITLE_PATTERNS:
        if re.search(pat, cleaned, re.IGNORECASE):
            return True
    return False

def infer_semantic_title_from_url(url: str) -> str:
    """Decodes match clues directly from URL paths when text title is weak or missing."""
    url_lower = url.lower()
    for pat, label in SEMANTIC_URL_PATTERNS:
        m = re.search(pat, url_lower)
        if m:
            if r'\1' in label and m.groups():
                return re.sub(pat, label, m.group(0), flags=re.IGNORECASE)
            return label
    return ""

def clean_title_text(text: str) -> str:
    """Clean markdown formatting, chat artifacts, and unwanted noise from titles."""
    if not text:
        return ""
    cleaned = re.sub(r'[*_`~#\\]', '', text)
    cleaned = re.sub(r'https?://\S+', '', cleaned)
    cleaned = re.sub(r'^[0-9]+[\.\)]\s*', '', cleaned)
    cleaned = re.sub(r'[<>{}\[\]"\']', '', cleaned)
    cleaned = re.sub(r'\s+', ' ', cleaned).strip()
    return cleaned

def resolve_best_title(message_text: str, parent_text: str, url: str) -> str:
    """
    Intelligent Title Resolution:
    1. Evaluates lines of message text for valid sports event patterns.
    2. Evaluates parent reply message if available.
    3. Falls back to semantic URL path inference.
    """
    url_clean = url.split('|')[0]
    lines = [clean_title_text(l) for l in message_text.splitlines() if l.strip()]
    candidate_title = ""
    for line in lines:
        if line and not line.startswith("http") and not is_garbage_title(line):
            candidate_title = line
            break

    if not candidate_title and parent_text:
        p_lines = [clean_title_text(l) for l in parent_text.splitlines() if l.strip()]
        for pline in p_lines:
            if pline and not pline.startswith("http") and not is_garbage_title(pline):
                candidate_title = pline
                break

    if not candidate_title or is_garbage_title(candidate_title):
        inferred = infer_semantic_title_from_url(url_clean)
        if inferred:
            candidate_title = inferred

    if not candidate_title:
        candidate_title = "Live Sports Match"

    return candidate_title

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
    
    clean_title = re.sub(r'\[.*?\]|\(.*?\)', '', title)
    title_words = " ".join(clean_title.lower().split()[:3])
    return f"{base_cluster.lower().rstrip('/')}::{title_words}"

def fetch_yandex_thumbnail(query: str) -> str:
    """Search Yandex Images for sports match title."""
    clean_query = re.sub(r'\[.*?\]|\(.*?\)', '', query)
    clean_query = re.sub(r'[^a-zA-Z0-9\s]', '', clean_query).strip()
    if not clean_query:
        return DEFAULT_FALLBACK_IMAGE

    search_term = f"{clean_query} sports match"
    try:
        url = "https://yandex.com/images/search?text=" + urllib.parse.quote(search_term)
        req = urllib.request.Request(
            url,
            headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"}
        )
        with urllib.request.urlopen(req, timeout=5) as response:
            html = response.read().decode('utf-8', errors='ignore')

        matches = re.findall(r'&quot;thumbUrl&quot;:&quot;(https?://[^&]+)&quot;', html)
        if matches:
            first_img = matches[0].replace('\\/', '/')
            return first_img

        matches_orig = re.findall(r'&quot;origin&quot;:\{&quot;url&quot;:&quot;(https?://[^&]+)&quot;', html)
        if matches_orig:
            first_img = matches_orig[0].replace('\\/', '/')
            return first_img
    except Exception:
        pass

    return DEFAULT_FALLBACK_IMAGE

async def resolve_target_entity(client, target):
    """Resolves target Telegram channel or group using username or integer ID."""
    target_str = str(target).strip()

    try:
        numeric_id = int(target_str)
        return await client.get_entity(numeric_id)
    except Exception:
        pass

    try:
        return await client.get_entity(target_str)
    except Exception:
        pass

    print(f"[*] Searching joined dialogs for target: '{target_str}'...")
    async for dialog in client.iter_dialogs(limit=250):
        if str(dialog.id) == target_str:
            return dialog.entity
        name = getattr(dialog, 'name', '') or getattr(dialog.entity, 'title', '')
        if target_str.lower() in name.lower() or "sport" in name.lower():
            print(f"[✓] Matched dialog '{name}' with ID: {dialog.id}")
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

            context_drm_key = extract_drm_key_from_context(text, parent_text)

            for idx, m in enumerate(url_matches):
                sanitized_url = sanitize_link(m.group(0))

                if not is_valid_stream_format(sanitized_url):
                    continue

                if (".mpd" in sanitized_url or "cenc" in sanitized_url) and "drmLicense=" not in sanitized_url:
                    if context_drm_key:
                        sep = "&" if "|" in sanitized_url else "|"
                        sanitized_url = f"{sanitized_url}{sep}drmScheme=clearkey&drmLicense={context_drm_key}"

                final_link = auto_inject_platform_headers(sanitized_url)

                prev_end = url_matches[idx-1].end() if idx > 0 else 0
                between_text = text[prev_end:m.start()]
                
                lang_tag = ""
                for lang in ["English", "Hindi", "Malayalam", "Tamil", "Telugu", "Kannada", "Bengali"]:
                    if lang.lower() in between_text.lower() or f"/{lang.lower()[:3]}/" in final_link.lower():
                        lang_tag = lang
                        break

                qual_tag = ""
                for q in ["1080p", "720p", "540p", "480p", "360p", "50fps"]:
                    if q in between_text.lower() or q in final_link.lower():
                        qual_tag = q
                        break

                tags = [t for t in [lang_tag, qual_tag] if t]
                tag_str = f" [{ ' '.join(tags) }]" if tags else ""

                base_title = resolve_best_title(text, parent_text, final_link)
                final_name = f"{base_title}{tag_str}"
                rank = extract_quality_rank(final_link, tag_str)

                raw_candidates.append({
                    "name": final_name,
                    "link": final_link,
                    "group_key": get_stream_group_key(final_link, base_title),
                    "rank": rank,
                    "date": msg.date
                })

    finally:
        await client.disconnect()

    print(f"[*] Found {len(raw_candidates)} candidate stream links. De-duplicating by match event...")

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
        raise e

if __name__ == "__main__":
    asyncio.run(main())
