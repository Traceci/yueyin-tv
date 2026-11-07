package com.yueyin.tv.source

import android.content.Context
import com.yueyin.tv.core.model.stream.PlaybackHttp
import com.yueyin.tv.core.data.online.OnlineSong
import com.yueyin.tv.core.data.source.MusicSource
import com.yueyin.tv.ui.source.MusicSourceRuntime
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 在线歌曲取链结果。 */
sealed interface PlaybackResolution {
    data class Success(val url: String, val qualityLabel: String, val sourceName: String) : PlaybackResolution
    data class Failure(val reason: String, val lastUrl: String = "") : PlaybackResolution
}

/**
 * 把一条 [OnlineSong] 交给用户导入的音源脚本，换出可直接播放的地址。
 *
 * 有多个脚本时按「声明了该平台 → 检测通过 → 其余」的顺序逐个尝试，
 * 一个脚本失败就换下一个（失败自动换源）。
 */
class OnlinePlaybackResolver(context: Context) {

    private val appContext = context.applicationContext

    private val qualityOrder = listOf("320k", "128k")

    private companion object {
        /** 单曲取链总预算：正常 0.2~2 秒，超过这个数就是坏源在拖时间。 */
        const val RESOLVE_BUDGET_MS = 9_000L

        /**
         * 单个脚本的时间上限。
         *
         * 原来预算只在「换脚本」时检查，而一个脚本内部要试两档音质、每档 12 秒超时，
         * 实测一份对咪咕无响应的脚本能拖 24 秒才失败——用户点歌后就是长时间没反应。
         * 现在预算落到每次调用上：单个脚本最多 5 秒，坏源让位给下一个。
         */
        const val PER_SCRIPT_BUDGET_MS = 5_000L

        /** 同一平台连续失败几次就先熔断。 */
        const val FAILS_BEFORE_COOLDOWN = 3

        /** 熔断时长。 */
        const val COOLDOWN_MS = 3 * 60_000L
    }

    /** 音源 id → 最近连续取链失败次数。用来把坏脚本排到后面，见 [score]。 */
    private val penalty = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * 平台健康度。
     *
     * 实测某份聚合音源时踩到的：咪咕那条链路整体挂了，每首歌取链都要把脚本里几组 API 全试一遍，
     * 一档音质 12 秒、两档 24 秒。而悦音的曲库是跨平台混排的，随心听随机 50 首里混着咪咕的歌，
     * 于是「点一下要等十几秒」。这里给平台做短期熔断：连续失败几次就先跳过几分钟，
     * 别让用户为一个已经死掉的平台反复付等待成本。
     */
    private class PlatformHealth {
        var fails: Int = 0
        var cooldownUntil: Long = 0L
    }

    private val health = java.util.concurrent.ConcurrentHashMap<String, PlatformHealth>()

    // 校验用：只取前 1KB，确认这条直链真的能出音频字节
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun resolve(song: OnlineSong): PlaybackResolution {
        val platformKey = song.platform.key
        val all = MusicSourceRuntime.store.state.value.sources.filter { it.enabled }
        if (all.isEmpty()) {
            return PlaybackResolution.Failure("还没有导入音源脚本，先去「设置 → 曲库与音源」添加")
        }
        val now = System.currentTimeMillis()
        health[platformKey]?.let { h ->
            if (h.cooldownUntil > now) {
                val left = ((h.cooldownUntil - now) / 1000).coerceAtLeast(1)
                return PlaybackResolution.Failure("${sourceLabel(platformKey)} 刚连续取链失败，先跳过（${left} 秒后自动重试）")
            }
        }
        val candidates = rankedFor(all, song, platformKey)
        val runtime = OnlineSourceRuntime.get(appContext)
        val reasons = mutableListOf<String>()
        var lastTriedUrl = ""
        // 单曲总预算：一首歌最多花这么久。再久就不值得——后面还有几十首在排队，
        // 而音源运行时只有一个隐藏 WebView，取链是串行的。
        val deadline = now + RESOLVE_BUDGET_MS

        for (source in candidates) {
            if (System.currentTimeMillis() > deadline) {
                reasons += "取链超时"
                break
            }
            if (score(source, platformKey) < 0 && source.fileName != song.sourceName) continue
            val loaded = runCatching { runtime.loadScript(source.fileName, source.script) }.getOrElse { false }
            if (!loaded) {
                reasons += "${source.fileName}: 加载失败"
                markMiss(source)
                continue
            }
            val declared = runCatching { runtime.declaredPlatforms() }.getOrElse { emptySet() }
            val supported = declared.isEmpty() || declared.contains(platformKey) ||
                source.platforms.contains(platformKey) || source.declaredPlatforms.contains(platformKey)
            if (!supported) {
                reasons += "${source.fileName}: 不支持${sourceLabel(platformKey)}"
                continue
            }
            // 每个脚本单独限时：坏源最多占 5 秒，把剩下的时间留给下一个脚本。
            val scriptDeadline = minOf(deadline, System.currentTimeMillis() + PER_SCRIPT_BUDGET_MS)
            for (quality in qualityOrder) {
                val remaining = scriptDeadline - System.currentTimeMillis()
                if (remaining <= 0L) {
                    reasons += "${source.fileName}: 取链超时"
                    break
                }
                val info = buildInfo(song, quality)
                val value = runCatching {
                    runtime.call(platformKey, "musicUrl", info, timeoutMs = minOf(12_000L, remaining))
                }.getOrNull()
                val url = extractUrl(value)
                if (url != null) {
                    // 很多平台在没版权/非会员时也会给一条"看起来正常"的地址，实际 404。
                    // 先自己拉 1KB 验一次，过不了就降档，别把坏链接交给播放器。
                    lastTriedUrl = url
                    val check = verify(url)
                    if (check == null) {
                        markHit(source)
                        return PlaybackResolution.Success(url, qualityLabel(quality), source.fileName).also {
                            health[platformKey]?.fails = 0
                        }
                    }
                    reasons += "${source.fileName}: $quality $check"
                    // 直链探活失败（404 / 返回的是 JSON 错误 / 内容为空）说明这个脚本这条链路本身坏了，
                    // 降一档音质也是同一个坏代理，没必要再等一轮。直接换下一个脚本。
                    markMiss(source)
                    break
                } else {
                    reasons += "${source.fileName}: ${sourceLabel(platformKey)} $quality 没取到"
                }
            }
            markMiss(source)
        }
        val hint = if (reasons.isEmpty()) "音源里没有能处理${sourceLabel(platformKey)}的脚本" else reasons.takeLast(3).joinToString("；")
        notePlatformFailure(platformKey)
        return PlaybackResolution.Failure(hint, lastTriedUrl)
    }

    /** 记一次平台级失败；连续几次就熔断一段时间。 */
    private fun notePlatformFailure(platformKey: String) {
        val state = health.getOrPut(platformKey) { PlatformHealth() }
        synchronized(state) {
            state.fails += 1
            if (state.fails >= FAILS_BEFORE_COOLDOWN) {
                state.fails = 0
                state.cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
                LxLog.add("${sourceLabel(platformKey)} 连续取链失败，暂停 ${COOLDOWN_MS / 1000} 秒")
            }
        }
    }

    private fun score(source: MusicSource, platformKey: String): Int {
        val base = when {
            source.platforms.contains(platformKey) -> 3
            source.declaredPlatforms.contains(platformKey) -> 2
            source.platforms.isEmpty() && source.declaredPlatforms.isEmpty() -> 1
            else -> -1
        }
        if (base < 0) return base
        // 导入的脚本可能有七八个，其中一半是早就失效的（拿到的链接 404）。
        // 如果永远按导入顺序试，每次取链都要先在坏脚本上白等一轮。
        // 这里记一下最近的失败次数，把失败过的往后排；成功一次就清零。
        val misses = penalty[source.id] ?: 0
        return base * 10 - misses.coerceAtMost(9)
    }

    private fun markMiss(source: MusicSource) {
        penalty[source.id] = ((penalty[source.id] ?: 0) + 1).coerceAtMost(9)
    }

    private fun markHit(source: MusicSource) {
        penalty.remove(source.id)
    }

    /**
     * 候选脚本排序：**产出这条结果的脚本**永远排第一。
     *
     * 脚本聚合搜索出来的歌，id / hash 是那份脚本自己那套编码，换别的脚本很可能对不上
     * （同一首歌在两家脚本里的 id 形如 `228908` 与 `TxSong_xxx`），会白跑一轮再失败。
     * 官方接口来的歌 sourceName 为空，这个条件对它们恒不成立，排序结果与以前完全一致。
     */
    private fun rankedFor(
        sources: List<MusicSource>,
        song: OnlineSong,
        platformKey: String,
    ): List<MusicSource> = sources.sortedWith(
        compareByDescending<MusicSource> { song.sourceName.isNotBlank() && it.fileName == song.sourceName }
            .thenByDescending { score(it, platformKey) },
    )

    private fun buildInfo(song: OnlineSong, quality: String): JsonObject {
        val id = song.id
        val media = song.mediaId.ifBlank { id }
        return buildJsonObject {
            put("type", quality)
            put(
                "musicInfo",
                buildJsonObject {
                    // 音源脚本里大量 API 靠 musicInfo.source 判断平台（`const src = info.source`
                    // → 不认识就抛 unsupported src）。少了这一个字段，脚本里一多半的备用 API 直接不工作：
                    // 实测同一首 QQ 歌，不带 source 时五组 API 全灭、耗时 9.5 秒；带上后 0.9 秒拿到直链。
                    put("source", song.platform.key)
                    put("songName", song.name)
                    put("songmid", id)
                    put("songId", id)
                    put("id", id)
                    put("rid", id)
                    put("hash", media)
                    put("strMediaMid", media)
                    put("copyrightId", id)
                    put("albumId", song.albumId)
                    put("albumMid", song.albumId)
                    put("name", song.name)
                    put("singer", song.artist)
                    put("albumName", song.album)
                    put("interval", song.durationSeconds.toString())
                    put("duration", song.durationSeconds)
                },
            )
        }
    }

    /** 校验直链；返回 null 表示可用，否则返回一句失败原因。 */
    private suspend fun verify(url: String): String? = withContext(Dispatchers.IO) {
        val reason = runCatching {
            val request = Request.Builder()
                .url(url)
                // 必须与播放器的 DefaultHttpDataSource 用同一个 UA，否则会出现
                // 「探活拿得到 1KB、ExoPlayer 却被 CDN 拒掉」的假成功。
                .header("User-Agent", PlaybackHttp.USER_AGENT)
                .header("Range", "bytes=0-1023")
                .build()
            probeClient.newCall(request).execute().use { response ->
                val bytes = response.body.bytes().size
                val contentType = response.header("Content-Type").orEmpty()
                when {
                    response.code >= 400 -> "HTTP ${response.code}"
                    // 音源代理接口常返回 HTML 错误页或 JSON 报错，长度轻松超过 256 字节。
                    // 只验状态码与长度会把它们当成可用音频，最终交给播放器报 IO 错误。
                    contentType.contains("text/html", true) ||
                        contentType.contains("application/json", true) ||
                        contentType.contains("text/plain", true) ->
                        "返回的不是音频（${contentType.take(32)}）"
                    bytes < 256 -> "返回内容为空"
                    else -> null
                }
            }
        }.getOrElse { cause -> "连接失败：${cause.javaClass.simpleName}" }
        if (reason != null) {
            val host = runCatching { java.net.URI(url).host ?: "?" }.getOrDefault("?")
            LxLog.add("直链探活失败 host=$host → $reason")
        }
        reason
    }

    private fun extractUrl(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val direct = value.trim()
        if (direct.startsWith("http://") || direct.startsWith("https://")) return direct
        val match = Regex("""https?://[^\s"'\\]+""").find(value)
        return match?.value?.trimEnd(',', '}', ']')
    }

    private fun qualityLabel(quality: String): String = when (quality) {
        "320k" -> "320K"
        "128k" -> "128K"
        "flac" -> "无损"
        "flac24bit" -> "Hi-Res"
        else -> quality
    }

    private fun sourceLabel(key: String): String = MusicSource.sourceLabel(key)

    /**
     * 取歌词：复用音源脚本的 `lyric` 事件，返回 LRC 原文；取不到返回 null。
     * 和取链共用同一套"选脚本 → 加载 → 声明校验"流程，避免两份逻辑漂移。
     */
    suspend fun resolveLyric(song: OnlineSong): String? {
        val platformKey = song.platform.key
        val all = MusicSourceRuntime.store.state.value.sources.filter { it.enabled }
        if (all.isEmpty()) return null
        val candidates = rankedFor(all, song, platformKey)
        val runtime = OnlineSourceRuntime.get(appContext)
        for (source in candidates) {
            if (score(source, platformKey) < 0 && source.fileName != song.sourceName) continue
            if (!runCatching { runtime.loadScript(source.fileName, source.script) }.getOrElse { false }) continue
            val declared = runCatching { runtime.declaredPlatforms() }.getOrElse { emptySet() }
            val supported = declared.isEmpty() || declared.contains(platformKey) ||
                source.platforms.contains(platformKey) || source.declaredPlatforms.contains(platformKey)
            if (!supported) continue
            val info = buildJsonObject {
                put("type", "lyric")
                put(
                    "musicInfo",
                    buildJsonObject {
                        put("source", platformKey)
                        put("songName", song.name)
                        put("songmid", song.id)
                        put("songId", song.id)
                        put("id", song.id)
                        put("hash", song.mediaId.ifBlank { song.id })
                        put("albumId", song.albumId)
                        put("name", song.name)
                        put("singer", song.artist)
                        put("albumName", song.album)
                    },
                )
            }
            val value = runCatching { runtime.call(platformKey, "lyric", info) }.getOrNull()
            val lyric = value?.takeIf(String::isNotBlank)
            if (!lyric.isNullOrBlank()) return lyric
        }
        return null
    }
}
