package com.yueyin.tv.core.playback

import java.util.ArrayDeque
import java.util.HashMap

/**
 * 按音源统计取链耗时与失败率（滑动窗口，B 方案「取链测速」）。
 *
 * 两个用途：
 * 1. 诊断：一行汇总日志，哪个源慢、哪个源失败率高，一眼可读；
 * 2. 调度：预取阶段对「失败率高且平均慢」的源收紧超时——反正大概率取不到，
 *    快失败才能尽快补位到后面的候选，别让唯一的 WebView 干等十几秒。
 *
 * 纯 JVM 实现（不碰 android.*），可单元测试。样本按窗口滚动，只看最近 [windowSize] 次。
 */
class StreamSourceStats(private val windowSize: Int = 20) {

    data class Snapshot(
        val source: String,
        val samples: Int,
        val avgMs: Long,
        /** 0.0～1.0，窗口内失败占比。 */
        val failRate: Double,
    )

    private class Window(private val size: Int) {
        val elapsedMs = ArrayDeque<Long>()
        val outcomes = ArrayDeque<Boolean>()

        fun add(ms: Long, success: Boolean) {
            if (elapsedMs.size >= size) {
                elapsedMs.removeFirst()
                outcomes.removeFirst()
            }
            elapsedMs.addLast(ms)
            outcomes.addLast(success)
        }

        fun snapshot(source: String): Snapshot {
            val n = elapsedMs.size
            val avg = if (n == 0) 0L else elapsedMs.sum() / n
            val fails = outcomes.count { !it }
            return Snapshot(source, n, avg, if (n == 0) 0.0 else fails.toDouble() / n)
        }
    }

    private val windows = HashMap<String, Window>()

    /** 记一次取链结果，返回该源当前的窗口快照（供调用方决定是否打汇总日志）。 */
    @Synchronized
    fun record(source: String, elapsedMs: Long, success: Boolean): Snapshot {
        val window = windows.getOrPut(source) { Window(windowSize) }
        window.add(elapsedMs, success)
        return window.snapshot(source)
    }

    @Synchronized
    fun snapshot(source: String): Snapshot? =
        windows[source]?.takeIf { it.elapsedMs.isNotEmpty() }?.snapshot(source)

    /** 该源是否「失败率高且平均慢」：这类源预取时收紧超时、快失败快补位。 */
    @Synchronized
    fun isUnhealthy(source: String): Boolean {
        val s = snapshot(source) ?: return false
        return s.samples >= UNHEALTHY_MIN_SAMPLES &&
            s.failRate >= UNHEALTHY_FAIL_RATE &&
            s.avgMs >= UNHEALTHY_MIN_AVG_MS
    }

    /** 预取建议超时：不健康的源给 [SLOW_SOURCE_TIMEOUT_MS]；其余返回 0（不限时）。 */
    fun suggestPrefetchTimeoutMs(source: String): Long =
        if (isUnhealthy(source)) SLOW_SOURCE_TIMEOUT_MS else 0L

    /** 一行汇总，进诊断日志。 */
    @Synchronized
    fun summary(): String {
        if (windows.isEmpty()) return "暂无样本"
        return windows.entries.joinToString("；") { (source, w) ->
            val s = w.snapshot(source)
            "${s.source} ${s.samples}次 平均${s.avgMs}ms 失败${(s.failRate * 100).toInt()}%"
        }
    }

    companion object {
        /** 判定「不健康源」的最小样本数：少于这个不武断收紧。 */
        const val UNHEALTHY_MIN_SAMPLES = 3

        /** 失败率达到该值视为高风险。 */
        const val UNHEALTHY_FAIL_RATE = 0.5

        /** 平均耗时超过该值视为慢源。 */
        const val UNHEALTHY_MIN_AVG_MS = 4_000L

        /** 不健康源的预取超时。 */
        const val SLOW_SOURCE_TIMEOUT_MS = 3_000L

        /** 从 mediaId 前缀解析音源名（mg:/kw:/wy:/kg:/tx:，与队列里的 id 约定一致）。 */
        fun sourceOf(mediaId: String): String = when {
            mediaId.startsWith("mg:") -> "migu"
            mediaId.startsWith("kw:") -> "kuwo"
            mediaId.startsWith("wy:") -> "netease"
            mediaId.startsWith("kg:") -> "kugou"
            mediaId.startsWith("tx:") -> "tencent"
            else -> "other"
        }
    }
}
