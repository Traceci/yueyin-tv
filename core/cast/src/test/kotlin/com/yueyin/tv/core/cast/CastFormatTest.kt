package com.yueyin.tv.core.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastFormatTest {
    @Test
    fun `range with explicit end is clamped to file size`() {
        assertEquals(0L..99L, parseHttpRange("bytes=0-99", 100))
        assertEquals(0L..99L, parseHttpRange("bytes=0-999", 100))
    }

    @Test
    fun `open ended range runs to last byte`() {
        assertEquals(50L..99L, parseHttpRange("bytes=50-", 100))
    }

    @Test
    fun `invalid ranges fall back to full response`() {
        assertNull(parseHttpRange(null, 100))
        assertNull(parseHttpRange("bytes=100-", 100))
        assertNull(parseHttpRange("bytes=80-20", 100))
        assertNull(parseHttpRange("bytes=0-10,20-30", 100))
        assertNull(parseHttpRange("bytes=abc-", 100))
        assertNull(parseHttpRange("bytes=0-10", -1))
    }

    @Test
    fun `didl metadata escapes special characters`() {
        val source = CastSource(
            title = "A&B <test>",
            artist = "Q\"uote",
            album = null,
            mimeType = "audio/mpeg",
            uri = "content://x",
            durationMs = 1_000,
        )
        val didl = buildDidlMetadata(source, "http://192.168.1.5:1234/m/abc")
        assertTrue(didl.contains("A&amp;B &lt;test&gt;"))
        assertTrue(didl.contains("Q&quot;uote"))
        assertTrue(didl.contains("protocolInfo=\"http-get:*:audio/mpeg:*\""))
        assertTrue(didl.endsWith("</DIDL-Lite>"))
    }
}
