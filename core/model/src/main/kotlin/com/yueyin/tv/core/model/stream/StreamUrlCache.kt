package com.yueyin.tv.core.model.stream

import java.util.concurrent.ConcurrentHashMap

/**
 * 在线音源直链的进程内缓存。
 *
 * 音源脚本返回的直链普遍带签名与 expire（几秒到几分钟），而 App 会一次预取
 * 十几二十首慢慢播。旧实现是一张永不过期的 Map：播到队尾时整批链接早已失效，
 * 于是连续报「播放失败：网络连接不上音源」，换音源、点重试都命中同一条死链，
 * 只有退出 App（内存缓存清空）才恢复。这里统一收口：带 TTL、可精确失效、可整体清空。
 */
object StreamUrlCache {
    private data class Entry(val url: String, val fetchedAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** 与音源脚本自己的 URL_CACHE expire 对齐，默认 5 分钟。 */
    @Volatile
    var ttlMs: Long = 5 * 60_000L

    fun get(key: String): String? {
        val entry = entries[key] ?: return null
        if (System.currentTimeMillis() - entry.fetchedAt > ttlMs) {
            entries.remove(key)
            return null
        }
        return entry.url
    }

    fun put(key: String, url: String) {
        if (key.isBlank() || url.isBlank()) return
        entries[key] = Entry(url, System.currentTimeMillis())
    }

    fun invalidate(key: String) {
        entries.remove(key)
    }

    fun clear() {
        entries.clear()
    }
}

/**
 * 取链探活与播放器必须共用同一套 HTTP 身份。
 *
 * 以前探活用浏览器 UA，ExoPlayer 用 `YueYinTV/1.0` 且不带 Referer；部分音源
 * （例如要求 `Referer: https://y.qq.com/` 的 QQ 链路）就会「探活通过、播放失败」。
 */
object PlaybackHttp {
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}
