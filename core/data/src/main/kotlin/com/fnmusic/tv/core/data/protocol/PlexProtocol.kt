package com.fnmusic.tv.core.data.protocol

import com.fnmusic.tv.core.data.connect.ConnectProfile
import com.fnmusic.tv.core.model.connect.ConnectType
import com.fnmusic.tv.core.model.connect.ServerCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Plex 协议。
 *
 * - 鉴权用 `X-Plex-Token`（可以放在请求头，也可以放在 URL 的 `?X-Plex-Token=`，
 *   后者正好让 Media3 能直接播放 / 让 Coil 能直接取封面，不需要改播放层加请求头）；
 * - 曲目列表里的 `Media[].Part[].key` 才是真正的文件地址，必须额外记一张
 *   `ratingKey → partKey` 表，否则拿不到可播放的 URL；
 * - 全部接口走 `Accept: application/json`（Plex 官方支持），省掉解析 XML。
 *
 * 表单约定：`host` = `http://ip:32400`，`密码` 位置填 **Plex Token**（不是账号密码）。
 */
class PlexProtocol(
    override val type: ConnectType = ConnectType.PLEX,
    private val client: OkHttpClient = defaultClient(),
) : MusicProtocol {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** ratingKey → Part.key（`/library/parts/123/456/file.flac`）。 */
    private val partKeys = ConcurrentHashMap<String, String>()

    // ---------------------------------------------------------------- 登录

    override suspend fun ping(profile: ConnectProfile): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val container = get(profile, "/identity")
            container.machineIdentifier != null || container.size != null
        }.getOrDefault(false)
    }

    override suspend fun login(profile: ConnectProfile): ServerCapabilities {
        val token = tokenOf(profile)
        if (token.isBlank()) return unsupportedCaps()
        val ok = withContext(Dispatchers.IO) {
            runCatching { musicSectionKey(profile) != null }.getOrDefault(false)
        }
        return if (ok) ServerCapabilities(
            lyrics = false,
            favorites = false,
            playlists = true,
            musicVideos = false,
            radio = false,
            search = true,
        ) else unsupportedCaps()
    }

    /** 把表单里填在密码位的 Token 落到 `token` 字段，保证之后取流地址带得上。 */
    override suspend fun enrich(profile: ConnectProfile): ConnectProfile =
        if (profile.token.isBlank()) profile.copy(token = profile.password) else profile

    // ---------------------------------------------------------------- 列表

    override suspend fun artists(profile: ConnectProfile): List<RemoteArtist> = withContext(Dispatchers.IO) {
        runCatching {
            val section = musicSectionKey(profile) ?: return@runCatching emptyList()
            items(profile, "/library/sections/$section/all?type=10").map { meta ->
                RemoteArtist(id = meta.ratingKey, name = meta.title, coverId = meta.thumb)
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun albums(profile: ConnectProfile): List<RemoteAlbum> = withContext(Dispatchers.IO) {
        runCatching {
            val section = musicSectionKey(profile) ?: return@runCatching emptyList()
            items(profile, "/library/sections/$section/all?type=8").map { it.toAlbum() }
        }.getOrDefault(emptyList())
    }

    override suspend fun songs(profile: ConnectProfile): List<RemoteSong> = withContext(Dispatchers.IO) {
        runCatching {
            val section = musicSectionKey(profile) ?: return@runCatching emptyList()
            items(profile, "/library/sections/$section/all?type=9").map { it.toSong() }
        }.getOrDefault(emptyList())
    }

    override suspend fun playlists(profile: ConnectProfile): List<RemotePlaylist> = withContext(Dispatchers.IO) {
        runCatching {
            items(profile, "/playlists?playlistType=audio").map { meta ->
                RemotePlaylist(
                    id = meta.ratingKey,
                    name = meta.title,
                    songCount = meta.leafCount ?: 0,
                    coverId = meta.composite ?: meta.thumb,
                )
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun playlistSongs(profile: ConnectProfile, playlistId: String): List<RemoteSong> =
        withContext(Dispatchers.IO) {
            runCatching {
                items(profile, "/playlists/$playlistId/items").map { it.toSong() }
            }.getOrDefault(emptyList())
        }

    override suspend fun albumSongs(profile: ConnectProfile, albumId: String): List<RemoteSong> =
        withContext(Dispatchers.IO) {
            runCatching {
                items(profile, "/library/metadata/$albumId/children").map { it.toSong() }
            }.getOrDefault(emptyList())
        }

    override suspend fun artistSongs(profile: ConnectProfile, artistId: String): List<RemoteSong> =
        withContext(Dispatchers.IO) {
            runCatching {
                items(profile, "/library/metadata/$artistId/allLeaves").map { it.toSong() }
            }.getOrDefault(emptyList())
        }

    override suspend fun artistAlbums(profile: ConnectProfile, artistId: String): List<RemoteAlbum> =
        withContext(Dispatchers.IO) {
            runCatching {
                items(profile, "/library/metadata/$artistId/children").map { it.toAlbum() }
            }.getOrDefault(emptyList())
        }

    /**
     * Plex 的 `/hubs/search` 会把各个类型混在一个 `Hub` 数组里，
     * 这里只挑 `type == "track"` 的分组，避免把专辑/歌手当成歌塞进播放队列。
     */
    override suspend fun search(profile: ConnectProfile, query: String): List<RemoteSong> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            runCatching {
                val container = get(profile, "/hubs/search?query=${encode(query)}&limit=50")
                container.hub.orEmpty()
                    .filter { it.type == "track" }
                    .flatMap { it.metadata.orEmpty() }
                    .map { it.toSong() }
            }.getOrDefault(emptyList())
        }

    // ---------------------------------------------------------------- 播放 / 封面

    override fun streamUrl(profile: ConnectProfile, songId: String): String {
        val base = baseOf(profile)
        val token = tokenOf(profile)
        val partKey = partKeys[songId]
        return if (!partKey.isNullOrBlank()) {
            "$base$partKey?X-Plex-Token=$token"
        } else {
            // 兜底：列表没进过内存（比如冷启动直接续播）时让 Plex 自己按 metadata 找文件
            "$base/library/metadata/$songId/file?X-Plex-Token=$token"
        }
    }

    override fun coverUrl(profile: ConnectProfile, coverId: String, size: Int): String {
        val base = baseOf(profile)
        val token = tokenOf(profile)
        val path = when {
            coverId.isBlank() -> return ""
            coverId.startsWith("http") -> return coverId
            coverId.startsWith("/") -> coverId
            else -> "/library/metadata/$coverId/thumb"
        }
        return "$base/photo/:/transcode?width=$size&height=$size&minSize=1&upscale=1" +
            "&url=${encode(path)}&X-Plex-Token=$token"
    }

    /** Plex 没有公开的歌词接口，交给上层显示「暂无歌词」。 */
    override suspend fun lyrics(
        profile: ConnectProfile,
        songId: String,
        artist: String,
        title: String,
    ): String? = null

    override suspend fun setFavorite(profile: ConnectProfile, songId: String, favorite: Boolean): Boolean = false

    // ---------------------------------------------------------------- 内部

    private suspend fun musicSectionKey(profile: ConnectProfile): String? {
        val container = get(profile, "/library/sections")
        return container.directory.orEmpty()
            .firstOrNull { it.type == "artist" }
            ?.key
    }

    private suspend fun items(profile: ConnectProfile, path: String): List<PlexMeta> {
        val container = get(profile, path)
        // 单个元素时 Plex 只返回对象不返回数组，serializer 已经按数组处理，这里直接取
        container.metadata.orEmpty().forEach { meta ->
            meta.partKey()?.let { key -> if (meta.ratingKey.isNotBlank()) partKeys[meta.ratingKey] = key }
        }
        return container.metadata.orEmpty()
    }

    private suspend fun get(profile: ConnectProfile, path: String): PlexContainer {
        val builder = Request.Builder()
            .url(baseOf(profile) + path)
            .header("Accept", "application/json")
            .header("X-Plex-Token", tokenOf(profile))
            .header("X-Plex-Product", "YueYin TV")
            .header("X-Plex-Client-Identifier", "yueyin-tv")
            .get()
        client.cancellableCall(builder.build()).use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return json.decodeFromString(PlexEnvelope.serializer(), text).mediaContainer
                ?: error("missing MediaContainer")
        }
    }

    private fun baseOf(profile: ConnectProfile): String {
        val raw = profile.host.trim().trimEnd('/')
        return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
    }

    private fun tokenOf(profile: ConnectProfile): String =
        profile.token.ifBlank { profile.password }.trim()

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun unsupportedCaps() = ServerCapabilities(
        lyrics = false, favorites = false, playlists = false, musicVideos = false, radio = false, search = false,
    )

    private fun PlexMeta.toSong() = RemoteSong(
        id = ratingKey,
        title = title.ifBlank { "未知曲目" },
        artistName = grandparentTitle ?: "",
        albumName = parentTitle ?: "",
        albumId = parentRatingKey,
        durationMs = duration ?: 0L,
        coverId = thumb ?: parentThumb,
        trackNumber = index ?: 0,
    )

    private fun PlexMeta.toAlbum() = RemoteAlbum(
        id = ratingKey,
        name = title.ifBlank { "未知专辑" },
        artistName = parentTitle ?: "",
        coverId = thumb,
        songCount = leafCount ?: 0,
    )

    private fun PlexMeta.partKey(): String? =
        media?.firstOrNull()?.part?.firstOrNull()?.key

    private suspend fun OkHttpClient.cancellableCall(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = newCall(request)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response)
                }

                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }
            })
        }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }
}

// ---------------------------------------------------------------- JSON 结构（只声明用到的字段）

@Serializable
internal data class PlexEnvelope(
    @SerialName("MediaContainer") val mediaContainer: PlexContainer? = null,
)

@Serializable
internal data class PlexContainer(
    val size: Int? = null,
    val machineIdentifier: String? = null,
    // Plex 的 JSON 用大写键名，这里用 @SerialName 映射成 Kotlin 小写字段，
    // 避免「大写属性 + 小写别名」生成同名 JVM getter 的冲突
    @SerialName("Directory") val directory: List<PlexDirectory>? = null,
    @SerialName("Metadata") val metadata: List<PlexMeta>? = null,
    @SerialName("Hub") val hub: List<PlexHub>? = null,
)

@Serializable
internal data class PlexDirectory(
    val key: String = "",
    val type: String = "",
    val title: String = "",
)

@Serializable
internal data class PlexHub(
    val type: String = "",
    @SerialName("Metadata") val metadata: List<PlexMeta>? = null,
)

@Serializable
internal data class PlexMeta(
    val ratingKey: String = "",
    val key: String = "",
    val type: String = "",
    val title: String = "",
    val parentTitle: String? = null,
    val parentRatingKey: String? = null,
    val grandparentTitle: String? = null,
    val thumb: String? = null,
    val parentThumb: String? = null,
    val composite: String? = null,
    val duration: Long? = null,
    val index: Int? = null,
    val leafCount: Int? = null,
    @SerialName("Media") val media: List<PlexMedia>? = null,
)

@Serializable
internal data class PlexMedia(
    @SerialName("Part") val part: List<PlexPart>? = null,
)

@Serializable
internal data class PlexPart(val key: String? = null)
