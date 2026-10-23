package com.bobstream.app.util

import android.util.Base64
import com.bobstream.app.data.model.DrmScheme
import com.bobstream.app.data.model.StreamRequest
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object StreamParser {

    /**
     * Parses stream links with pipe syntax:
     * e.g. "https://example.com/manifest.mpd|User-Agent=Mozilla/5.0&Referer=https://site.com&drm_key=kidHex:keyHex"
     */
    fun parse(title: String, rawLink: String): StreamRequest {
        val trimmed = rawLink.trim()
        if (!trimmed.contains("|")) {
            return StreamRequest(
                title = title,
                mediaUrl = trimmed,
                headers = emptyMap(),
                drmScheme = DrmScheme.NONE,
                drmKeyOrLicense = null
            )
        }

        val parts = trimmed.split("|", limit = 2)
        val baseUrl = parts[0].trim()
        val queryPart = parts.getOrNull(1)?.trim().orEmpty()

        val headers = mutableMapOf<String, String>()
        var drmKeyVal: String? = null

        val pairs = queryPart.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = pair.substring(0, idx).trim()
                val rawValue = pair.substring(idx + 1).trim()
                val value = try {
                    URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name())
                } catch (e: Exception) {
                    rawValue
                }

                if (key.equals("drm_key", ignoreCase = true) || key.equals("drm", ignoreCase = true) || key.equals("key", ignoreCase = true)) {
                    drmKeyVal = value
                } else {
                    headers[key] = value
                }
            }
        }

        val (scheme, resolvedDrm) = resolveDrm(drmKeyVal)

        return StreamRequest(
            title = title,
            mediaUrl = baseUrl,
            headers = headers,
            drmScheme = scheme,
            drmKeyOrLicense = resolvedDrm
        )
    }

    /**
     * Resolves DRM Scheme and sanitized payload.
     * Supports:
     * - ClearKey hex pair: "kidHex:keyHex"
     * - Widevine HTTP license: "https://..."
     */
    fun resolveDrm(drmKeyInput: String?): Pair<DrmScheme, String?> {
        if (drmKeyInput.isNullOrBlank()) {
            return Pair(DrmScheme.NONE, null)
        }
        val clean = drmKeyInput.trim()

        return when {
            clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true) -> {
                Pair(DrmScheme.WIDEVINE, clean)
            }
            clean.contains(":") -> {
                Pair(DrmScheme.CLEARKEY, clean)
            }
            else -> {
                Pair(DrmScheme.NONE, null)
            }
        }
    }

    /**
     * Converts a ClearKey hex pair (kid:key) into standard W3C ClearKey JSON byte payload:
     * {"keys":[{"kty":"oct","k":"<base64UrlKey>","kid":"<base64UrlKid>"}],"type":"temporary"}
     */
    fun buildClearKeyW3cJsonBytes(kidHexKeyHex: String): ByteArray {
        val parts = kidHexKeyHex.split(":")
        require(parts.size == 2) { "ClearKey must be formatted as <kidHex>:<keyHex>" }

        val kidHex = parts[0].trim().replace("-", "").replace(" ", "")
        val keyHex = parts[1].trim().replace("-", "").replace(" ", "")

        val kidBytes = hexToBytes(kidHex)
        val keyBytes = hexToBytes(keyHex)

        // URL-safe Base64 without padding
        val kidB64 = Base64.encodeToString(kidBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val keyB64 = Base64.encodeToString(keyBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        val json = """{"keys":[{"kty":"oct","k":"$keyB64","kid":"$kidB64"}],"type":"temporary"}"""
        return json.toByteArray(StandardCharsets.UTF_8)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            val high = Character.digit(hex[i], 16)
            val low = Character.digit(hex[i + 1], 16)
            data[i / 2] = ((high shl 4) + low).toByte()
            i += 2
        }
        return data
    }
}
