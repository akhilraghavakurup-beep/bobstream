# 🌊 BobStream — Production Android Media & Torrent Streaming Engine

**BobStream** is a modern, high-performance Android streaming application built with **Jetpack Compose (Material 3)**, **AndroidX Media3 (ExoPlayer)**, and a **Sequential Torrent Streaming Engine**.

---

## ⚡ Core Features

1. **Matches Tab (Live Sports & IPTV)**
   - Cloud-fetched JSON configuration with GitHub CDN cache-busting (`?t=timestamp`).
   - Advanced pipe syntax (`|`) for injecting custom HTTP headers (`User-Agent`, `Referer`, `Origin`, `Cookie`).
   - Smart DRM resolution for **ClearKey (W3C Base64Url)** and **Widevine (HTTP License)** streams.

2. **Movies Tab (P2P Sequential Streaming)**
   - Sequential BitTorrent piece streaming via embedded local HTTP proxy (`http://127.0.0.1:port/stream.mp4`).
   - Dynamic peer and speed telemetry overlay during pre-buffering.

3. **Manual Stream (IPTV & Network Tester)**
   - Full-featured URL tester with preset chips (including preloaded Live Golf HLS).
   - Dynamic DRM Scheme selector (None, ClearKey, Widevine, PlayReady).

4. **Media3 Player Overlay & Gesture Controls**
   - Double-tap horizontal seeking (±10s).
   - Vertical edge swipe gestures: **Left screen brightness**, **Right system volume** with animated visual HUDs.
   - Dynamic Aspect Ratio toggling: **FIT**, **FILL**, and **ZOOM**.
   - Built-in Audio and Subtitle track selection dialog.
   - Comprehensive error mapping for HTTP 401/403, DRM license failures, and peer starvation.

---

## 🚀 How to Build Using GitHub Actions (Zero Local SDK Required)

1. Initialize a Git repository inside this directory and push to GitHub:
   ```bash
   git init
   git add .
   git commit -m "Initial BobStream commit"
   git remote add origin https://github.com/<your-username>/bobstream.git
   git branch -M main
   git push -u origin main
   ```

2. Go to the **Actions** tab on your GitHub repository.
3. The workflow `.github/workflows/build.yml` will automatically:
   - Set up JDK 17 & Gradle
   - Run unit tests
   - Build **Debug APK** (`app-debug.apk`) and **Release APK** (`app-release.apk`)
   - Publish them under the **Artifacts** section ready to download directly to your Android device!

---

## 📄 Remote `config.json` Schema

Host your `config.json` on GitHub Raw, Gist, or Cloudflare:

```json
{
  "matches": [
    {
      "name": "Live Golf HD",
      "image": "https://example.com/banner.jpg",
      "link": "https://sonydaime.akamaized.net/hls/live/2120301/Golf_Gen2310/ENG/master.m3u8?hdnea=exp=1792778646~acl=*~id=1556784484fancode~hmac=97a6994adaff4017be58933cbbbfafd43b05658cb1f35d72b9dadbef2a57e9fc|User-Agent=Mozilla/5.0&Referer=https://fancode.com/"
    }
  ],
  "movies": [
    {
      "name": "Big Buck Bunny (1080p)",
      "photo": "https://upload.wikimedia.org/wikipedia/commons/thumb/c/c5/Big_buck_bunny_poster_big.jpg/800px-Big_buck_bunny_poster_big.jpg",
      "magnet": "magnet:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c..."
    }
  ]
}
```

---

## 🧪 Pre-Configured Test Streams

1. **Test 1: Live Sports Stream (Akamai HLS)**
   - **Stream**: Sony/FanCode Live Golf HD
   - **URL**: `https://sonydaime.akamaized.net/hls/live/2120301/Golf_Gen2310/ENG/master.m3u8?hdnea=...`
   - **Headers**: `User-Agent: Mozilla/5.0...`, `Referer: https://fancode.com/`
   - **Access**: Located under **Matches Tab** as well as preset chip in **Tester Tab**.

2. **Test 2: P2P Torrent Stream (Magnet Link)**
   - **Title**: Dorothy (2026) Tamil HQ HDRip 720p
   - **Magnet**: `magnet:?xt=urn:btih:b57ef56f5a2a2b43e92553de6f31b288b19cab6b...`
   - **Access**: Located under **Movies Tab** as well as preset chip in **Tester Tab**.
