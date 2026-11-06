package com.yueyin.tv.source

import android.content.Context
import android.os.SystemClock
import com.yueyin.tv.core.data.online.OnlinePlatform
import com.yueyin.tv.core.data.online.OnlineSong
import com.yueyin.tv.core.data.source.MusicSource
import com.yueyin.tv.ui.source.MusicSourceRuntime
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * 用用户导入的 LX 音源脚本做聚合搜索。
 *
 * 在线搜索原本只打酷我/网易/QQ/酷狗/咪咕 5 家官方接口；这里把用户导入的脚本也当成数据源：
 * 对 5 个平台逐一调用脚本注册的 `search` 事件，把脚本返回的歌并回结果。
 * 这样"搜索"就和"取链/歌词"一样，覆盖到官方接口没有的自定义音源。
 *
 * 关键约束：音源运行时是**单个隐藏 WebView**，取链/搜索严格串行。所以这里用一把全局 Mutex
 * 把 `loadScript` + `call` 串行化，避免在同一块 WebView 上互相踩踏、拿到张冠李戴的结果甚至崩脚本。
 * 5 个平台只能排队跑，所以整轮有一个总预算（[TOTAL_BUDGET_MS]），按剩余平台平分时间片，
 * 预算用完就带着已拿到的部分结果返回，绝不让搜索页一直转圈。
 *
 * 与 [com.yueyin.tv.source.OnlinePlaybackResolver] 共用同一套"选脚本 → 加载 → 声明校验"逻辑，
 * 但刻意不复用它的实现，避免为了让两者共享而改动取链这条已经稳定的链路。
 */
class OnlineScriptSearch(context: Context) {

    private companion object {
        /** 脚本聚合搜索的总预算：搜索是即时交互，超过 3 秒的等待不如少两个平台的结果。 */
        const val TOTAL_BUDGET_MS = 3_000L

        /** 单个平台最少分到的时间：再少连脚本的第一次网络请求都跑不完。 */
        const val MIN_SLICE_MS = 900L
    }

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 串行化所有 WebView 操作（单 WebView，绝不能并发 loadScript/call）。 */
    private val runtimeMutex = Mutex()

    /** 搜索失败过的脚本往后排，避免每次都在坏脚本上白等。 */
    private val penalty = ConcurrentHashMap<String, Int>()

    /** 和官方搜索一致的查询缓存：输入即搜会连打十几次请求，命中缓存能让列表不闪。 */
    private val cache = object : LinkedHashMap<String, List<OnlineSong>>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<OnlineSong>>) = size > 12
    }

    suspend fun search(keyword: String): List<OnlineSong> {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return emptyList()
        val cacheKey = trimmed.lowercase()
        synchronized(cache) { cache[cacheKey] }?.let { return it }

        val sources = MusicSourceRuntime.store.state.value.sources.filter { it.enabled }
        if (sources.isEmpty()) return emptyList()
        // 只问"至少有一个脚本声明支持"的平台：没脚本支持的平台白跑一趟只是浪费预算。
        val platforms = OnlinePlatform.entries.filter { platform -> sources.any { supports(it, platform.key) } }
        if (platforms.isEmpty()) return emptyList()

        // 单 WebView 串行，5 个平台只能排队。给整轮一个总预算，按剩余平台数平分，
        // 用完就带着已经拿到的结果返回——宁可少两个平台，也不要让搜索页转圈等到超时。
        val deadline = SystemClock.elapsedRealtime() + TOTAL_BUDGET_MS
        val merged = LinkedHashMap<String, OnlineSong>()
        platforms.forEachIndexed { index, platform ->
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining < MIN_SLICE_MS) return@forEachIndexed
            val left = (platforms.size - index).coerceAtLeast(1)
            val slice = (remaining / left).coerceAtLeast(MIN_SLICE_MS)
            for (song in searchPlatform(platform, sources, trimmed, slice)) {
                merged.putIfAbsent(song.key, song)
            }
        }
        val result = merged.values.toList()
        if (result.isNotEmpty()) synchronized(cache) { cache[cacheKey] = result }
        return result
    }

    private suspend fun searchPlatform(
        platform: OnlinePlatform,
        sources: List<MusicSource>,
        query: String,
        sliceMs: Long,
    ): List<OnlineSong> {
        val candidates = sources.filter { supports(it, platform.key) }
            .sortedByDescending { score(it, platform.key) }
        if (candidates.isEmpty()) return emptyList()
        val runtime = OnlineSourceRuntime.get(appContext)
        for (source in candidates) {
            var timedOut = false
            val songs = runCatching {
                withTimeout(sliceMs) {
                    runtimeMutex.withLock {
                        // 切到这个脚本（单 WebView，会替换上一个脚本的上下文）。
                        // 不等它发 inited：搜索有时间片，等 2.5 秒直接就超时了。
                        if (!runtime.loadScript(source.fileName, source.script, awaitInit = false)) {
                            return@withLock null
                        }
                        val declared = runCatching { runtime.declaredPlatforms() }.getOrElse { emptySet() }
                        // 脚本明确声明了支持的平台，但不含当前平台 → 跳过；
                        // 声明为空（万能兜底脚本）或命中检测平台 → 继续尝试。
                        if (declared.isNotEmpty() &&
                            !declared.contains(platform.key) &&
                            !source.platforms.contains(platform.key)
                        ) {
                            return@withLock null
                        }
                        val info = buildInfo(query)
                        val value = runtime.call(platform.key, "search", info) ?: return@withLock null
                        parseResult(value, platform, source.fileName)
                    }
                }
            }.onFailure { timedOut = it is TimeoutCancellationException }.getOrNull()

            when {
                // 这个平台的时间片用完：脚本本身可能没问题，只是这一轮预算不够，
                // 不能记成"坏源"，否则下一轮会被排到后面。
                timedOut -> return emptyList()
                songs.isNullOrEmpty() -> markMiss(source)
                else -> {
                    penalty.remove(source.id)
                    // 只保留这一家脚本的结果，避免同平台多份脚本给出重复歌。
                    return songs.distinctBy { it.key }
                }
            }
        }
        return emptyList()
    }

    // ---------------------------------------------------------------- 脚本选择

    private fun supports(source: MusicSource, platformKey: String): Boolean =
        source.declaredPlatforms.contains(platformKey) ||
            source.platforms.contains(platformKey) ||
            (source.platforms.isEmpty() && source.declaredPlatforms.isEmpty())

    private fun score(source: MusicSource, platformKey: String): Int {
        val base = when {
            source.platforms.contains(platformKey) -> 3
            source.declaredPlatforms.contains(platformKey) -> 2
            source.platforms.isEmpty() && source.declaredPlatforms.isEmpty() -> 1
            else -> -1
        }
        if (base < 0) return base
        val misses = penalty[source.id] ?: 0
        return base * 10 - misses.coerceAtMost(9)
    }

    private fun markMiss(source: MusicSource) {
        penalty[source.id] = ((penalty[source.id] ?: 0) + 1).coerceAtMost(9)
    }

    // ---------------------------------------------------------------- 结果解析

    /**
     * 搜索入参。
     *
     * 各家音源读的字段不一样：有的在根上读 `keyword`，有的读 `info.musicInfo.keyword`
     * （LX 桌面端把搜索词放在 musicInfo 里，很多脚本是从那边照搬的）。
     * 两套都写上，脚本按自己习惯取，取不到才不会整条链路报 unsupported。
     */
    private fun buildInfo(query: String): JsonObject = buildJsonObject {
        put("type", "search")
        put("searchQuery", query)
        put("keyword", query)
        put("page", 1)
        put("limit", 30)
        put(
            "musicInfo",
            buildJsonObject {
                put("keyword", query)
                put("songName", query)
                put("page", 1)
                put("limit", 30)
            },
        )
    }

    private fun parseResult(value: String, platform: OnlinePlatform, sourceName: String): List<OnlineSong> {
        val raw = value.trim()
        if (raw.isEmpty() || raw == "null") return emptyList()
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
        val arr: JsonArray = when {
            element is JsonArray -> element
            element is JsonObject -> {
                val list = element["list"] ?: element["data"] ?: element["songs"] ?: element["result"]
                (list as? JsonArray) ?: return emptyList()
            }
            else -> return emptyList()
        }
        val out = mutableListOf<OnlineSong>()
        for (item in arr) {
            if (item !is JsonObject) continue
            runCatching { songFromJson(item, platform, sourceName) }.getOrNull()?.let { out += it }
        }
        return out
    }

    private fun songFromJson(o: JsonObject, platform: OnlinePlatform, sourceName: String): OnlineSong {
        fun str(vararg keys: String): String {
            for (k in keys) {
                val el = o[k] ?: continue
                val prim = el as? JsonPrimitive ?: continue
                val v = prim.contentOrNull
                if (!v.isNullOrBlank()) return v
            }
            return ""
        }
        val name = str("songname", "name", "title", "songName")
        val artist = str("singer", "artist", "artistName", "artists", "author").replace("、", " / ")
        val album = str("albumName", "album", "albumname")
        val albumId = str("albumId", "albumid", "albumMid", "album_id")
        val cover = str("img", "pic", "image", "cover", "albumPic", "coverUrl", "coverImg")
        val rawId = str("songmid", "songId", "id", "songid", "mid", "musicId")
        val id = rawId.ifBlank { "${name}_${artist}_${platform.key}".hashCode().toString() }
        val duration = parseDuration(str("interval", "duration", "time", "dt", "songTimeMinutes"))
        val qualities = parseQualities(str("quality", "qualities", "prefix", "size"))
        val mediaId = str("hash", "strMediaMid", "mediaId", "media_id")
        return OnlineSong(
            platform = platform,
            id = id,
            name = name,
            artist = artist,
            album = album,
            albumId = albumId,
            durationSeconds = duration,
            coverUrl = cover,
            qualities = qualities,
            mediaId = mediaId,
            sourceName = sourceName,
        )
    }

    private fun parseDuration(raw: String): Int {
        val s = raw.trim()
        if (s.isEmpty()) return 0
        if (s.contains(":")) {
            val parts = s.split(":")
            val min = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val sec = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val extra = parts.getOrNull(2)?.toIntOrNull() ?: 0
            return min * 60 + sec + extra * 3600
        }
        return s.toIntOrNull() ?: 0
    }

    private fun parseQualities(vararg raws: String): List<String> {
        val out = mutableSetOf<String>()
        for (r in raws) {
            val v = r.trim()
            if (v.isEmpty()) continue
            when {
                v.contains("flac", true) || v.contains("无损", true) ||
                    v.contains("sq", true) || v.contains("hires", true) -> out += "flac"
                v.contains("320", true) -> out += "320k"
                v.contains("128", true) -> out += "128k"
                else -> out += v
            }
        }
        return out.toList()
    }
}
