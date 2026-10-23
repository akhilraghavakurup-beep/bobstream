package com.bobstream.app

import com.bobstream.app.data.model.DrmScheme
import com.bobstream.app.util.StreamParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class StreamParserTest {

    @Test
    fun testParseSimpleUrl() {
        val raw = "https://example.com/live.m3u8"
        val req = StreamParser.parse("Test Stream", raw)

        assertEquals("Test Stream", req.title)
        assertEquals("https://example.com/live.m3u8", req.mediaUrl)
        assertTrue(req.headers.isEmpty())
        assertEquals(DrmScheme.NONE, req.drmScheme)
        assertNull(req.drmKeyOrLicense)
    }

    @Test
    fun testParsePipeWithHeadersAndDrm() {
        val raw = "https://sonydaime.akamaized.net/live/master.m3u8|User-Agent=Mozilla/5.0&Referer=https://fancode.com/&drm_key=eb676abbcb345e96bbcf616630f1a3da:100b6c20940f779a4589152b57d2dacb"
        val req = StreamParser.parse("Golf Live", raw)

        assertEquals("Golf Live", req.title)
        assertEquals("https://sonydaime.akamaized.net/live/master.m3u8", req.mediaUrl)
        assertEquals("Mozilla/5.0", req.headers["User-Agent"])
        assertEquals("https://fancode.com/", req.headers["Referer"])
        assertEquals(DrmScheme.CLEARKEY, req.drmScheme)
        assertEquals("eb676abbcb345e96bbcf616630f1a3da:100b6c20940f779a4589152b57d2dacb", req.drmKeyOrLicense)
    }

    @Test
    fun testParseWidevineUrl() {
        val raw = "https://example.com/dash.mpd|drm_key=https://proxy.widevine.com/proxy"
        val req = StreamParser.parse("Widevine Test", raw)

        assertEquals(DrmScheme.WIDEVINE, req.drmScheme)
        assertEquals("https://proxy.widevine.com/proxy", req.drmKeyOrLicense)
    }

    @Test
    fun testClearKeyW3cJsonSynthesis() {
        val kidHex = "eb676abbcb345e96bbcf616630f1a3da"
        val keyHex = "100b6c20940f779a4589152b57d2dacb"
        val jsonBytes = StreamParser.buildClearKeyW3cJsonBytes("$kidHex:$keyHex")
        val jsonStr = String(jsonBytes, StandardCharsets.UTF_8)

        assertTrue(jsonStr.contains("\"keys\":[{\"kty\":\"oct\""))
        assertTrue(jsonStr.contains("\"type\":\"temporary\""))
        assertTrue(jsonStr.contains("\"kid\":"))
        assertTrue(jsonStr.contains("\"k\":"))
    }
}
