package com.fnmusic.tv.core.data.protocol

import com.fnmusic.tv.core.data.connect.ConnectProfile
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.fnmusic.tv.core.lyrics.parseLyrics
import com.fnmusic.tv.core.model.Album
import com.fnmusic.tv.core.model.Artist
import com.fnmusic.tv.core.model.CollectionGuid
import com.fnmusic.tv.core.model.CoverVariant
import com.fnmusic.tv.core.model.LyricDocument
import com.fnmusic.tv.core.model.Page
import com.fnmusic.tv.core.model.PlaybackTrack
import com.fnmusic.tv.core.model.Playlist
import com.fnmusic.tv.core.data.repository.SearchResults
import com.fnmusic.tv.core.model.Track
import com.fnmusic.tv.core.model.TrackGuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 协议数据源：把任意平台的 [MusicProtocol] 结果映射成 App 现有的领域模型。
 *
 * 这样做的目的很关键——**首页 / 歌单 / 歌手 / 专辑 / 播放页 / 搜索全部不用改**，
 * 它们只认 `MusicRepository` 返回的 Track/Album/Artist/Playlist；
 * 把数据源换成当前激活的平台，界面自然就跟着换了（这也是 Amcfy 的核心做法）。
 *
 * 各协议的列表接口都是"一次给一批"（上限 500），这里在内存里缓存并按页切片，
 * 避免 UI 翻页时反复打网络。
 */
class ProtocolLibrarySource(
    private val profile: ConnectProfile,
    private val protocol: MusicProtocol,
    private val client: OkHttpClient = defaultClient(),
) {
    val displayName: String get() = profile.displayName
    val type = profile.type

    private val mutex = Mutex()
    private val playlistCache = mutableMapOf<String, List<RemoteSong>>()
    private val albumSongCache = mutableMapOf<String, List<RemoteSong>>()
    private val artistSongCache = mutableMapOf<String, List<RemoteSong>>()
    private val artistAlbumCache = mutableMapOf<String, List<RemoteAlbum>>()
    private var allPlaylists: List<RemotePlaylist>? = null
    private var allArtists: List<RemoteArtist>? = null
    private var allAlbums: List<RemoteAlbum>? = null
    private var allSongs: List<RemoteSong>? = null

    // ---------------------------------------------------------------- 列表

    suspend fun playlists(): List<Playlist> = cachedPlaylists().map { it.toDomain() }

    suspend fun playlist(id: String): Playlist? =
        cachedPlaylists().firstOrNull { it.id == id }?.toDomain()

    suspend fun playlistTracks(id: String, page: Int, pageSize: Int = 100): Page<Track> {
        val songs = mutex.withLock {
            playlistCache[id] ?: protocol.playlistSongs(profile, id).also { playlistCache[id] = it }
        }
        return songs.map { it.toTrack() }.paged(page, pageSize)
    }

    suspend fun artists(page: Int, pageSize: Int): Page<Artist> {
        val artists = mutex.withLock {
            allArtists ?: protocol.artists(profile).also { allArtists = it }
        }
        return artists.map { it.toDomain() }.paged(page, pageSize)
    }

    suspend fun artist(id: String): Artist? =
        cachedArtists().firstOrNull { it.id == id }?.toDomain()

    suspend fun artistTracks(id: String, page: Int, pageSize: Int = 100): Page<Track> {
        val songs = mutex.withLock {
            artistSongCache[id] ?: protocol.artistSongs(profile, id).also { artistSongCache[id] = it }
        }
        return songs.map { it.toTrack() }.paged(page, pageSize)
    }

    suspend fun artistAlbums(id: String, page: Int, pageSize: Int = 100): Page<Album> {
        val albums = mutex.withLock {
            artistAlbumCache[id] ?: protocol.artistAlbums(profile, id).also { artistAlbumCache[id] = it }
        }
        return albums.map { it.toDomain() }.paged(page, pageSize)
    }

    suspend fun albums(page: Int, pageSize: Int): Page<Album> {
        val albums = mutex.withLock {
            allAlbums ?: protocol.albums(profile).also { allAlbums = it }
        }
        return albums.map { it.toDomain() }.paged(page, pageSize)
    }

    suspend fun album(id: String): Album? =
        cachedAlbums().firstOrNull { it.id == id }?.toDomain()

    suspend fun albumTracks(id: String, page: Int, pageSize: Int = 100): Page<Track> {
        val songs = mutex.withLock {
            albumSongCache[id] ?: protocol.albumSongs(profile, id).also { albumSongCache[id] = it }
        }
        return songs.map { it.toTrack() }.paged(page, pageSize)
    }

    suspend fun allTracks(page: Int, pageSize: Int = 100): Page<Track> {
        val songs = mutex.withLock {
            allSongs ?: protocol.songs(profile).also { allSongs = it }
        }
        return songs.map { it.toTrack() }.paged(page, pageSize)
    }

    /** 收藏列表：协议层暂不单独提供，先返回空，避免界面出现"假收藏"。 */
    suspend fun favoriteTracks(page: Int, pageSize: Int = 100): Page<Track> =
        emptyList<Track>().paged(page, pageSize)

    suspend fun search(query: String): SearchResults {
        if (query.isBlank()) return SearchResults()
        val songs = runCatching { protocol.search(profile, query) }.getOrDefault(emptyList())
        return SearchResults(
            tracks = songs.map { it.toTrack() },
            artists = emptyList(),
            albums = emptyList(),
            playlists = cachedPlaylists()
                .filter { it.name.contains(query, ignoreCase = true) }
                .map { it.toDomain() },
        )
    }

    // ---------------------------------------------------------------- 播放 / 封面 / 歌词

    fun prepare(track: Track): PlaybackTrack = PlaybackTrack(
        track = track,
        streamUrl = protocol.streamUrl(profile, track.guid.value),
        artworkUrl = track.coverId?.let { protocol.coverUrl(profile, it, CoverVariant.Player.width ?: 800) },
    )

    /** 整队列的播放信息：必须走协议取流地址，否则会拿到飞牛的 URL 而播不出来。 */
    fun queue(tracks: List<Track>): List<PlaybackTrack> = tracks.map(::prepare)

    /** 按 guid 在已加载的缓存里找回曲目（播放页需要标题/歌手/封面）。 */
    suspend fun trackOrNull(guid: String): Track? {
        val pools = buildList {
            addAll(playlistCache.values)
            addAll(albumSongCache.values)
            addAll(artistSongCache.values)
            allSongs?.let(::add)
        }
        pools.forEach { songs ->
            songs.firstOrNull { it.id == guid }?.let { return it.toTrack() }
        }
        // 还没缓存过时，用搜索接口按 id 兜底（多数服务端都支持）
        return runCatching { protocol.search(profile, guid).firstOrNull { it.id == guid }?.toTrack() }.getOrNull()
    }

    suspend fun artwork(coverId: String, variant: CoverVariant): ByteArray? = withContext(Dispatchers.IO) {
        val url = protocol.coverUrl(profile, coverId, variant.width ?: 800).takeIf { it.isNotBlank() }
            ?: return@withContext null
        runCatching {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body?.bytes()
            }
        }.getOrNull()
    }

    suspend fun lyrics(guid: String, artist: String, title: String): Pair<LyricDocument?, SyncedLyrics?> {
        val raw = runCatching { protocol.lyrics(profile, guid, artist, title) }.getOrNull()
            ?: return null to null
        if (raw.isBlank()) return null to null
        return runCatching {
            val document = LyricDocument(guid = guid, content = raw, isLrc = raw.contains("["), offsetMs = 0L)
            document to parseLyrics(raw)
        }.getOrElse { null to null }
    }

    suspend fun setFavorite(guid: String, favorite: Boolean): Boolean =
        runCatching { protocol.setFavorite(profile, guid, favorite) }.getOrDefault(false)

    // ---------------------------------------------------------------- 内部

    private suspend fun cachedPlaylists(): List<RemotePlaylist> = mutex.withLock {
        allPlaylists ?: protocol.playlists(profile).also { allPlaylists = it }
    }

    private suspend fun cachedArtists(): List<RemoteArtist> = mutex.withLock {
        allArtists ?: protocol.artists(profile).also { allArtists = it }
    }

    private suspend fun cachedAlbums(): List<RemoteAlbum> = mutex.withLock {
        allAlbums ?: protocol.albums(profile).also { allAlbums = it }
    }

    private fun RemoteSong.toTrack() = Track(
        guid = TrackGuid(id),
        title = title.ifBlank { "未知曲目" },
        artistName = artistName.takeIf { it.isNotBlank() },
        albumName = albumName.takeIf { it.isNotBlank() },
        coverId = coverId,
        durationMs = durationMs.takeIf { it > 0 },
        isCue = false,
    )

    private fun RemoteAlbum.toDomain() = Album(
        guid = CollectionGuid(id),
        name = name.ifBlank { "未知专辑" },
        artistName = artistName.takeIf { it.isNotBlank() },
        coverId = coverId,
        trackCount = songCount.takeIf { it > 0 },
        releaseDate = null,
    )

    private fun RemoteArtist.toDomain() = Artist(
        guid = CollectionGuid(id),
        name = name.ifBlank { "未知歌手" },
        coverId = coverId,
        trackCount = null,
        albumCount = null,
    )

    private fun RemotePlaylist.toDomain() = Playlist(
        guid = CollectionGuid(id),
        name = name.ifBlank { "未命名歌单" },
        coverId = coverId,
        trackCount = songCount.takeIf { it > 0 },
    )

    private fun <T> List<T>.paged(page: Int, pageSize: Int): Page<T> {
        val safePage = page.coerceAtLeast(1)
        val from = (safePage - 1) * pageSize
        val slice = if (from >= size) emptyList() else drop(from).take(pageSize)
        return Page(items = slice, page = safePage, pageSize = pageSize, total = size, sort = "default")
    }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
