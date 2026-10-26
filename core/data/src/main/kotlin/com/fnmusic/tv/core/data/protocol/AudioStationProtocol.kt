package com.fnmusic.tv.core.data.protocol

import com.fnmusic.tv.core.data.connect.ConnectProfile
import com.fnmusic.tv.core.model.connect.ConnectType
import com.fnmusic.tv.core.model.connect.ServerCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * 群晖 AudioStation 协议（DSM 6 / 7 通用）。
 *
 * 关键点：
 * 1. 各 API 的 cgi 路径在 DSM 版本间会变，所以先查 `SYNO.API.Info` 拿到真实 path 再调用，
 *    查不到时退回固定的 `AudioStation/xxx.cgi` 老路径；
 * 2. 登录拿 `sid` 后，后续接口的会话参数各家实现有 `_sid` / `sid` 两种写法，
 *    这里两个都带上（服务端会忽略不认识的参数），避免踩版本差异；
 * 3. 会话过期（error 106/107/119）自动重登一次再重试，避免 TV 上长时间待机后不能播。
 */
class AudioStationProtocol(
    override val type: ConnectType = ConnectType.AUDIO_STATION,
    private val client: OkHttpClient = defaultClient(),
) : MusicProtocol {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** api 名 → webapi 下的相对路径。按 host 缓存，换服务器不影响。 */
    private val apiPaths = ConcurrentHashMap<String, Map<String, String>>()

    /** 最近一次登录拿到的 sid，供 `streamUrl()` 这种同步方法使用。 */
    @Volatile private var lastSid: String = ""

    // ---------------------------------------------------------------- 登录

    override suspend fun ping(profile: ConnectProfile): Boolean = withContext(Dispatchers.IO) {
        loginSession(profile) != null
    }

    override suspend fun login(profile: ConnectProfile): ServerCapabilities =
        withContext(Dispatchers.IO) {
            val sid = loginSession(profile) ?: return@withContext unsupportedCaps()
            lastSid = sid
            ServerCapabilities(
                lyrics = false,
                favorites = false,
                playlists = true,
                musicVideos = false,
                radio = false,
                search = true,
            )
        }

    /** sid 落库：播放地址需要它，否则「连得上但播不了」。 */
    override suspend fun enrich(profile: ConnectProfile): ConnectProfile {
        val sid = lastSid.takeIf { it.isNotBlank() }
            ?: withContext(Dispatchers.IO) { loginSession(profile) }
            ?: return profile
        return profile.copy(token = sid)
    }

    // ---------------------------------------------------------------- 列表

    override suspend fun artists(profile: ConnectProfile): List<RemoteArtist> = withContext(Dispatchers.IO) {
        runCatching {
            val data = call(profile, API_ARTIST, "list", mapOf("library" to "all", "limit" to "1000", "offset" to "0"))
            data.artists.orEmpty().map { RemoteArtist(id = it.name, name = it.name) }
        }.getOrDefault(emptyList())
    }

    override suspend fun albums(profile: ConnectProfile): List<RemoteAlbum> = withContext(Dispatchers.IO) {
        runCatching {
            val data = call(profile, API_ALBUM, "list", mapOf("library" to "all", "limit" to "1000", "offset" to "0"))
            data.albums.orEmpty().map { it.toAlbum() }
        }.getOrDefault(emptyList())
    }

    override suspend fun songs(profile: ConnectProfile): List<RemoteSong> = withContext(Dispatchers.IO) {
        runCatching {
            // AudioStation 的 list 一次最多 500 条，翻几页凑一份可用的曲库快照
            val result = mutableListOf<RemoteSong>()
            var offset = 0
            while (offset < SONG_SNAPSHOT_LIMIT) {
                val data = call(
                    profile,
                    API_SONG,
                    "list",
                    mapOf(
                        "library" to "all",
                        "limit" to "500",
                        "offset" to offset.toString(),
                        "additional" to "song_tag,song_audio",
                    ),
                )
                val page = data.songs.orEmpty().map { it.toSong() }
                result += page
                if (page.size < 500) break
                offset += 500
            }
            result
        }.getOrDefault(emptyList())
    }

    override suspend fun playlists(profile: ConnectProfile): List<RemotePlaylist> = withContext(Dispatchers.IO) {
        runCatching {
            val data = call(profile, API_PLAYLIST, "list", mapOf("limit" to "500", "offset" to "0"))
            data.playlists.orEmpty().map { playlist ->
                RemotePlaylist(
                    id = playlist.id,
                    name = playlist.name,
                    songCount = playlist.songCount ?: 0,
                )
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun playlistSongs(profile: ConnectProfile, playlistId: String): List<RemoteSong> =
        withContext(Dispatchers.IO) {
            runCatching {
                val data = call(
                    profile,
                    API_PLAYLIST,
                    "get",
                    mapOf("id" to playlistId, "limit" to "1000", "offset" to "0", "additional" to "song_tag"),
                )
                data.songs.orEmpty().map { it.toSong() }
            }.getOrDefault(emptyList())
        }

    override suspend fun albumSongs(profile: ConnectProfile, albumId: String): List<RemoteSong> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (name, artist) = decodeAlbumId(albumId)
                val params = mutableMapOf(
                    "library" to "all",
                    "limit" to "1000",
                    "offset" to "0",
                    "album" to name,
                    "additional" to "song_tag",
                )
                if (artist.isNotBlank()) params["album_artist"] = artist
                val data = call(profile, API_SONG, "list", params)
                data.songs.orEmpty().map { it.toSong() }
            }.getOrDefault(emptyList())
        }

    override suspend fun artistSongs(profile: ConnectProfile, artistId: String): List<RemoteSong> =
        withContext(Dispatchers.IO) {
            runCatching {
                val data = call(
                    profile,
                    API_SONG,
                    "list",
                    mapOf(
                        "library" to "all",
                        "limit" to "1000",
                        "offset" to "0",
                        "artist" to artistId,
                        "additional" to "song_tag",
                    ),
                )
                data.songs.orEmpty().map { it.toSong() }
            }.getOrDefault(emptyList())
        }

    override suspend fun artistAlbums(profile: ConnectProfile, artistId: String): List<RemoteAlbum> =
        withContext(Dispatchers.IO) {
            runCatching {
                val data = call(
                    profile,
                    API_ALBUM,
                    "list",
                    mapOf("library" to "all", "limit" to "1000", "offset" to "0", "artist" to artistId),
                )
                data.albums.orEmpty().map { it.toAlbum() }
            }.getOrDefault(emptyList())
        }

    /**
     * 搜索：优先用 `SYNO.AudioStation.Search`；部分 DSM 版本没有该方法，
     * 此时退回「取一批歌在本地按歌名/歌手/专辑过滤」，保证搜索框永远不会空手而归。
     */
    override suspend fun search(profile: ConnectProfile, query: String): List<RemoteSong> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val remote = runCatching {
                val data = call(
                    profile,
                    API_SEARCH,
                    "list",
                    mapOf("keyword" to query, "limit" to "100", "offset" to "0", "additional" to "song_tag"),
                )
                data.songs.orEmpty().map { it.toSong() }
            }.getOrDefault(emptyList())
            if (remote.isNotEmpty()) return@withContext remote
            runCatching {
                songs(profile)
                    .filter {
                        it.title.contains(query, ignoreCase = true) ||
                            it.artistName.contains(query, ignoreCase = true) ||
                            it.albumName.contains(query, ignoreCase = true)
                    }
                    .take(100)
            }.getOrDefault(emptyList())
        }

    // ---------------------------------------------------------------- 播放 / 封面

    override fun streamUrl(profile: ConnectProfile, songId: String): String {
        val base = baseOf(profile)
        // 同步方法不能查 API 路径（那是网络请求），登录时已经把表建好了，这里只读缓存
        val path = cachedPathOf(base, API_STREAM)
        val sid = sessionOf(profile)
        return "$base/webapi/$path?api=$API_STREAM&method=stream&version=2&id=${encode(songId)}" +
            "&sid=${encode(sid)}&_sid=${encode(sid)}"
    }

    override fun coverUrl(profile: ConnectProfile, coverId: String, size: Int): String {
        if (coverId.isBlank()) return ""
        val base = baseOf(profile)
        val path = cachedPathOf(base, API_COVER)
        val sid = sessionOf(profile)
        val dimension = if (size <= 200) "sm" else "xl"
        return "$base/webapi/$path?api=$API_COVER&method=getcover&version=1&id=${encode(coverId)}" +
            "&size=$dimension&sid=${encode(sid)}&_sid=${encode(sid)}"
    }

    override suspend fun lyrics(
        profile: ConnectProfile,
        songId: String,
        artist: String,
        title: String,
    ): String? = null

    override suspend fun setFavorite(profile: ConnectProfile, songId: String, favorite: Boolean): Boolean = false

    // ---------------------------------------------------------------- 内部

    private suspend fun loginSession(profile: ConnectProfile): String? {
        val base = baseOf(profile)
        val url = "$base/webapi/auth.cgi?api=SYNO.API.Auth&version=6&method=login" +
            "&account=${encode(profile.username)}&passwd=${encode(profile.password)}" +
            "&session=AudioStation&format=sid"
        return runCatching {
            val envelope = getJson(url)
            envelope.data?.sid?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private suspend fun call(
        profile: ConnectProfile,
        api: String,
        method: String,
        params: Map<String, String> = emptyMap(),
        retried: Boolean = false,
    ): SynoData {
        val base = baseOf(profile)
        val path = pathOf(base, api)
        val sid = sessionOf(profile)
        val query = buildString {
            append("api=").append(api)
            append("&version=").append(versionOf(api))
            append("&method=").append(method)
            append("&_sid=").append(encode(sid))
            append("&sid=").append(encode(sid))
            params.forEach { (key, value) -> append('&').append(key).append('=').append(encode(value)) }
        }
        val envelope = getJson("$base/webapi/$path?$query")
        if (!envelope.success) {
            val code = envelope.error?.code ?: 0
            // 106=会话失效 / 107=重复登录 / 119=会话过期
            if (!retried && code in SESSION_ERRORS) {
                val fresh = loginSession(profile)
                if (!fresh.isNullOrBlank()) {
                    lastSid = fresh
                    return call(profile, api, method, params, retried = true)
                }
            }
            error("Synology error $code")
        }
        return envelope.data ?: SynoData()
    }

    private suspend fun getJson(url: String): SynoEnvelope {
        val text = getText(url)
        return json.decodeFromString(SynoEnvelope.serializer(), text)
    }

    private suspend fun getText(url: String): String {
        val request = Request.Builder().url(url).get().build()
        client.cancellableCall(request).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return text
        }
    }

    /** 同步版本：只查已经缓存好的路径表，供 [streamUrl] / [coverUrl] 使用。 */
    private fun cachedPathOf(base: String, api: String): String =
        apiPaths[base]?.get(api) ?: fallbackPath(api)

    /** 查 `SYNO.API.Info` 得到真实路径；失败时用老路径兜底。 */
    private suspend fun pathOf(base: String, api: String): String {
        val cached = apiPaths[base]
        if (cached != null) return cached[api] ?: fallbackPath(api)
        val resolved = runCatching { fetchApiPaths(base) }.getOrDefault(emptyMap())
        apiPaths[base] = resolved
        return resolved[api] ?: fallbackPath(api)
    }

    /**
     * `query.cgi` 的 `data` 是「api 名 → { path, minVersion, maxVersion }」的动态键名对象，
     * 固定 data class 收不住，必须按 JsonObject 逐项取。
     */    private suspend fun fetchApiPaths(base: String): Map<String, String> {
        val names = API_ALL.joinToString(",")
        val text = getText("$base/webapi/query.cgi?api=SYNO.API.Info&version=1&method=query&query=$names&format=json")
        val root = json.parseToJsonElement(text).jsonObject
        if (root["success"]?.jsonPrimitive?.booleanOrNull != true) return emptyMap()
        val data = root["data"]?.jsonObject ?: return emptyMap()
        return data.mapNotNull { (api, element) ->
            val path = element.jsonObject["path"]?.jsonPrimitive?.contentOrNull
            if (path.isNullOrBlank()) null else api to path
        }.toMap()
    }

    private fun versionOf(api: String): String = when (api) {
        API_ARTIST -> "1"
        API_ALBUM -> "2"
        API_PLAYLIST -> "2"
        API_SEARCH -> "1"
        else -> "3"
    }

    private fun fallbackPath(api: String): String = when (api) {
        API_SONG -> "AudioStation/song.cgi"
        API_ALBUM -> "AudioStation/album.cgi"
        API_ARTIST -> "AudioStation/artist.cgi"
        API_PLAYLIST -> "AudioStation/playlist.cgi"
        API_SEARCH -> "AudioStation/search.cgi"
        API_STREAM -> "AudioStation/stream.cgi"
        API_COVER -> "AudioStation/cover.cgi"
        else -> "auth.cgi"
    }

    private fun baseOf(profile: ConnectProfile): String {
        val raw = profile.host.trim().trimEnd('/')
        return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
    }

    private fun sessionOf(profile: ConnectProfile): String =
        lastSid.ifBlank { profile.token }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun encodeAlbumId(name: String, artist: String) = "$name\u0001$artist"

    private fun decodeAlbumId(id: String): Pair<String, String> {
        val parts = id.split('\u0001')
        return parts.firstOrNull().orEmpty() to parts.getOrElse(1) { "" }
    }

    private fun unsupportedCaps() = ServerCapabilities(
        lyrics = false, favorites = false, playlists = false, musicVideos = false, radio = false, search = false,
    )

    private fun SynoSong.toSong() = RemoteSong(
        id = id,
        title = title.ifBlank { "未知曲目" },
        artistName = artist ?: additional?.songTag?.artist ?: "",
        albumName = album ?: additional?.songTag?.album ?: "",
        albumId = album?.let { encodeAlbumId(it, artist.orEmpty()) },
        durationMs = (duration ?: 0L) * 1000L,
        coverId = id,
        trackNumber = additional?.songTag?.track ?: 0,
    )

    private fun SynoAlbum.toAlbum() = RemoteAlbum(
        id = encodeAlbumId(name, artist.orEmpty()),
        name = name.ifBlank { "未知专辑" },
        artistName = artist.orEmpty(),
        coverId = encodeAlbumId(name, artist.orEmpty()),
        songCount = songCount ?: 0,
    )

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
        const val API_SONG = "SYNO.AudioStation.Song"
        const val API_ALBUM = "SYNO.AudioStation.Album"
        const val API_ARTIST = "SYNO.AudioStation.Artist"
        const val API_PLAYLIST = "SYNO.AudioStation.Playlist"
        const val API_SEARCH = "SYNO.AudioStation.Search"
        const val API_STREAM = "SYNO.AudioStation.Stream"
        const val API_COVER = "SYNO.AudioStation.Cover"
        val API_ALL = listOf(API_SONG, API_ALBUM, API_ARTIST, API_PLAYLIST, API_SEARCH, API_STREAM, API_COVER)
        val SESSION_ERRORS = setOf(106, 107, 119)
        const val SONG_SNAPSHOT_LIMIT = 5_000

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }
}

// ---------------------------------------------------------------- JSON 结构

@Serializable
internal data class SynoEnvelope(
    val success: Boolean = false,
    val data: SynoData? = null,
    val error: SynoError? = null,
)

@Serializable
internal data class SynoError(val code: Int = 0)

@Serializable
internal data class SynoData(
    val sid: String? = null,
    val songs: List<SynoSong>? = null,
    val albums: List<SynoAlbum>? = null,
    val artists: List<SynoArtist>? = null,
    val playlists: List<SynoPlaylist>? = null,
    val total: Int? = null,
)

@Serializable
internal data class SynoSong(
    val id: String = "",
    val title: String = "",
    val artist: String? = null,
    val album: String? = null,
    val duration: Long? = null,
    val path: String? = null,
    val additional: SynoAdditional? = null,
)

@Serializable
internal data class SynoAdditional(val song_tag: SynoSongTag? = null) {
    val songTag: SynoSongTag? get() = song_tag
}

@Serializable
internal data class SynoSongTag(
    val album: String? = null,
    val artist: String? = null,
    val track: Int? = null,
)

@Serializable
internal data class SynoAlbum(
    val name: String = "",
    val artist: String? = null,
    val album_artist: String? = null,
    val song_count: Int? = null,
) {
    val songCount: Int? get() = song_count
}

@Serializable internal data class SynoArtist(val name: String = "")

@Serializable
internal data class SynoPlaylist(
    val id: String = "",
    val name: String = "",
    val song_count: Int? = null,
) {
    val songCount: Int? get() = song_count
}
