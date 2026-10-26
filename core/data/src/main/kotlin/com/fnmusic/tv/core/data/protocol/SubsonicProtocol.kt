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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Subsonic 协议实现：一套代码覆盖 **Navidrome / Subsonic / Airsonic / gonic** 等兼容服务。
 *
 * 鉴权用最简单的 `p=enc:<密码的十六进制>`（Subsonic 规范允许），避免 token+salt 的实现差异；
 * 全部请求固定 `f=json`，解析失败时返回空列表而不是抛异常——TV 上宁可"这项没内容"也不能崩。
 */
class SubsonicProtocol(
    override val type: ConnectType,
    private val client: OkHttpClient = defaultClient(),
) : MusicProtocol {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 可取消的 HTTP 调用：用 `enqueue` + 回调代替阻塞的 `execute()`，
     * 让协程的 `withTimeout` / `cancel` 能通过 `call.cancel()` 真正中断在途请求
     * （`execute()` 阻塞 IO 线程时协程无法抢占，超时形同虚设，会让搜索界面永久转圈）。
     */
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

    override suspend fun ping(profile: ConnectProfile): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val body = get(profile, "ping")
            body.response.status.equals("ok", ignoreCase = true)
        }.getOrDefault(false)
    }

    override suspend fun login(profile: ConnectProfile): ServerCapabilities {
        val ok = ping(profile)
        return if (ok) ServerCapabilities(
            lyrics = true,
            favorites = true,
            playlists = true,
            // Subsonic 协议本身没有音乐视频；电台只有部分实现支持
            musicVideos = false,
            radio = runCatching { !get(profile, "getInternetRadioStations").response.internetRadioStations?.station.isNullOrEmpty() }
                .getOrDefault(false),
            search = true,
        ) else {
            ServerCapabilities(lyrics = false, favorites = false, playlists = false, musicVideos = false, radio = false, search = false)
        }
    }

    override suspend fun artists(profile: ConnectProfile): List<RemoteArtist> = withContext(Dispatchers.IO) {
        runCatching {
            val block = get(profile, "getArtists").response.artists ?: return@runCatching emptyList()
            block.index.orEmpty().flatMap { index ->
                index.artist.orEmpty().map { artist ->
                    RemoteArtist(id = artist.id, name = artist.name, coverId = artist.id)
                }
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun albums(profile: ConnectProfile): List<RemoteAlbum> = withContext(Dispatchers.IO) {
        runCatching {
            val block = get(profile, "getAlbumList2", mapOf("type" to "alphabeticalByName", "size" to "500"))
                .response.albumList2 ?: return@runCatching emptyList()
            block.album.orEmpty().map { it.toRemote() }
        }.getOrDefault(emptyList())
    }

    /**
     * Subsonic 没有"列出全部歌曲"的接口，这里用 `getRandomSongs` 取样 500 首作为曲库近似。
     * 真正的全量浏览请走专辑/歌单维度（这也是各家 Subsonic 客户端的通行做法）。
     */
    override suspend fun songs(profile: ConnectProfile): List<RemoteSong> = withContext(Dispatchers.IO) {
        runCatching {
            val block = get(profile, "getRandomSongs", mapOf("size" to "500")).response.randomSongs
                ?: return@runCatching emptyList()
            block.song.orEmpty().map { it.toRemote() }
        }.getOrDefault(emptyList())
    }

    override suspend fun playlists(profile: ConnectProfile): List<RemotePlaylist> = withContext(Dispatchers.IO) {
        runCatching {
            val block = get(profile, "getPlaylists").response.playlists ?: return@runCatching emptyList()
            block.playlist.orEmpty().map { playlist ->
                RemotePlaylist(
                    id = playlist.id,
                    name = playlist.name,
                    songCount = playlist.songCount ?: 0,
                    coverId = playlist.coverArt ?: playlist.id,
                )
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun playlistSongs(profile: ConnectProfile, playlistId: String): List<RemoteSong> =
        withContext(Dispatchers.IO) {
            runCatching {
                val block = get(profile, "getPlaylist", mapOf("id" to playlistId)).response.playlist
                    ?: return@runCatching emptyList()
                block.entry.orEmpty().map { it.toRemote() }
            }.getOrDefault(emptyList())
        }

    override suspend fun search(profile: ConnectProfile, query: String): List<RemoteSong> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            runCatching {
                val block = get(
                    profile,
                    "search3",
                    mapOf("query" to query, "songCount" to "50", "albumCount" to "0", "artistCount" to "0"),
                ).response.searchResult3 ?: return@runCatching emptyList()
                block.song.orEmpty().map { it.toRemote() }
            }.getOrDefault(emptyList())
        }

    override suspend fun albumSongs(profile: ConnectProfile, albumId: String): List<RemoteSong> =
        withContext(Dispatchers.IO) {
            runCatching {
                val block = get(profile, "getAlbum", mapOf("id" to albumId)).response.album
                    ?: return@runCatching emptyList()
                block.song.orEmpty().map { it.toRemote() }
            }.getOrDefault(emptyList())
        }

    override suspend fun artistSongs(profile: ConnectProfile, artistId: String): List<RemoteSong> =
        withContext(Dispatchers.IO) {
            runCatching {
                val block = get(profile, "getArtist", mapOf("id" to artistId)).response.artist
                    ?: return@runCatching emptyList()
                block.song.orEmpty().map { it.toRemote() }
            }.getOrDefault(emptyList())
        }

    override suspend fun artistAlbums(profile: ConnectProfile, artistId: String): List<RemoteAlbum> =
        withContext(Dispatchers.IO) {
            runCatching {
                val block = get(profile, "getArtist", mapOf("id" to artistId)).response.artist
                    ?: return@runCatching emptyList()
                block.album.orEmpty().map { it.toRemote() }
            }.getOrDefault(emptyList())
        }

    override fun streamUrl(profile: ConnectProfile, songId: String): String =
        buildUrl(profile, "stream", mapOf("id" to songId))

    override fun coverUrl(profile: ConnectProfile, coverId: String, size: Int): String =
        buildUrl(profile, "getCoverArt", mapOf("id" to coverId, "size" to size.toString()))

    override suspend fun lyrics(
        profile: ConnectProfile,
        songId: String,
        artist: String,
        title: String,
    ): String? = withContext(Dispatchers.IO) {
        // 优先走 OpenSubsonic 的结构化歌词（带行时间），拿不到再退回传统纯文本接口
        val structured = runCatching {
            val block = get(profile, "getLyricsBySongId", mapOf("id" to songId)).response.lyricsList
                ?: return@runCatching null
            block.structuredLyrics.orEmpty().firstOrNull()?.line.orEmpty()
                .joinToString("\n") { line -> "[${line.start ?: 0L}]${line.value}" }
        }.getOrNull()
        if (!structured.isNullOrBlank()) return@withContext structured
        runCatching {
            get(profile, "getLyrics", mapOf("artist" to artist, "title" to title)).response.lyrics?.value
        }.getOrNull()
    }

    override suspend fun setFavorite(profile: ConnectProfile, songId: String, favorite: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                // star / unstar 只需要 id 参数
                val endpoint = if (favorite) "star" else "unstar"
                get(profile, endpoint, mapOf("id" to songId)).response.status.equals("ok", ignoreCase = true)
            }.getOrDefault(false)
        }

    // ---------------------------------------------------------------- 内部

    private fun buildUrl(profile: ConnectProfile, method: String, extra: Map<String, String>): String {
        val base = profile.host.trim().trimEnd('/')
        val auth = buildString {
            append("u=").append(encode(profile.username))
            append("&p=enc:").append(hex(profile.password))
            append("&v=1.16.1&c=yueyin&f=json")
        }
        val params = extra.entries.joinToString("&") { "${it.key}=${encode(it.value)}" }
        val suffix = if (params.isEmpty()) "" else "&$params"
        return "$base/rest/$method.view?$auth$suffix"
    }

    private suspend fun get(profile: ConnectProfile, method: String, extra: Map<String, String> = emptyMap()): Envelope {
        val request = Request.Builder()
            .url(buildUrl(profile, method, extra))
            .header("Accept", "application/json")
            .get()
            .build()
        client.cancellableCall(request).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return json.decodeFromString(Envelope.serializer(), text)
        }
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun hex(value: String): String =
        value.toByteArray(Charsets.UTF_8).joinToString("") { String.format("%02x", it) }

    private fun SubsonicSong.toRemote() = RemoteSong(
        id = id,
        title = title ?: "未知曲目",
        artistName = artist ?: "",
        albumName = album ?: "",
        albumId = albumId,
        durationMs = (duration ?: 0) * 1000L,
        coverId = coverArt ?: id,
        trackNumber = track ?: 0,
    )

    private fun SubsonicAlbum.toRemote() = RemoteAlbum(
        id = id,
        name = name ?: "未知专辑",
        artistName = artist ?: "",
        coverId = coverArt ?: id,
        songCount = songCount ?: 0,
    )

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

// ---------------------------------------------------------------- JSON 结构（只声明用到的字段）

@Serializable
internal data class Envelope(
    @SerialName("subsonic-response") val response: SubsonicResponse = SubsonicResponse(),
)

@Serializable
internal data class SubsonicResponse(
    val status: String = "",
    val version: String = "",
    val artists: ArtistsBlock? = null,
    val albumList2: AlbumListBlock? = null,
    val randomSongs: RandomSongsBlock? = null,
    val playlists: PlaylistsBlock? = null,
    val playlist: PlaylistBlock? = null,
    val searchResult3: SearchBlock? = null,
    val album: AlbumBlock? = null,
    val artist: ArtistDetailBlock? = null,
    val lyricsList: LyricsListBlock? = null,
    val lyrics: LyricsBlock? = null,
    val internetRadioStations: RadioBlock? = null,
)

@Serializable internal data class ArtistsBlock(val index: List<ArtistIndex>? = null)
@Serializable internal data class ArtistIndex(val artist: List<SubsonicArtist>? = null)
@Serializable internal data class SubsonicArtist(val id: String = "", val name: String = "")

@Serializable internal data class AlbumListBlock(val album: List<SubsonicAlbum>? = null)
@Serializable internal data class SubsonicAlbum(
    val id: String = "",
    val name: String? = null,
    val artist: String? = null,
    val coverArt: String? = null,
    val songCount: Int? = null,
)

@Serializable internal data class RandomSongsBlock(val song: List<SubsonicSong>? = null)
@Serializable internal data class SearchBlock(val song: List<SubsonicSong>? = null)
@Serializable internal data class AlbumBlock(val song: List<SubsonicSong>? = null)
@Serializable internal data class ArtistDetailBlock(
    val song: List<SubsonicSong>? = null,
    val album: List<SubsonicAlbum>? = null,
)

@Serializable internal data class SubsonicSong(
    val id: String = "",
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val duration: Int? = null,
    val coverArt: String? = null,
    val track: Int? = null,
)

@Serializable internal data class PlaylistsBlock(val playlist: List<SubsonicPlaylist>? = null)
@Serializable internal data class PlaylistBlock(val entry: List<SubsonicSong>? = null)
@Serializable internal data class SubsonicPlaylist(
    val id: String = "",
    val name: String = "",
    val songCount: Int? = null,
    val coverArt: String? = null,
)

@Serializable internal data class LyricsListBlock(val structuredLyrics: List<StructuredLyrics>? = null)
@Serializable internal data class StructuredLyrics(val line: List<StructuredLine>? = null)
@Serializable internal data class StructuredLine(val start: Long? = null, val value: String = "")

@Serializable internal data class LyricsBlock(val value: String = "")
@Serializable internal data class RadioBlock(val station: List<SubsonicRadio>? = null)
@Serializable internal data class SubsonicRadio(val id: String = "", val name: String = "")
