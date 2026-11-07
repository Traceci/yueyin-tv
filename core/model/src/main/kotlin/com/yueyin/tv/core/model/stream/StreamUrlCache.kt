package com.yueyin.tv.core.model.stream

import java.util.concurrent.ConcurrentHashMap

/**
 * 在线音源直链的进程内缓存。
 *
 * 音源脚本返回的直链普遍带签名与 expire（几秒到几分钟），而 App 会一次预取
 * 十几二十首慢慢播。旧实现是一张永不过期的 Map：播到队尾时整批链接早已失效，
 * 于是连续报「播放失败：网络连接不上音源」，换音源、点重试都命中同一条死链，
 * 只有退出 App（内存缓存清空）才恢复。这里统一收口：带 TTL、可精确失效、可整体清空。
 *
 * 后来发现只靠固定 TTL 还不够：脚本链的真实有效期常常比 TTL 更短，
 * 「缓存还没过期、链接已经失效」照样会把死链交给播放器。所以再叠一层**按 URL 自带的
 * expire 参数判断**——能解析到就以它为准，解析不到才退回 [ttlMs]。
 */
object StreamUrlCache {
    private data class Entry(
        val url: String,
        val fetchedAt: Long,
        /** URL 里自带的过期时刻（毫秒）；没有则为 null。 */
        val expiresAt: Long?,
    )

    /**
     * 没有 expire 参数时的兜底有效期。
     *
     * 从 5 分钟收紧到 2 分钟：多取一次链大约 0.3 秒，而交给播放器一条过期地址
     * 换来的是「播放失败 + 自动重取」，代价明显更高。
     */
    @Volatile
    var ttlMs: Long = 2 * 60_000L

    /** 提前量：留一点余量给「取到链 → 播放器真正发出请求」这段耗时。 */
    private const val EXPIRY_SAFETY_MS = 5_000L

    /**
     * 取缓存。[maxAgeMs] 用于更严格的场景（例如交给播放器前只认最近 90 秒取的），
     * 默认按 [ttlMs] 判断。
     */
    fun get(key: String, maxAgeMs: Long = ttlMs): String? {
        val entry = entries[key] ?: return null
        val now = System.currentTimeMillis()
        val expiredByUrl = entry.expiresAt?.let { now >= it - EXPIRY_SAFETY_MS } ?: false
        if (expiredByUrl || now - entry.fetchedAt > maxAgeMs) {
            entries.remove(key)
            return null
        }
        return entry.url
    }

    fun put(key: String, url: String) {
        if (key.isBlank() || url.isBlank()) return
        entries[key] = Entry(url, System.currentTimeMillis(), expiryOf(url))
    }

    fun invalidate(key: String) {
        entries.remove(key)
    }

    fun clear() {
        entries.clear()
    }

    /**
     * 从直链里解析过期时刻。
     *
     * 不同音源写法不一（`expire` / `expires` / `deadline`，秒或毫秒），但都是明确的
     * 时间点而不是签名，误判风险低。解析不出来就交给 [ttlMs] 兜底。
     */
    private fun expiryOf(url: String): Long? {
        val match = EXPIRY_PATTERN.find(url) ?: return null
        val raw = match.groupValues[1].toLongOrNull() ?: return null
        val millis = if (raw < 100_000_000_000L) raw * 1_000L else raw
        // 合理性校验：只认「一年内」的时间点，避免把恰好是长数字的签名参数当成过期时间。
        val now = System.currentTimeMillis()
        if (millis < now - 365L * 24 * 3600 * 1_000L || millis > now + 365L * 24 * 3600 * 1_000L) return null
        return millis
    }

    private val EXPIRY_PATTERN = Regex(
        """[?&](?:expire|expires|expired_at|deadline|expireTime)=(\d{10,13})""",
        RegexOption.IGNORE_CASE,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
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
