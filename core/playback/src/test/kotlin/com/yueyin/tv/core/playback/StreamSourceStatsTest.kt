package com.yueyin.tv.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B 方案「取链测速」的滑动窗口统计：样本滚动、阈值判定、超时建议。 */
class StreamSourceStatsTest {

    @Test
    fun `sourceOf resolves prefix map`() {
        assertEquals("migu", StreamSourceStats.sourceOf("mg:69906200266"))
        assertEquals("kuwo", StreamSourceStats.sourceOf("kw:658552469"))
        assertEquals("netease", StreamSourceStats.sourceOf("wy:3404799468"))
        assertEquals("kugou", StreamSourceStats.sourceOf("kg:9092283E0891"))
        assertEquals("tencent", StreamSourceStats.sourceOf("tx:003D86wN2bbuqU"))
        assertEquals("other", StreamSourceStats.sourceOf("local:something"))
    }

    @Test
    fun `snapshot reflects average and failure rate`() {
        val stats = StreamSourceStats()
        stats.record("migu", 1_000, true)
        stats.record("migu", 3_000, false)
        val s = stats.snapshot("migu")!!
        assertEquals(2, s.samples)
        assertEquals(2_000L, s.avgMs)
        assertEquals(0.5, s.failRate, 1e-9)
    }

    @Test
    fun `window rolls keeping only recent samples`() {
        val stats = StreamSourceStats(windowSize = 3)
        stats.record("kw", 1_000, false)
        stats.record("kw", 1_000, false)
        stats.record("kw", 1_000, false)
        // 窗口只有 3 格：新样本进来，最旧的被挤掉。
        stats.record("kw", 1_000, true)
        val s = stats.snapshot("kw")!!
        assertEquals(3, s.samples)
        assertEquals(1_000L, s.avgMs)
        // 最近 3 次 = 失败、失败、成功 → 失败率 2/3，旧的三连败已滚出窗口。
        assertEquals(2.0 / 3, s.failRate, 1e-9)
    }

    @Test
    fun `unhealthy source gets tightened prefetch timeout`() {
        val stats = StreamSourceStats()
        // 少于最小样本数：不武断收紧。
        stats.record("mg", 9_000, false)
        stats.record("mg", 9_000, false)
        assertFalse(stats.isUnhealthy("mg"))
        assertEquals(0L, stats.suggestPrefetchTimeoutMs("mg"))
        // 第 3 次：3 样本、全失败、平均 9s ≥ 4s → 不健康，预取收紧到 3 秒。
        stats.record("mg", 9_000, false)
        assertTrue(stats.isUnhealthy("mg"))
        assertEquals(StreamSourceStats.SLOW_SOURCE_TIMEOUT_MS, stats.suggestPrefetchTimeoutMs("mg"))
    }

    @Test
    fun `healthy fast source keeps unlimited prefetch timeout`() {
        val stats = StreamSourceStats()
        repeat(10) { stats.record("kw", 800, true) }
        assertFalse(stats.isUnhealthy("kw"))
        assertEquals(0L, stats.suggestPrefetchTimeoutMs("kw"))
    }

    @Test
    fun `snapshot of unknown source is null`() {
        val stats = StreamSourceStats()
        assertNull(stats.snapshot("migu"))
    }

    @Test
    fun `summary lists all sources`() {
        val stats = StreamSourceStats()
        stats.record("kuwo", 1_000, true)
        stats.record("migu", 5_000, false)
        val text = stats.summary()
        assertTrue(text.contains("kuwo"))
        assertTrue(text.contains("migu"))
    }
}
