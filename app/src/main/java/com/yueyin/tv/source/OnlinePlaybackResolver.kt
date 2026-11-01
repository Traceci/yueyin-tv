package com.yueyin.tv.source

import android.content.Context
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
        val candidates = all.sortedByDescending { score(it, platformKey) }
        val runtime = OnlineSourceRuntime.get(appContext)
        val reasons = mutableListOf<String>()
        var lastTriedUrl = ""

        for (source in candidates) {
            if (score(source, platformKey) < 0) continue
            val loaded = runCatching { runtime.loadScript(source.fileName, source.script) }.getOrElse { false }
            if (!loaded) {
                reasons += "${source.fileName}: 加载失败"
                continue
            }
            val declared = runCatching { runtime.declaredPlatforms() }.getOrElse { emptySet() }
            val supported = declared.isEmpty() || declared.contains(platformKey) ||
                source.platforms.contains(platformKey) || source.declaredPlatforms.contains(platformKey)
            if (!supported) {
                reasons += "${source.fileName}: 不支持${sourceLabel(platformKey)}"
                continue
            }
            for (quality in qualityOrder) {
                val info = buildInfo(song, quality)
                val value = runCatching { runtime.call(platformKey, "musicUrl", info) }.getOrNull()
                val url = extractUrl(value)
                if (url != null) {
                    // 很多平台在没版权/非会员时也会给一条"看起来正常"的地址，实际 404。
                    // 先自己拉 1KB 验一次，过不了就降档，别把坏链接交给播放器。
                    lastTriedUrl = url
                    val check = verify(url)
                    if (check == null) {
                        return PlaybackResolution.Success(url, qualityLabel(quality), source.fileName)
                    }
                    reasons += "${source.fileName}: $quality $check"
                } else {
                    reasons += "${source.fileName}: ${sourceLabel(platformKey)} $quality 没取到"
                }
            }
        }
        val hint = if (reasons.isEmpty()) "音源里没有能处理${sourceLabel(platformKey)}的脚本" else reasons.takeLast(3).joinToString("；")
        return PlaybackResolution.Failure(hint, lastTriedUrl)
    }

    private fun score(source: MusicSource, platformKey: String): Int = when {
        source.platforms.contains(platformKey) -> 3
        source.declaredPlatforms.contains(platformKey) -> 2
        source.platforms.isEmpty() && source.declaredPlatforms.isEmpty() -> 1
        else -> -1
    }

    private fun buildInfo(song: OnlineSong, quality: String): JsonObject {
        val id = song.id
        val media = song.mediaId.ifBlank { id }
        return buildJsonObject {
            put("type", quality)
            put(
                "musicInfo",
                buildJsonObject {
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
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Range", "bytes=0-1023")
                .build()
            probeClient.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes()?.size ?: 0
                when {
                    response.code >= 400 -> "HTTP ${response.code}"
                    bytes < 256 -> "返回内容为空"
                    else -> null
                }
            }
        }.getOrElse { "连接失败" }
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
        val candidates = all.sortedByDescending { score(it, platformKey) }
        val runtime = OnlineSourceRuntime.get(appContext)
        for (source in candidates) {
            if (score(source, platformKey) < 0) continue
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
