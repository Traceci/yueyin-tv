package com.yueyin.tv.core.model.stream

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 直链缓存的有效期判断。
 *
 * 这几条对应线上反馈的「播放失败：网络连接不上音源，点下一曲也失败、点重试又能播」：
 * 根因是缓存里还躺着一条已经过期（或太老）的地址，却被当成可用链交给播放器。
 */
class StreamUrlCacheTest {

    @After
    fun tearDown() {
        StreamUrlCache.clear()
        StreamUrlCache.ttlMs = 2 * 60_000L
    }

    @Test
    fun `url 自带的过期时间已到就丢弃`() {
        val expired = System.currentTimeMillis() / 1000 - 60
        StreamUrlCache.put("k", "https://cdn.example.com/a.mp3?expire=$expired&sign=abc")
        assertNull(StreamUrlCache.get("k"))
    }

    @Test
    fun `url 自带的过期时间还早就继续用`() {
        val future = System.currentTimeMillis() / 1000 + 600
        val url = "https://cdn.example.com/a.mp3?expire=$future&sign=abc"
        StreamUrlCache.put("k", url)
        assertEquals(url, StreamUrlCache.get("k"))
    }

    @Test
    fun `毫秒级的时间戳同样识别`() {
        val expired = System.currentTimeMillis() - 1_000
        StreamUrlCache.put("k", "https://cdn.example.com/a.mp3?expire=$expired")
        assertNull(StreamUrlCache.get("k"))
    }

    @Test
    fun `没有过期参数时按年龄判断`() {
        StreamUrlCache.put("k", "https://cdn.example.com/a.mp3")
        // maxAge 传负数：任何条目都算太老，用来确认年龄分支真的生效。
        assertNull(StreamUrlCache.get("k", maxAgeMs = -1))
    }

    @Test
    fun `新鲜条目在默认时限内可用`() {
        val url = "https://cdn.example.com/a.mp3"
        StreamUrlCache.put("k", url)
        assertEquals(url, StreamUrlCache.get("k"))
        // 默认兜底时限已经收紧到 2 分钟（原 5 分钟远大于脚本链真实有效期）。
        assertTrue(StreamUrlCache.ttlMs <= 2 * 60_000L)
    }

    @Test
    fun `作废后立刻取不到`() {
        StreamUrlCache.put("k", "https://cdn.example.com/a.mp3")
        StreamUrlCache.invalidate("k")
        assertNull(StreamUrlCache.get("k"))
    }
}
