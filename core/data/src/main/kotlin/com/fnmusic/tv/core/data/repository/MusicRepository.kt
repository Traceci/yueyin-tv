package com.fnmusic.tv.core.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import com.fnmusic.tv.core.data.api.AlbumDto
import com.fnmusic.tv.core.data.api.ApiDecoder
import com.fnmusic.tv.core.data.api.ArtistDto
import com.fnmusic.tv.core.data.api.LyricListDto
import com.fnmusic.tv.core.data.api.PlaylistDetailDto
import com.fnmusic.tv.core.data.api.PlaylistDto
import com.fnmusic.tv.core.data.api.SharedLibraryDto
import com.fnmusic.tv.core.data.api.SortedPageListDto
import com.fnmusic.tv.core.data.api.TrackDto
import com.fnmusic.tv.core.data.api.TrackMetadataDto
import com.fnmusic.tv.core.data.api.isRetryableRequestFailure
import com.fnmusic.tv.core.data.local.CachedIndexEntity
import com.fnmusic.tv.core.data.local.CachedLyricEntity
import com.fnmusic.tv.core.data.local.CachedPageEntity
import com.fnmusic.tv.core.data.local.LocalStore
import com.fnmusic.tv.core.data.protocol.ProtocolLibrarySource
import com.fnmusic.tv.core.data.preferences.AppPreferences
import com.fnmusic.tv.core.model.Album
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import com.fnmusic.tv.core.model.Artist
import com.fnmusic.tv.core.model.CoverVariant
import com.fnmusic.tv.core.model.LyricDocument
import com.fnmusic.tv.core.model.Page
import com.fnmusic.tv.core.model.PlaybackTrack
import com.fnmusic.tv.core.model.Playlist
import com.fnmusic.tv.core.model.RoamWindow
import com.fnmusic.tv.core.model.SharedLibrary
import com.fnmusic.tv.core.model.Track
import com.fnmusic.tv.core.model.TrackGuid
import com.fnmusic.tv.core.model.playback.QueueSource
import com.fnmusic.tv.core.model.preferences.CacheUsage
import com.fnmusic.tv.core.lyrics.DefaultLyricsSources
import com.fnmusic.tv.core.lyrics.LyricsMatchCoordinator
import com.fnmusic.tv.core.lyrics.LyricsMatchRequest
import com.fnmusic.tv.core.lyrics.LyricsMatchResult
import com.fnmusic.tv.core.lyrics.hasUsableLines
import com.fnmusic.tv.core.lyrics.parseLyrics
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private const val MAX_ARTWORK_DOWNLOAD_BYTES = 20 * 1024 * 1024
private const val MAX_ARTWORK_EDGE = 8_192
private const val MAX_ARTWORK_PIXELS = 16_000_000L
private const val ARTWORK_VALIDATION_LONG_EDGE = 128

private const val SEARCH_PAGE_SIZE = 200

/** 搜索索引使用的分页大小：与浏览页相同（浏览页已验证该大小下服务端分页正常、可完整拉取）。 */
private const val SEARCH_INDEX_PAGE_SIZE = 50

/** 大 size 单页请求：应对“服务端忽略 page 参数、但尊重 size”的情况（歌手/专辑等小目录）。 */
private const val LARGE_CATALOG_SIZE = 10_000

/** 歌曲目录的大 size 单页请求（过大可能导致单次响应过重，歌曲用 1000）。 */
private const val TRACK_LARGE_PAGE_SIZE = 1_000

/** 搜索单类目最大扫描页数（200 条/页，最多 10 万条），纯属防御性上限，防止异常服务端导致死循环。 */
private const val SEARCH_MAX_PAGES = 500

/** 歌曲搜索索引（预计算拼音）在本地 cache_index 表中的键名。 */
private const val SEARCH_TRACK_INDEX_KEY = "search_track_index_v1"

/** 歌曲搜索索引的刷新周期：超过该时长后在后台静默重建（不阻塞搜索）。 */
private const val SEARCH_INDEX_TTL_MS = 24L * 60 * 60 * 1000

/** 客户端本地搜索结果，按歌单 / 歌手 / 专辑 / 歌曲分类。 */
data class SearchResults(
    val tracks: List<Track> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val albums: List<Album> = emptyList(),
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && artists.isEmpty() && playlists.isEmpty() && albums.isEmpty()
}

/** 基于汉字拼音的搜索匹配器，支持中文子串、拼音全拼与拼音首字母。 */
private class SearchMatcher(private val query: String) {
    private val qLower = query.lowercase()
    private val qPinyin = PinyinIndex.of(query).pinyinCompact
    private val qInitials = PinyinIndex.of(query).initials

    /** 歌曲只按歌名匹配：歌手命中进歌手区、专辑命中进专辑区，避免“歌手结果混进歌曲区”。 */
    fun matchesTrack(track: Track): Boolean = matches(track.title)

    fun matchesArtist(artist: Artist): Boolean = matches(artist.name)

    /** 专辑只按专辑名匹配（不匹配歌手名）。 */
    fun matchesAlbum(album: Album): Boolean = matches(album.name)

    fun matchesName(name: String): Boolean = matches(name)

    /** 基于预计算拼音的歌曲索引条目匹配（不重复转拼音，毫秒级）。 */
    fun matchesTrackEntry(entry: TrackSearchEntry): Boolean =
        matchesPrecomputed(entry.name, entry.namePinyin, entry.nameInitials)

    private fun matchesPrecomputed(lower: String, pinyin: String, initials: String): Boolean {
        if (lower.contains(qLower)) return true
        if (qPinyin.isNotEmpty() && pinyin.contains(qPinyin)) return true
        if (qInitials.isNotEmpty() && initials.contains(qInitials)) return true
        return false
    }

    /** 预计算拼音的排序器：前缀优先、其次包含，避免排序时重复转拼音。 */
    val trackEntryRank: Comparator<TrackSearchEntry> =
        compareByDescending<TrackSearchEntry> { e ->
            when {
                e.name.startsWith(qLower) -> 3
                e.namePinyin.startsWith(qPinyin) && qPinyin.isNotEmpty() -> 2
                e.nameInitials.startsWith(qInitials) && qInitials.isNotEmpty() -> 2
                e.name.contains(qLower) -> 1
                else -> 0
            }
        }.thenBy { it.name }

    private fun matches(text: String): Boolean {
        if (text.isEmpty()) return false
        val idx = PinyinIndex.of(text.lowercase())
        if (idx.lower.contains(qLower)) return true
        if (qPinyin.isNotEmpty() && idx.pinyinCompact.contains(qPinyin)) return true
        if (qInitials.isNotEmpty() && idx.initials.contains(qInitials)) return true
        return false
    }

    val trackRank: Comparator<Track> = rankBy(Track::title)
    val artistRank: Comparator<Artist> = rankBy(Artist::name)
    val albumRank: Comparator<Album> = rankBy(Album::name)
    val playlistRank: Comparator<Playlist> = rankBy(Playlist::name)

    private fun <T> rankBy(selector: (T) -> String): Comparator<T> = compareByDescending<T> { item ->
        val n = selector(item).lowercase()
        val idx = PinyinIndex.of(n)
        when {
            n.startsWith(qLower) -> 3
            idx.pinyinCompact.startsWith(qPinyin) && qPinyin.isNotEmpty() -> 2
            idx.initials.startsWith(qInitials) && qInitials.isNotEmpty() -> 2
            n.contains(qLower) -> 1
            else -> 0
        }
    }.thenBy { selector(it) }
}

/**
 * 名称的拼音索引：小写原文、全拼（无空格无音调）、首字母。
 * 使用纯 Java 的 pinyin4j 完成汉字转拼音（无原生代码、无原生崩溃风险），
 * 单字符转拼音失败/非汉字时跳过；拼音结果按名称缓存。
 */
private class PinyinIndex(
    val lower: String,
    val pinyinCompact: String,
    val initials: String,
) {
    companion object {
        // 拼音结果缓存，带容量上限（LRU），防止长时间使用内存无限增长
        private val cache = object : LinkedHashMap<String, PinyinIndex>(4_096, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PinyinIndex>): Boolean =
                size > 30_000
        }

        private val format: net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat by lazy {
            net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat().apply {
                toneType = net.sourceforge.pinyin4j.format.HanyuPinyinToneType.WITHOUT_TONE
                vCharType = net.sourceforge.pinyin4j.format.HanyuPinyinVCharType.WITH_V
                caseType = net.sourceforge.pinyin4j.format.HanyuPinyinCaseType.LOWERCASE
            }
        }

        fun of(text: String): PinyinIndex {
            val lower = text.lowercase()
            synchronized(cache) {
                return cache.getOrPut(lower) { build(lower) }
            }
        }

        private fun build(lower: String): PinyinIndex {
            val compact = StringBuilder()
            val initials = StringBuilder()
            for (ch in lower) {
                if (ch in 'a'..'z' || ch in '0'..'9') {
                    compact.append(ch)
                    initials.append(ch)
                    continue
                }
                if (ch == ' ') continue
                val py = runCatching {
                    net.sourceforge.pinyin4j.PinyinHelper.toHanyuPinyinStringArray(ch, format)
                }.getOrNull()?.firstOrNull()
                if (py != null && py.isNotEmpty()) {
                    compact.append(py)
                    initials.append(py[0])
                }
            }
            return PinyinIndex(lower, compact.toString(), initials.toString())
        }
    }
}

/**
 * 歌曲搜索的内存索引条目：歌名与预计算的拼音一次成型，搜索时只做纯字符串过滤。
 */
private data class TrackSearchEntry(
    val track: Track,
    val name: String,
    val namePinyin: String,
    val nameInitials: String,
)

/** 歌曲搜索索引的磁盘持久化格式（写入 cache_index 表，跨启动秒级恢复）。 */
@Serializable
private data class TrackSearchIndexDto(
    val version: Int,
    val tracks: List<TrackSearchEntryDto>,
)

@Serializable
private data class TrackSearchEntryDto(
    val guid: String,
    val title: String,
    val artistName: String?,
    val albumName: String?,
    val coverId: String?,
    val durationMs: Long?,
    val isCue: Boolean,
    val accessStatus: Int?,
    val audioFormat: String?,
    val namePinyin: String,
    val nameInitials: String,
)

internal data class ArtworkBounds(val width: Int, val height: Int)

internal fun TrackDto.toFavoriteDomain(): Track = toDomain().copy(isFavorite = true)

data class FavoriteLibraryState(
    val namespace: String? = null,
    val statuses: Map<String, Boolean> = emptyMap(),
    val pending: Set<String> = emptySet(),
    val revision: Long = 0L,
    val error: AppError? = null,
)

internal data class FavoriteMutation(
    val namespace: String,
    val trackGuid: String,
    val confirmed: Boolean,
    val desired: Boolean,
)

internal fun FavoriteLibraryState.bindNamespace(namespace: String): FavoriteLibraryState =
    if (this.namespace == namespace) this else FavoriteLibraryState(namespace = namespace)

internal fun FavoriteLibraryState.observe(trackGuid: String, favorite: Boolean): FavoriteLibraryState =
    if (trackGuid in pending) this else copy(statuses = statuses + (trackGuid to favorite))

internal fun FavoriteLibraryState.beginMutation(
    trackGuid: String,
    fallbackFavorite: Boolean,
): Pair<FavoriteLibraryState, FavoriteMutation> {
    val boundNamespace = checkNotNull(namespace)
    val confirmed = statuses[trackGuid] ?: fallbackFavorite
    val mutation = FavoriteMutation(boundNamespace, trackGuid, confirmed, !confirmed)
    return copy(
        statuses = statuses + (trackGuid to mutation.desired),
        pending = pending + trackGuid,
        error = null,
    ) to mutation
}

internal fun FavoriteLibraryState.complete(mutation: FavoriteMutation): FavoriteLibraryState =
    if (namespace != mutation.namespace) this else copy(
        statuses = statuses + (mutation.trackGuid to mutation.desired),
        pending = pending - mutation.trackGuid,
        revision = revision + 1L,
        error = null,
    )

internal fun FavoriteLibraryState.rollback(
    mutation: FavoriteMutation,
    error: AppError? = this.error,
): FavoriteLibraryState = if (namespace != mutation.namespace) this else copy(
    statuses = statuses + (mutation.trackGuid to mutation.confirmed),
    pending = pending - mutation.trackGuid,
    error = error,
)

internal fun decodeLyrics(response: LyricListDto): Pair<LyricDocument?, SyncedLyrics?> {
    val selected = response.list.firstOrNull { it.guid == response.preferred }
        ?: response.list.firstOrNull { it.isLRC }
        ?: response.list.firstOrNull()
    val document = selected?.toDomain()
    val syncedLyrics = document?.let { parseLyrics(it.content) }?.takeIf(SyncedLyrics::hasUsableLines)
    return document to syncedLyrics
}

internal fun isValidArtworkBytes(bytes: ByteArray): Boolean = isValidArtworkBytes(
    bytes = bytes,
    readBounds = { encoded ->
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)
        ArtworkBounds(options.outWidth, options.outHeight)
    },
    decodeSampled = { encoded, sample ->
        BitmapFactory.decodeByteArray(
            encoded,
            0,
            encoded.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )?.let { bitmap ->
            bitmap.recycle()
            true
        } ?: false
    },
)

internal fun isValidArtworkBytes(
    bytes: ByteArray,
    readBounds: (ByteArray) -> ArtworkBounds?,
    decodeSampled: (ByteArray, Int) -> Boolean,
): Boolean {
    if (bytes.isEmpty() || bytes.size > MAX_ARTWORK_DOWNLOAD_BYTES) return false
    val bounds = runCatching { readBounds(bytes) }.getOrNull() ?: return false
    if (
        bounds.width !in 1..MAX_ARTWORK_EDGE ||
        bounds.height !in 1..MAX_ARTWORK_EDGE ||
        bounds.width.toLong() * bounds.height > MAX_ARTWORK_PIXELS
    ) {
        return false
    }

    var sample = 1
    while (
        bounds.width / sample > ARTWORK_VALIDATION_LONG_EDGE ||
        bounds.height / sample > ARTWORK_VALIDATION_LONG_EDGE
    ) {
        sample *= 2
    }
    return runCatching { decodeSampled(bytes, sample) }.getOrDefault(false)
}

class MusicRepository internal constructor(
    context: Context,
    private val session: SessionRepository,
    private val preferences: AppPreferences,
    private val localStore: LocalStore,
    metadataCapacityBytes: Int,
    private val repositoryScope: CoroutineScope,
    onlineLyricsMatcher: suspend (LyricsMatchRequest) -> LyricsMatchResult,
) {
    constructor(
        context: Context,
        session: SessionRepository,
        preferences: AppPreferences,
        localStore: LocalStore,
    ) : this(
        context = context,
        session = session,
        preferences = preferences,
        localStore = localStore,
        metadataCapacityBytes = METADATA_CAPACITY_BYTES,
        repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        onlineLyricsMatcher = defaultOnlineLyricsMatcher(),
    )

    private val responses = SerializedResponseCache(metadataCapacityBytes, repositoryScope)
    private val onlineLyricsResolver = OnlineLyricsResolver(
        localStore = localStore,
        responses = responses,
        namespace = session::cacheNamespace,
        matcher = onlineLyricsMatcher,
    )
    private val favoriteMutationMutex = Mutex()

    /**
     * 当前生效的协议数据源（非飞牛平台）。
     *
     * 为 null 时走原有飞牛链路——**行为与之前完全一致**，这是安全的回退点；
     * 非 null 时下面各方法的开头会直接返回该平台的数据，于是首页/歌单/播放/搜索
     * 都会自然变成当前平台的内容，UI 一行都不用改。
     */
    @Volatile
    private var protocolSource: ProtocolLibrarySource? = null

    val activeProtocolSource: ProtocolLibrarySource? get() = protocolSource

    fun setProtocolSource(source: ProtocolLibrarySource?) {
        protocolSource = source
        // 换平台/换账号后旧的索引与缓存都不再有效
        searchTrackEntries = null
        searchArtists = null
        searchAlbums = null
        indexBuildJob = null
        indexBuildNamespace = null
    }
    /** 正在进行的索引构建任务；同一 namespace 只构建一次，所有等待方共享同一 Job。 */
    private var indexBuildJob: Job? = null
    private var indexBuildNamespace: String? = null
    /** 歌曲搜索索引（预计算拼音，内存），按登录账号缓存；登录后由 warmSearchIndex() 后台构建，搜索时按需补齐。 */
    private var searchTrackEntries: Pair<String, List<TrackSearchEntry>>? = null
    /** 全量歌手/专辑搜索索引，按登录账号缓存；登录后由 warmSearchIndex() 后台预热，搜索时按需补齐。 */
    private var searchArtists: Pair<String, List<Artist>>? = null
    private var searchAlbums: Pair<String, List<Album>>? = null
    /** 最近一次搜索的诊断信息（查询、索引规模、命中数、近似候选），用于排查“搜不到”；由 App 层写入崩溃日志文件。 */
    @Volatile
    private var lastSearchDiagnostics: String? = null
    private val _favoriteState = MutableStateFlow(FavoriteLibraryState())
    val favoriteState: StateFlow<FavoriteLibraryState> = _favoriteState.asStateFlow()
    private val artworkCache = ArtworkCache(
        root = context.cacheDir.resolve("artwork"),
        memoryCapacityBytes = ARTWORK_MEMORY_CAPACITY_BYTES,
        diskBudgetBytes = { preferences.state.value.cacheBudget.artworkBytes },
        isValid = ::isValidArtworkBytes,
        scope = repositoryScope,
    )

    init {
        repositoryScope.launch { artworkCache.initialize() }
    }

    /**
     * 返回服务器上的全部歌单。
     *
     * 默认走本地缓存（内存 + 磁盘兜底）；当 [forceRefresh] 为 true 时绕过已缓存数据，
     * 强制从服务器重新拉取并把结果重新落盘，用于“打开 App / 回到前台时同步歌单”。
     * 若此时网络不可用，仍会回退到磁盘缓存，保证离线时也能展示最近一次同步的歌单。
     */
    suspend fun playlists(forceRefresh: Boolean = false): List<Playlist> = protocolSource?.playlists() ?: cachedIndex<List<PlaylistDto>, List<Playlist>>(
        key = "playlists",
        forceRefresh = forceRefresh,
        fetch = { enrichPlaylistCounts(session.authenticated { it.playlists() }) },
    ) { list -> list.map(PlaylistDto::toDomain) }

    /** 歌单列表接口不返回歌曲数，逐歌单并发拉取详情补齐 trackCount；失败则保留 null（界面显示 0）。 */
    private suspend fun enrichPlaylistCounts(dtos: List<PlaylistDto>): List<PlaylistDto> {
        if (dtos.isEmpty()) return dtos
        val enriched = mutableListOf<PlaylistDto>()
        coroutineScope {
            for (chunk in dtos.chunked(6)) {
                enriched += chunk.map { dto ->
                    async {
                        runCatching { session.authenticated { it.playlist(dto.guid).trackCount } }
                            .getOrNull()?.let { count -> dto.copy(trackCount = count) } ?: dto
                    }
                }.awaitAll()
            }
        }
        return enriched
    }

    suspend fun playlist(guid: String): Playlist = protocolSource?.playlist(guid) ?: cachedIndex<PlaylistDetailDto, Playlist>(
        key = "playlist:$guid",
        fetch = { session.authenticated { it.playlist(guid) } },
    ) { it.toDomain() }

    suspend fun playlistTracks(guid: String, page: Int) = protocolSource?.playlistTracks(guid, page) ?: cachedPage<TrackDto, Track>(
        sourceKey = "playlist:$guid",
        page = page,
        fetch = { session.authenticated { it.playlistTracks(guid, page) } },
    ) { it.toDomain() }.also(::observeFavoriteTracks)

    suspend fun artists(page: Int, size: Int = 50) = protocolSource?.artists(page, size) ?: cachedPage<ArtistDto, Artist>(
        sourceKey = sizedPageSourceKey("artists", size),
        page = page,
        pageSize = size,
        fetch = { session.authenticated { it.artists(page, size) } },
    ) { it.toDomain() }

    suspend fun artist(guid: String): Artist = protocolSource?.artist(guid) ?: cachedIndex<ArtistDto, Artist>(
        key = "artist:$guid",
        fetch = { session.authenticated { it.artist(guid) } },
    ) { it.toDomain() }

    suspend fun artistTracks(guid: String, page: Int) = protocolSource?.artistTracks(guid, page) ?: cachedPage<TrackDto, Track>(
        sourceKey = "artist-tracks:$guid",
        page = page,
        fetch = { session.authenticated { it.artistTracks(guid, page) } },
    ) { it.toDomain() }.also(::observeFavoriteTracks)

    suspend fun artistAlbums(guid: String, page: Int) = protocolSource?.artistAlbums(guid, page) ?: cachedPage<AlbumDto, Album>(
        sourceKey = "artist-albums:$guid",
        page = page,
        fetch = { session.authenticated { it.artistAlbums(guid, page) } },
    ) { it.toDomain() }

    suspend fun albums(page: Int, size: Int = 50) = protocolSource?.albums(page, size) ?: cachedPage<AlbumDto, Album>(
        sourceKey = sizedPageSourceKey("albums", size),
        page = page,
        pageSize = size,
        fetch = { session.authenticated { it.albums(page, size) } },
    ) { it.toDomain() }

    suspend fun album(guid: String): Album = protocolSource?.album(guid) ?: cachedIndex<AlbumDto, Album>(
        key = "album:$guid",
        fetch = { session.authenticated { it.album(guid) } },
    ) { it.toDomain() }

    suspend fun albumTracks(guid: String, page: Int) = protocolSource?.albumTracks(guid, page) ?: cachedPage<TrackDto, Track>(
        sourceKey = "album-tracks:$guid",
        page = page,
        fetch = { session.authenticated { it.albumTracks(guid, page) } },
    ) { it.toDomain() }.also(::observeFavoriteTracks)

    suspend fun allTracks(page: Int) = protocolSource?.allTracks(page) ?: cachedPage<TrackDto, Track>(
        sourceKey = "all-tracks",
        page = page,
        fetch = { session.authenticated { it.allTracks(page) } },
    ) { it.toDomain() }.also(::observeFavoriteTracks)

    suspend fun favoriteTracks(page: Int): Page<Track> {
        protocolSource?.let { return it.favoriteTracks(page, 100) }
        val namespace = session.cacheNamespace()
        val response = session.authenticated { it.favoriteTracks(page, FAVORITE_PAGE_SIZE) }
        val result = Page(
            items = response.list.map(TrackDto::toFavoriteDomain),
            page = page,
            pageSize = FAVORITE_PAGE_SIZE,
            total = response.total,
            sort = FAVORITE_SORT,
        )
        observeFavoriteTracks(result, namespace)
        return result
    }

    suspend fun toggleFavorite(trackGuid: String, fallbackFavorite: Boolean): Result<Boolean> =
        favoriteMutationMutex.withLock {
            val namespace = session.cacheNamespace()
            bindFavoriteNamespace(namespace)
            val (optimisticState, mutation) = _favoriteState.value.beginMutation(trackGuid, fallbackFavorite)
            _favoriteState.value = optimisticState
            try {
                session.authenticated { api ->
                    if (mutation.desired) api.createFavorite(trackGuid) else api.deleteFavorite(trackGuid)
                }
                _favoriteState.update { it.complete(mutation) }
                Result.success(mutation.desired)
            } catch (cause: CancellationException) {
                _favoriteState.update { it.rollback(mutation) }
                throw cause
            } catch (cause: Exception) {
                val error = (cause as? AppException)?.error ?: AppError.Unknown(cause.message)
                _favoriteState.update { it.rollback(mutation, error) }
                Result.failure(cause)
            }
        }

    fun clearFavoriteState() {
        _favoriteState.value = FavoriteLibraryState()
    }

    suspend fun queuePage(source: QueueSource, page: Int): Page<Track> = when (source) {
        is QueueSource.Playlist -> playlistTracks(source.guid, page)
        is QueueSource.Artist -> artistTracks(source.guid, page)
        is QueueSource.Album -> albumTracks(source.guid, page)
        is QueueSource.LibraryAllTracks -> allTracks(page)
        is QueueSource.Favorites -> favoriteTracks(page)
    }

    suspend fun sharedLibraries(): List<SharedLibrary> {
        // 协议数据源没有"共享库"概念，直接返回空，避免走飞牛接口
        if (protocolSource != null) return emptyList()
        return cachedIndex<List<SharedLibraryDto>, List<SharedLibrary>>(
            key = "shared-libraries",
            fetch = { session.authenticated { it.sharedLibraries() } },
        ) { list -> list.map(SharedLibraryDto::toDomain) }
    }

    suspend fun trackMetadata(trackGuid: String): Track = protocolSource?.trackOrNull(trackGuid) ?: cachedIndex<TrackMetadataDto, Track>(
        key = "track-metadata:$trackGuid",
        fetch = { session.authenticated { it.metadata(trackGuid) } },
    ) { it.toDomain() }.also(::observeFavoriteTrack)

    suspend fun currentTrackMetadata(trackGuid: String): CurrentResourceResult<Track> = currentResource {
        withCurrentResourceRetry { trackMetadata(trackGuid) }
    }

    suspend fun prepare(track: Track): PlaybackTrack {
        protocolSource?.let { return it.prepare(track) }
        if (track.accessStatus != null && track.accessStatus != 0) throw AppException(AppError.UnavailableTrack)
        val refreshed = trackMetadata(track.guid.value)
        if (refreshed.isCue) throw AppException(AppError.TranscodeUnavailable)
        val api = session.requireApi()
        return PlaybackTrack(
            refreshed,
            api.streamUrl(refreshed.guid.value).toString(),
            refreshed.coverId?.let { api.coverUrl(it, CoverVariant.Player.width).toString() },
        )
    }

    fun prepareQueue(tracks: List<Track>): List<PlaybackTrack> {
        protocolSource?.let { return it.queue(tracks) }
        val api = session.requireApi()
        return tracks.asSequence()
            .filter { it.accessStatus == null || it.accessStatus == 0 }
            .filterNot(Track::isCue)
            .take(250)
            .map { track ->
                PlaybackTrack(
                    track,
                    api.streamUrl(track.guid.value).toString(),
                    track.coverId?.let { api.coverUrl(it, CoverVariant.Player.width).toString() },
                )
            }
            .toList()
    }

    suspend fun lyrics(trackGuid: String): Pair<LyricDocument?, SyncedLyrics?> {
        protocolSource?.let { return it.lyrics(trackGuid, "", "") }
        return decodeLyrics(lyricResponse(trackGuid))
    }

    private suspend fun firstPartyCurrentLyrics(trackGuid: String): CurrentResourceResult<CurrentLyrics> = currentResource {
        val (document, syncedLyrics) = withCurrentResourceRetry { lyrics(trackGuid) }
        document?.takeIf { it.content.isNotBlank() }?.let { CurrentLyrics(it, syncedLyrics) }
    }

    suspend fun currentLyrics(
        track: Track,
        onlineMatchingEnabled: Boolean = preferences.state.value.onlineLyricsMatchingEnabled,
    ): CurrentResourceResult<CurrentLyrics> = resolveLyricsWithFallback(
        onlineMatchingEnabled = onlineMatchingEnabled,
        online = { onlineLyricsResolver.resolve(track) },
        firstParty = { firstPartyCurrentLyrics(track.guid.value) },
    )

    suspend fun startRoam(): RoamWindow? {
        val namespace = session.cacheNamespace()
        return session.authenticated { it.roamStart(session.deviceId) }?.let {
            RoamWindow(null, it.current.toDomain(), it.next?.toDomain())
        }.also { observeFavoriteWindow(it, namespace) }
    }

    suspend fun nextRoam(roamId: String): RoamWindow {
        val namespace = session.cacheNamespace()
        return session.authenticated { it.roamNext(session.deviceId, roamId).toDomain() }
            .also { observeFavoriteWindow(it, namespace) }
    }

    suspend fun previousRoam(roamId: String): RoamWindow {
        val namespace = session.cacheNamespace()
        return session.authenticated { it.roamPrevious(session.deviceId, roamId).toDomain() }
            .also { observeFavoriteWindow(it, namespace) }
    }

    suspend fun artwork(coverId: String, variant: CoverVariant): ByteArray? = try {
        protocolSource?.let { return it.artwork(coverId, variant) }
        loadArtwork(coverId, variant)
    } catch (cause: CancellationException) {
        throw cause
    } catch (_: AppException) {
        null
    }

    suspend fun currentArtwork(
        coverId: String,
        variant: CoverVariant,
    ): CurrentResourceResult<ByteArray> = currentResource {
        withCurrentResourceRetry { loadArtwork(coverId, variant) }
    }

    suspend fun invalidateNamespace(namespace: String, includeEssential: Boolean = false) {
        responses.invalidateNamespace(namespace)
        artworkCache.clearNamespace(namespace)
        localStore.clearNamespace(namespace, includeEssential)
    }

    suspend fun clearLocalNamespace(includeEssential: Boolean) {
        val namespace = runCatching(session::cacheNamespace).getOrNull() ?: return
        invalidateNamespace(namespace, includeEssential)
    }

    suspend fun clearArtwork() = artworkCache.clearAll()

    suspend fun clearAllEvictableCaches() {
        responses.invalidateAll()
        artworkCache.clearAll()
        localStore.clearAllEvictable()
    }

    suspend fun cacheUsage(): CacheUsage = CacheUsage(
        artworkBytes = artworkCache.usageBytes(),
        indexBytes = localStore.physicalBytes(),
    )

    suspend fun applyArtworkBudget() = artworkCache.applyBudget()

    /**
     * 客户端本地搜索：服务端 API 未提供搜索端点。
     * 歌手、专辑、歌单数量少，登录后后台建立全量内存索引（warmSearchIndex），搜索时直接过滤，保证完整覆盖；
     * 歌曲数量大，采用“流式分页匹配”——逐页拉取并即时匹配、只保留命中项，内存占用只与命中数量有关。
     * 歌曲扫描带自适应回退：先用大分页（200 条/页）快速扫描；若服务端在该分页大小下分页异常（整页重复），
     * 自动改用与浏览页相同的小分页（50 条/页）重扫，保证覆盖完整。
     * 匹配规则（不区分大小写），且严格“哪一项命中进哪一项”：
     *  1) 歌曲区只按歌名匹配（中文、英文、拼音全拼、拼音首字母）；
     *  2) 歌手区只按歌手名匹配；专辑区只按专辑名匹配；歌单区只按歌单名匹配；
     *  3) 搜歌手名（如 zzh）时歌手进歌手区，其歌曲/专辑不会混入歌曲区/专辑区。
     * 结果按“前缀优先、其次包含”排序。
     */
    suspend fun search(query: String): SearchResults {
        protocolSource?.let { return it.search(query) }
        val q = query.trim().lowercase()
        if (q.isEmpty()) return SearchResults()
        return try {
            withContext(Dispatchers.Default) {
                ensureSearchIndex()
                val matcher = SearchMatcher(q)
                val artistIndex = searchArtists ?: ("" to emptyList())
                val albumIndex = searchAlbums ?: ("" to emptyList())
                val trackIndex = searchTrackEntries ?: ("" to emptyList())
                // 歌曲：优先走内存预计算拼音索引（无网络、无实时转拼音，毫秒级）；
                // 索引不可用时回退到流式分页扫描（多策略兜底）。
                val trackHits = if (trackIndex.second.isNotEmpty()) {
                    trackIndex.second.filter { matcher.matchesTrackEntry(it) }.map { it.track }
                } else {
                    scanTracks(matcher).hits
                }
                val artistHits = artistIndex.second.filter { matcher.matchesArtist(it) }
                val albumHits = albumIndex.second.filter { matcher.matchesAlbum(it) }
                val playlistHits = playlists().filter { matcher.matchesName(it.name) }
                lastSearchDiagnostics = searchDiagnostics(
                    q = q,
                    artistIndex = artistIndex,
                    albumIndex = albumIndex,
                    trackIndexSize = trackIndex.second.size,
                    trackHits = trackHits,
                    artistHits = artistHits,
                    albumHits = albumHits,
                    playlistHits = playlistHits,
                )
                SearchResults(
                    tracks = trackHits.sortedWith(matcher.trackRank),
                    artists = artistHits.sortedWith(matcher.artistRank),
                    albums = albumHits.sortedWith(matcher.albumRank),
                    playlists = playlistHits.sortedWith(matcher.playlistRank),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // 之前这里静默返回空结果，界面表现为"搜索没反应"，无从定位。
            // 把原因写进诊断，搜索页在无结果时会显示出来。
            lastSearchDiagnostics = "搜索失败：${t::class.simpleName} · ${t.message.orEmpty()}"
            SearchResults()
        }
    }

    /** 最近一次搜索的诊断信息；供 App 层写入崩溃日志文件，方便反馈“为什么搜不到”。 */
    fun lastSearchDiagnostics(): String? = lastSearchDiagnostics

    /**
     * 登录后后台预热搜索索引：
     * 1) 歌手、专辑全量载入内存（多策略兜底，保证完整覆盖）；
     * 2) 后台预取歌曲目录页到磁盘缓存，让首次搜索也命中缓存、速度接近本地索引。
     * 幂等；账号切换时自动重建。
     */
    suspend fun warmSearchIndex() {
        withContext(Dispatchers.Default) {
            // 登录后一次性构建完整搜索索引（歌手/专辑/歌曲+预计算拼音），
            // 首次搜索即命中内存索引，无需再逐页扫描与实时转拼音。
            ensureSearchIndex()
        }
    }

    /**
     * 保证搜索索引就绪；返回 true 表示索引已可用。
     *
     * 与原始回声台（v1.1.2 / v1.2.2）一致：**不留超时、等待完整索引构建完成**。
     * 之前的实现用 20 秒超时兜底，大曲库首搜会在索引还没建好时提前放弃，退回只扫前 15 页，
     * 结果就是「什么都搜不到」或「一直转圈」。现在挂起等待构建完成，保证首搜覆盖完整；
     * 构建任务跑在独立的 [repositoryScope] 里，输入即搜频繁取消搜索也不会中断它；
     * 建好后（内存 + 磁盘缓存）后续搜索毫秒级返回。
     */
    private suspend fun ensureSearchIndex(): Boolean {
        val namespace = runCatching(session::cacheNamespace).getOrNull() ?: return false
        if (searchIndexReady(namespace)) return true
        val job = synchronized(this) {
            if (indexBuildNamespace != namespace || indexBuildJob?.isActive != true) {
                indexBuildNamespace = namespace
                indexBuildJob = repositoryScope.launch { buildSearchIndexLocked(namespace) }
            }
            indexBuildJob!!
        }
        // 等待完整构建完成（不设超时）；UI 侧保留一个很长（120s）的兜底超时以防极端卡死。
        job.join()
        return searchIndexReady(namespace)
    }

    private fun searchIndexReady(namespace: String): Boolean =
        searchArtists?.first == namespace &&
            searchAlbums?.first == namespace &&
            searchTrackEntries?.first == namespace

    private suspend fun buildSearchIndexLocked(namespace: String) {
        // 三步各自容错：某一类拉取失败不应该让整个搜索索引变空
        searchArtists = namespace to runCatching {
            loadFullCatalog(pageLoader = { page, size -> artists(page, size) }, idOf = { it.guid.value })
        }.getOrDefault(emptyList())
        searchAlbums = namespace to runCatching {
            loadFullCatalog(pageLoader = { page, size -> albums(page, size) }, idOf = { it.guid.value })
        }.getOrDefault(emptyList())
        searchTrackEntries = namespace to runCatching {
            buildTrackSearchEntries(namespace)
        }.getOrDefault(emptyList())
    }

    /** 歌曲搜索索引条目：构建或从磁盘缓存恢复（优先缓存，跨启动秒级可用）。 */
    private suspend fun buildTrackSearchEntries(namespace: String): List<TrackSearchEntry> {
        // 1) 磁盘缓存优先：上次构建好的预计算拼音索引直接恢复，搜索立刻可用
        val cached = runCatching { localStore.index(namespace, SEARCH_TRACK_INDEX_KEY) }.getOrNull()
        if (cached != null && cached.payload.isNotBlank()) {
            val loaded = runCatching {
                ApiDecoder.json.decodeFromString<TrackSearchIndexDto>(cached.payload)
                    .tracks.mapNotNull { it.toEntry() }
            }.getOrNull()
            if (loaded != null && loaded.isNotEmpty()) {
                // 2) 超过刷新周期则在后台静默重建（不阻塞本次搜索）
                val stale = now() - cached.accessedAt > SEARCH_INDEX_TTL_MS
                if (stale) {
                    repositoryScope.launch {
                        runCatching { buildTrackSearchEntriesFresh(namespace) }
                    }
                }
                return loaded
            }
        }
        return buildTrackSearchEntriesFresh(namespace)
    }

    /** 从服务端全量拉取歌曲目录并构建预计算拼音索引，落盘 cache_index 供下次启动秒级恢复。 */
    private suspend fun buildTrackSearchEntriesFresh(namespace: String): List<TrackSearchEntry> {
        val tracks = loadFullCatalog(
            pageLoader = { page, size -> searchTracksPage(page, size) },
            idOf = { it.guid.value },
        )
        val entries = tracks.mapNotNull { it.toSearchEntry() }
        if (entries.isNotEmpty()) {
            val dto = TrackSearchIndexDto(
                version = 1,
                tracks = entries.map { it.toDto() },
            )
            runCatching {
                localStore.saveIndex(
                    CachedIndexEntity(
                        namespace = namespace,
                        indexKey = SEARCH_TRACK_INDEX_KEY,
                        payload = ApiDecoder.json.encodeToString(dto),
                        accessedAt = now(),
                    ),
                )
            }
        }
        return entries
    }

    private fun Track.toSearchEntry(): TrackSearchEntry? {
        if (title.isBlank()) return null
        val lower = title.lowercase()
        val idx = PinyinIndex.of(lower)
        return TrackSearchEntry(this, lower, idx.pinyinCompact, idx.initials)
    }

    private fun TrackSearchEntry.toDto(): TrackSearchEntryDto = TrackSearchEntryDto(
        guid = track.guid.value,
        title = track.title,
        artistName = track.artistName,
        albumName = track.albumName,
        coverId = track.coverId,
        durationMs = track.durationMs,
        isCue = track.isCue,
        accessStatus = track.accessStatus,
        audioFormat = track.audioFormat,
        namePinyin = namePinyin,
        nameInitials = nameInitials,
    )

    private fun TrackSearchEntryDto.toEntry(): TrackSearchEntry? {
        if (title.isBlank()) return null
        val track = Track(
            guid = TrackGuid(guid),
            title = title,
            artistName = artistName,
            albumName = albumName,
            coverId = coverId,
            durationMs = durationMs,
            isCue = isCue,
            accessStatus = accessStatus,
            audioFormat = audioFormat,
        )
        return TrackSearchEntry(track, title.lowercase(), namePinyin, nameInitials)
    }

    /** 歌曲搜索结果：hits 为命中项，pages 为策略 A 扫描页数（用于诊断）。 */
    private data class TrackScanResult(val hits: List<Track>, val pages: Int)

    /**
     * 歌曲搜索：流式匹配、只保留命中项（内存安全）。多策略兜底应对服务端分页怪癖：
     * A. 标准 0 起始大分页（200 条/页）快速扫描；若 A 未正常推进到空页（分页未前进），
     * B. 一次 1000 条/页大请求（服务端可能忽略 page 但尊重 size）；
     * C. 1 起始分页（50 条/页，部分服务端 page 从 1 开始且忽略 0）。
     * 各策略命中结果合并去重后返回。
     */
    private suspend fun scanTracks(matcher: SearchMatcher): TrackScanResult {
        val a = scanPages(
            pageLoader = { page, size -> searchTracksPage(page, size) },
            size = SEARCH_PAGE_SIZE,
            startPage = 0,
            idOf = { it.guid.value },
            matches = matcher::matchesTrack,
        )
        if (a.complete && a.pages > 1) return TrackScanResult(a.items, a.pages)
        val b = scanPages(
            pageLoader = { page, size -> searchTracksPage(page, size) },
            size = TRACK_LARGE_PAGE_SIZE,
            startPage = 0,
            idOf = { it.guid.value },
            matches = matcher::matchesTrack,
            maxPages = 1,
        )
        val c = scanPages(
            pageLoader = { page, size -> searchTracksPage(page, size) },
            size = SEARCH_INDEX_PAGE_SIZE,
            startPage = 1,
            idOf = { it.guid.value },
            matches = matcher::matchesTrack,
        )
        val seen = HashSet<String>()
        val out = ArrayList<Track>()
        for (item in a.items + b.items + c.items) {
            if (seen.add(item.guid.value)) out.add(item)
        }
        return TrackScanResult(out, a.pages)
    }

    /** 搜索专用歌曲分页；缓存键含分页大小，避免不同分页大小的缓存互相污染。 */
    private suspend fun searchTracksPage(page: Int, size: Int): Page<Track> = cachedPage<TrackDto, Track>(
        sourceKey = sizedPageSourceKey("search-tracks", size),
        page = page,
        pageSize = size,
        fetch = { session.authenticated { it.allTracks(page, size) } },
    ) { it.toDomain() }.also(::observeFavoriteTracks)

    /** 目录扫描结果：items 为保留项；complete=true 表示以“空页”正常结束（分页推进到末尾）；pages 为拉取页数。 */
    private data class CatalogScanResult<T>(val items: List<T>, val complete: Boolean, val pages: Int)

    /**
     * 逐页扫描目录：从 startPage 起按给定 size 逐页拉取，只保留匹配项（matches={true} 时全量保留）。
     * 终止条件：遇到空页、整页均为已见重复项（服务端分页未前进）或超过安全上限。
     * 注意：不依赖服务端 total——部分服务端不返回 total（或 total 缺省为页大小），
     * 若用 total 终止会误判“已拉完”而只扫第一页，这正是此前索引只有 50 条的根因。
     */
    private suspend fun <T> scanPages(
        pageLoader: suspend (page: Int, size: Int) -> Page<T>,
        size: Int,
        startPage: Int,
        idOf: (T) -> String,
        matches: (T) -> Boolean,
        maxPages: Int = SEARCH_MAX_PAGES,
    ): CatalogScanResult<T> {
        val out = ArrayList<T>()
        val seen = HashSet<String>()
        var page = startPage
        var pages = 0
        var stuck = false
        while (pages < maxPages) {
            val items = pageLoader(page, size).items
            pages += 1
            if (items.isEmpty()) break
            var newlySeen = 0
            for (item in items) {
                if (seen.add(idOf(item))) {
                    newlySeen += 1
                    if (matches(item)) out.add(item)
                }
            }
            // 整页都是重复项：服务端分页没有前进，停止扫描，避免无限循环
            if (newlySeen == 0) {
                stuck = true
                break
            }
            page += 1
        }
        return CatalogScanResult(out, complete = !stuck, pages = pages)
    }

    /**
     * 全量拉取目录（歌手/专辑等小列表）：多策略兜底，应对服务端各种分页怪癖：
     * A. 标准 0 起始分页；B. 若 A 未正常推进，一次大 size 请求（服务端可能忽略 page 但尊重 size）；
     * C. 尝试 1 起始分页（部分服务端 page 从 1 开始且忽略 0）。
     * 最后合并所有策略结果去重，保证覆盖尽可能完整。
     */
    private suspend fun <T> loadFullCatalog(
        pageLoader: suspend (page: Int, size: Int) -> Page<T>,
        idOf: (T) -> String,
    ): List<T> {
        val a = scanPages(pageLoader, SEARCH_INDEX_PAGE_SIZE, 0, idOf, { true })
        if (a.complete && a.pages > 1) return a.items
        val b = scanPages(pageLoader, LARGE_CATALOG_SIZE, 0, idOf, { true }, maxPages = 1)
        val c = scanPages(pageLoader, SEARCH_INDEX_PAGE_SIZE, 1, idOf, { true })
        val seen = HashSet<String>()
        val merged = ArrayList<T>()
        for (item in a.items + b.items + c.items) {
            if (seen.add(idOf(item))) merged.add(item)
        }
        return merged
    }

    /** 生成搜索诊断文本：查询、索引规模、命中数，以及“可能想找”的近似歌手（首字母前缀接近）。 */
    private fun searchDiagnostics(
        q: String,
        artistIndex: Pair<String, List<Artist>>,
        albumIndex: Pair<String, List<Album>>,
        trackIndexSize: Int,
        trackHits: List<Track>,
        artistHits: List<Artist>,
        albumHits: List<Album>,
        playlistHits: List<Playlist>,
    ): String = buildString {
        appendLine("查询：\"$q\"")
        appendLine("歌手索引 ${artistIndex.second.size} 条；专辑索引 ${albumIndex.second.size} 条")
        appendLine("歌曲索引 ${trackIndexSize} 条（内存预计算拼音）")
        appendLine("命中：歌曲 ${trackHits.size}、歌手 ${artistHits.size}、专辑 ${albumHits.size}、歌单 ${playlistHits.size}")
        if (q.isNotBlank() && artistHits.isEmpty()) {
            val qInit = PinyinIndex.of(q).initials
            val prefix = qInit.take(2)
            val near = artistIndex.second.asSequence()
                .filter { it.name.isNotBlank() }
                .filter { PinyinIndex.of(it.name.lowercase()).initials.startsWith(prefix) }
                .take(10)
                .map { "${it.name}(${PinyinIndex.of(it.name.lowercase()).initials})" }
                .toList()
            if (near.isNotEmpty()) {
                appendLine("首字母前缀为\"$prefix\"的歌手（最多 10 条）：${near.joinToString("、")}")
            } else {
                appendLine("索引中没有首字母前缀为\"$prefix\"的歌手；请确认该歌手是否在曲库中")
            }
        }
    }

    private fun observeFavoriteTracks(page: Page<Track>) {
        page.items.forEach(::observeFavoriteTrack)
    }

    private fun observeFavoriteTracks(page: Page<Track>, namespace: String) {
        page.items.forEach { observeFavoriteTrack(it, namespace) }
    }

    private fun observeFavoriteWindow(window: RoamWindow?, namespace: String) {
        window ?: return
        listOfNotNull(window.previous, window.current, window.next)
            .forEach { observeFavoriteTrack(it.track, namespace) }
    }

    private fun observeFavoriteTrack(track: Track) {
        val namespace = runCatching(session::cacheNamespace).getOrNull() ?: return
        observeFavoriteTrack(track, namespace)
    }

    private fun observeFavoriteTrack(track: Track, namespace: String) {
        if (runCatching(session::cacheNamespace).getOrNull() != namespace) return
        bindFavoriteNamespace(namespace)
        _favoriteState.update { it.observe(track.guid.value, track.isFavorite) }
    }

    private fun bindFavoriteNamespace(namespace: String) {
        if (_favoriteState.value.namespace != namespace) {
            _favoriteState.update { it.bindNamespace(namespace) }
        }
    }

    private suspend fun loadArtwork(coverId: String, variant: CoverVariant): ByteArray? {
        val namespace = session.cacheNamespace()
        return artworkCache.get(namespace, coverId, variant) {
            session.authenticated { it.cover(coverId, variant.width) }
        }
    }

    private suspend fun lyricResponse(trackGuid: String): LyricListDto {
        val namespace = session.cacheNamespace()
        val key = ResponseCacheKey(namespace, "lyric", trackGuid)
        var decodedResponse: LyricListDto? = null
        val payload = responses.getOrFetch(
            key = key,
            persist = { encoded ->
                bestEffort {
                    localStore.saveLyric(CachedLyricEntity(namespace, trackGuid, encoded, now()))
                }
            },
        ) {
            try {
                session.authenticated { it.lyrics(trackGuid) }
                    .also { decodedResponse = it }
                    .let(ApiDecoder.json::encodeToString)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: AppException) {
                if (cause.error != AppError.NetworkUnavailable) throw cause
                val cached = fallback { localStore.lyric(namespace, trackGuid) } ?: throw cause
                decodedResponse = validatePayload<LyricListDto>(cached.payload) ?: throw cause
                cached.payload
            }
        }
        return decodedResponse ?: ApiDecoder.json.decodeFromString(payload)
    }

    private suspend inline fun <reified Dto, Domain> cachedPage(
        sourceKey: String,
        page: Int,
        pageSize: Int = PAGE_SIZE,
        crossinline fetch: suspend () -> SortedPageListDto<Dto>,
        noinline transform: (Dto) -> Domain,
    ): Page<Domain> {
        val namespace = session.cacheNamespace()
        val key = ResponseCacheKey(namespace, "page", sourceKey, page)
        var decodedResponse: SortedPageListDto<Dto>? = null
        val payload = responses.getOrFetch(
            key = key,
            persist = { encoded ->
                bestEffort {
                    val response = decodedResponse
                        ?: ApiDecoder.json.decodeFromString<SortedPageListDto<Dto>>(encoded)
                    localStore.savePage(
                        CachedPageEntity(
                            namespace = namespace,
                            sourceKey = sourceKey,
                            page = page,
                            payload = encoded,
                            total = response.total,
                            sort = response.sort,
                            accessedAt = now(),
                        ),
                    )
                }
            },
        ) {
            try {
                fetch().also { decodedResponse = it }.let(ApiDecoder.json::encodeToString)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: AppException) {
                if (cause.error != AppError.NetworkUnavailable) throw cause
                val cached = fallback { localStore.page(namespace, sourceKey, page) } ?: throw cause
                decodedResponse = validatePayload<SortedPageListDto<Dto>>(cached.payload) ?: throw cause
                cached.payload
            }
        }
        return (decodedResponse ?: ApiDecoder.json.decodeFromString<SortedPageListDto<Dto>>(payload))
            .toDomainPage(page, pageSize, transform)
    }

    private suspend inline fun <reified Dto, Domain> cachedIndex(
        key: String,
        forceRefresh: Boolean = false,
        crossinline fetch: suspend () -> Dto,
        transform: (Dto) -> Domain,
    ): Domain {
        val namespace = session.cacheNamespace()
        val cacheKey = ResponseCacheKey(namespace, "index", key)
        var decodedResponse: Dto? = null
        val payload = responses.getOrFetch(
            key = cacheKey,
            isRetainedValid = { !forceRefresh },
            persist = { encoded ->
                bestEffort {
                    localStore.saveIndex(CachedIndexEntity(namespace, key, encoded, now()))
                }
            },
        ) {
            try {
                fetch().also { decodedResponse = it }.let(ApiDecoder.json::encodeToString)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: AppException) {
                if (cause.error != AppError.NetworkUnavailable) throw cause
                val cached = fallback { localStore.index(namespace, key) } ?: throw cause
                decodedResponse = validatePayload<Dto>(cached.payload) ?: throw cause
                cached.payload
            }
        }
        return transform(decodedResponse ?: ApiDecoder.json.decodeFromString(payload))
    }

    private suspend fun <T> currentResource(block: suspend () -> T?): CurrentResourceResult<T> = try {
        block()?.let { CurrentResourceResult.Ready(it) } ?: CurrentResourceResult.Absent
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: AppException) {
        when (cause.error) {
            AppError.NotFound, AppError.Empty -> CurrentResourceResult.Absent
            else -> CurrentResourceResult.Failure(cause.error, cause.isRetryableRequestFailure)
        }
    }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Exception) {
            // Room remains a fallback tier; a disk write failure must not fail a valid network response.
        }
    }

    private suspend fun <T> fallback(block: suspend () -> T): T? = try {
        block()
    } catch (cause: CancellationException) {
        throw cause
    } catch (_: Exception) {
        null
    }

    private inline fun <reified T> validatePayload(payload: String): T? = try {
        ApiDecoder.json.decodeFromString(payload)
    } catch (_: Exception) {
        null
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val METADATA_CAPACITY_BYTES = 8 * 1024 * 1024
        const val ARTWORK_MEMORY_CAPACITY_BYTES = 24 * 1024 * 1024
        const val PAGE_SIZE = 50
        const val FAVORITE_PAGE_SIZE = 50
        const val FAVORITE_SORT = "favoriteAt,desc"

        fun defaultOnlineLyricsMatcher(): suspend (LyricsMatchRequest) -> LyricsMatchResult {
            val client = okhttp3.OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .connectTimeout(1_200L, TimeUnit.MILLISECONDS)
                .readTimeout(1_800L, TimeUnit.MILLISECONDS)
                .writeTimeout(1_800L, TimeUnit.MILLISECONDS)
                .build()
            val coordinator = LyricsMatchCoordinator(DefaultLyricsSources.create(client))
            return coordinator::match
        }
    }
}

internal fun sizedPageSourceKey(sourceKey: String, size: Int): String {
    require(size > 0)
    return "$sourceKey:size=$size"
}

internal fun <Dto, Domain> SortedPageListDto<Dto>.toDomainPage(
    page: Int,
    pageSize: Int,
    transform: (Dto) -> Domain,
): Page<Domain> {
    require(pageSize > 0)
    return Page(list.map(transform), page, pageSize, total, sort)
}

internal suspend fun resolveLyricsWithFallback(
    onlineMatchingEnabled: Boolean,
    online: suspend () -> CurrentLyrics?,
    firstParty: suspend () -> CurrentResourceResult<CurrentLyrics>,
): CurrentResourceResult<CurrentLyrics> {
    if (!onlineMatchingEnabled) return firstParty()
    return try {
        online()?.let { CurrentResourceResult.Ready(it) } ?: firstParty()
    } catch (cause: CancellationException) {
        throw cause
    } catch (_: Exception) {
        firstParty()
    }
}
