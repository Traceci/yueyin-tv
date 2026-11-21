package com.yueyin.tv.core.playback

import java.net.HttpURLConnection
import java.net.URL

/**
 * 直链探活：拿到播放直链后、交给播放器前，先用一个极小的 Range 请求确认链接活着。
 *
 * 背景：脚本取链「成功」不等于链接能播。平台签名直链会过期、被下架、或干脆返回一个
 * 网页错误页（HTTP 200 + text/html）。这种死链交给 ExoPlayer 的下场是：起播 → 报错 →
 * 自愈重取/跳过，用户感知十几秒。探活把这类链接在进播放器**之前**拦下来，
 * 走既有的重取/换源/跳过路径，把十几秒压到一次重试的量级。
 *
 * 三档结果，只有「明确坏」才拦截：
 * - [Result.OK]：2xx/3xx 且不是网页内容，或 416（Range 越界但链接本身有效）。
 * - [Result.BAD]：4xx/5xx，或返回的是 text/html 等网页内容——明确死链，立刻处理。
 * - [Result.UNKNOWN]：超时、连接失败等网络异常——**判不了就放行**，宁可疑可播放，
 *   也不能把慢 CDN 误杀成「播不了」。
 *
 * 用 `GET + Range: bytes=0-1` 而不是 HEAD：部分 CDN 对 HEAD 回 403，但 Range GET 正常
 * （柠檬音乐 TV 版 CdnProbe 同款做法，实测验证过）。只读 1~2 字节，带宽开销可忽略。
 */
internal object StreamUrlProbe {

    /** 探活结论。 */
    enum class Result { OK, BAD, UNKNOWN }

    private const val CONNECT_TIMEOUT_MS = 1_200
    private const val READ_TIMEOUT_MS = 1_200
    private const val USER_AGENT = "YueYinPlayer/2.1"

    /**
     * 探测一个直链。阻塞网络调用，必须在 IO 线程执行。
     * 非 http(s) 的 URI（本地文件、content:// 等）不做网络探测，直接放行。
     */
    fun probe(url: String): Result {
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            return Result.OK
        }
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Range", "bytes=0-1")
                setRequestProperty("User-Agent", USER_AGENT)
                useCaches = false
            }
            try {
                val code = conn.responseCode
                val type = conn.contentType?.lowercase().orEmpty()
                when {
                    // 416 = Range 越界，只说明文件比 2 字节还短，链接本身是活的。
                    code == 416 -> Result.OK
                    code in 200..299 -> {
                        if (type.startsWith("text/") || type.contains("json") || type.contains("xml")) {
                            // 拿到的是网页/错误页，不是音频流。
                            Result.BAD
                        } else {
                            // 读 1~2 字节确认真的能出数据（个别 CDN 200 之后才拒绝）。
                            runCatching { conn.inputStream.use { it.read() } }
                            Result.OK
                        }
                    }
                    code in 400..599 -> Result.BAD
                    else -> Result.UNKNOWN
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            // 超时/DNS 失败/连接被重置：判不了就放行，交给播放器与既有的自愈逻辑。
            Result.UNKNOWN
        }
    }
}
