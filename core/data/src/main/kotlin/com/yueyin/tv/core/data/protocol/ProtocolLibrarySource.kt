package com.yueyin.tv.core.data.protocol

import com.yueyin.tv.core.data.connect.ConnectProfile
import com.yueyin.tv.core.data.online.OnlineSong
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.yueyin.tv.core.lyrics.parseLyrics
import com.yueyin.tv.core.model.Album
import com.yueyin.tv.core.model.Artist
import com.yueyin.tv.core.model.CollectionGuid
import com.yueyin.tv.core.model.CoverVariant
import com.yueyin.tv.core.model.LyricDocument
import com.yueyin.tv.core.model.Page
import com.yueyin.tv.core.model.PlaybackTrack
import com.yueyin.tv.core.model.Playlist
import com.yueyin.tv.core.data.repository.SearchResults
import com.yueyin.tv.core.model.Track
import com.yueyin.tv.core.model.TrackGuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    /** 当前协议是否有服务端收藏（false 时上层走本地收藏兜底）。 */
    val supportsServerFavorites: Boolean get() = protocol.supportsServerFavorites

    private val mutex = Mutex()
    private val playlistCache = mutableMapOf<String, List<RemoteSong>>()
    private val albumSongCache = mutableMapOf<String, List<RemoteSong>>()
    private val artistSongCache = mutableMapOf<String, List<RemoteSong>>()
    private val artistAlbumCache = mutableMapOf<String, List<RemoteAlbum>>()
    private var allPlaylists: List<RemotePlaylist>? = null
    private var allArtists: List<RemoteArtist>? = null
    private var allAlbums: List<RemoteAlbum>? = null
    private var allSongs: List<RemoteSong>? = null
    private var favoriteSongs: List<RemoteSong>? = null
    /**
     * 本会话内用户手动改过的收藏状态：服务端列表接口（UserData/starred）是快照，
     * 刚点完收藏再读旧缓存会显示错，这里以最后一次本地操作为准。
     */
    private val favoriteOverrides = mutableMapOf<String, Boolean>()

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
            // 空结果不缓存：在线平台首屏聚合歌手时若恰逢网络抖动返回空，
            // 缓存成空列表会让"歌手"长期空白且永不重试。非空才落缓存。
            allArtists ?: protocol.artists(profile).also { if (it.isNotEmpty()) allArtists = it }
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
            val cached = allSongs
            if (cached != null && cached.isNotEmpty()) {
                cached
            } else {
                val fetched = protocol.songs(profile)
                // 空结果不写缓存：首次进「全部歌曲」常赶上音源脚本未就绪 / 网络没通，
                // 那一轮 songs() 会返回空。以前把空列表也缓存下来，这一页就永远显示
                // 「暂无歌曲」，只有切平台清缓存才恢复——用户看到的就是「曲库突然空了」。
                if (fetched.isNotEmpty()) allSongs = fetched
                fetched
            }
        }
        return songs.map { it.toTrack() }.paged(page, pageSize)
    }

    /**
     * 收藏列表：有服务端收藏协议的走协议（Emby 的 Filters=IsFavorite / Subsonic 的
     * getStarred2 / Plex 的 userRating>=10），与收藏开关操作同一份数据，天然一致。
     * 文件类协议没有服务端收藏，返回空页，由上层本地收藏兜底。
     */
    suspend fun favoriteTracks(page: Int, pageSize: Int = 100): Page<Track> {
        if (!protocol.supportsServerFavorites) return emptyList<Track>().paged(page, pageSize)
        val songs = mutex.withLock {
            favoriteSongs ?: protocol.favoriteSongs(profile).also { favoriteSongs = it }
        }
        return songs.map { it.toTrack() }.paged(page, pageSize)
    }

    suspend fun search(query: String): SearchResults {
        if (query.isBlank()) return SearchResults()
        // 歌曲与专辑并行：串行的话用户要先等专辑超时（最长 4.5 秒）才看到歌曲列表。
        val (songs, albums) = coroutineScope {
            val songsJob = async {
                runCatching { protocol.search(profile, query) }.getOrDefault(emptyList())
            }
            val albumsJob = async {
                runCatching { protocol.searchAlbums(profile, query) }.getOrDefault(emptyList())
            }
            songsJob.await() to albumsJob.await()
        }
        return SearchResults(
            tracks = songs.map { it.toTrack() },
            // 歌手实体：在线侧没有独立的歌手目录，只能从歌曲聚合；
            // 这里保持空，由歌曲分区里的歌手名去定位（专辑已经能搜出来了）。
            artists = emptyList(),
            albums = albums.map { it.toDomain() },
            playlists = cachedPlaylists()
                .filter { it.name.contains(query, ignoreCase = true) }
                .map { it.toDomain() },
        )
    }

    // ---------------------------------------------------------------- 播放 / 封面 / 歌词

    /** 本地收藏落库时取在线取链元数据（非在线协议返回 null）。 */
    fun songOrNull(guid: String): OnlineSong? = protocol.songOrNull(guid)

    /** 冷启动后把落库的元数据放回在线协议的内存索引。 */
    fun restoreSongs(songs: List<OnlineSong>) = protocol.restoreSongs(songs)

    suspend fun prepare(track: Track): PlaybackTrack = PlaybackTrack(
        track = track,
        streamUrl = protocol.resolveStreamUrl(profile, track.guid.value),
        artworkUrl = track.coverId?.let { protocol.coverUrl(profile, it, CoverVariant.Player.width ?: 800) },
    )

    /** 整队列的播放信息：必须走协议取流地址，否则会拿到飞牛的 URL 而播不出来。 */
    suspend fun queue(tracks: List<Track>): List<PlaybackTrack> = tracks.map { prepare(it) }

    /**
     * 只把 [startIndex] 起往后的 [aheadCount] 首直链取出来，其余先留空。
     *
     * 在线音源取一条链要过一次 WebView 里的脚本 + 一次 1KB 探活，实测 ~300ms/首；
     * 100 首的队列一次性取完要 30 秒以上——用户点「播放全部」后要干等半分钟才出声、
     * 播放页也才弹出来，体感就是「点了没反应」。
     * 所以只取「即将要播的那首 + 后面一首」，剩下的由调用方在后台补齐
     * （见 PlaybackController.patchStreamUrl）。
     *
     * 注意起点是 [startIndex] 而不是 0：用户点榜单第 80 首时，
     * 从 0 开始按顺序取要先把前 79 首取完才轮到他点的那首，白白干等 24 秒。
     */
    suspend fun queueEager(tracks: List<Track>, startIndex: Int, aheadCount: Int): List<PlaybackTrack> {
        val from = startIndex.coerceIn(0, maxOf(tracks.size - 1, 0))
        val eagerRange = from until (from + aheadCount.coerceAtLeast(1)).coerceAtMost(tracks.size)
        return tracks.mapIndexed { index, track ->
            if (index in eagerRange) {
                prepare(track)
            } else {
                PlaybackTrack(
                    track = track,
                    streamUrl = "",
                    artworkUrl = track.coverId?.let { protocol.coverUrl(profile, it, CoverVariant.Player.width ?: 800) },
                )
            }
        }
    }

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
        android.util.Log.d("YueyinCover", "artwork coverId=$coverId url=$url")
        if (url == null) return@withContext null
        runCatching {
            val request = Request.Builder()
                .url(url)
                // 在线平台（QQ 的 y.gtimg.cn、网易的 CDN 等）对封面有防盗链，
                // 裸请求会被判 403。带一个浏览器 UA 和空 Referer 即可绕过大部分校验；
                // 服务器平台（飞牛/Navidrome）的 coverUrl 走 LocalStreamProxy，这些头不影响。
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Referer", "https://music.163.com/")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                android.util.Log.d("YueyinCover", "artwork response code=${response.code} len=${response.body.contentLength()}")
                if (!response.isSuccessful) return@runCatching null
                response.body.bytes()
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

    suspend fun setFavorite(guid: String, favorite: Boolean): Boolean {
        val ok = runCatching { protocol.setFavorite(profile, guid, favorite) }.getOrDefault(false)
        if (ok) {
            // 成功才更新本地视图：记下最新状态，并作废收藏列表缓存（下次进收藏页重新拉）
            mutex.withLock {
                favoriteOverrides[guid] = favorite
                favoriteSongs = null
            }
        }
        return ok
    }

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
        // 本会话的手动操作优先于服务端快照，避免"点了收藏列表里还是不显示"
        isFavorite = favoriteOverrides[id] ?: isFavorite,
    )

    private fun RemoteAlbum.toDomain() = Album(
        guid = CollectionGuid(id),
        name = name.ifBlank { "未知专辑" },
        artistName = artistName.takeIf { it.isNotBlank() },
        coverId = coverId,
        trackCount = songCount.takeIf { it > 0 },
        // 只留年份：卡片副标题是「歌手 · 2016」，整条日期在窄卡片里放不下
        releaseDate = releaseDate.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) },
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
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
