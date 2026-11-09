package com.yueyin.tv.core.cast

/**
 * HTTP Range 解析（服务端自用）。
 *
 * 为什么单独提出来：DLNA 渲染器几乎一定会带 `Range` 来 seek，
 * 解析错一次就会「能放但不能拖进度」，这类问题只能靠单测兜住。
 * 返回 null 表示按整文件 200 响应。
 */
internal fun parseHttpRange(header: String?, total: Long): LongRange? {
    if (header == null || total <= 0) return null
    val raw = header.removePrefix("bytes=").trim()
    if (raw.contains(',')) return null
    val dash = raw.indexOf('-')
    if (dash < 0) return null
    val startText = raw.substring(0, dash).trim()
    val endText = raw.substring(dash + 1).trim()
    val start = startText.toLongOrNull() ?: return null
    val end = endText.toLongOrNull() ?: (total - 1)
    if (start !in 0 until total) return null
    val safeEnd = end.coerceAtMost(total - 1)
    if (safeEnd < start) return null
    return start..safeEnd
}
