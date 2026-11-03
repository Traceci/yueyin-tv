package com.yueyin.tv.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.yueyin.tv.core.model.Album
import com.yueyin.tv.core.model.AppError
import com.yueyin.tv.core.model.AppException
import com.yueyin.tv.core.model.Artist
import com.yueyin.tv.core.model.Page
import com.yueyin.tv.core.model.Playlist
import com.yueyin.tv.core.model.Track
import com.yueyin.tv.ui.FnColors
import com.yueyin.tv.ui.LibraryRetainedStateStore
import com.yueyin.tv.ui.LibraryRoute
import com.yueyin.tv.ui.RetainedPagedGridState
import com.yueyin.tv.ui.retainLoadedPage
import com.yueyin.tv.ui.shouldLoadInitialPage
import kotlinx.coroutines.launch

/**
 * 曲库的五个分段。
 *
 * 电视端把「专辑 / 歌手 / 歌曲 / 收藏 / 全部歌单」做成了五个**顶级路由**，靠左栏导航切换。
 * 手机竖屏没有左栏，五个顶级入口会撑爆底部四 Tab，所以合并成一个「曲库」+ 顶部分段条。
 *
 * 路由仍然保留五个（[LibraryRoute.Albums] 等），只是都落到本页并预选对应分段——
 * 这样首页的「全部 ›」可以直接跳到指定分段，不必再另造一套导航参数。
 */
internal enum class PhoneLibrarySegment(val label: String) {
    Albums("专辑"),
    Artists("歌手"),
    Playlists("歌单"),
    Tracks("歌曲"),
    Favorites("收藏"),
}

internal fun LibraryRoute.librarySegmentOrNull(): PhoneLibrarySegment? = when (this) {
    LibraryRoute.Albums -> PhoneLibrarySegment.Albums
    LibraryRoute.Artists -> PhoneLibrarySegment.Artists
    LibraryRoute.AllPlaylists -> PhoneLibrarySegment.Playlists
    LibraryRoute.AllTracks -> PhoneLibrarySegment.Tracks
    LibraryRoute.Favorites -> PhoneLibrarySegment.Favorites
    else -> null
}

/** 分页尺寸：两列网格一页 20 张 = 10 行，滚到底前就能接上下一页。 */
private const val GRID_PAGE_SIZE = 20

@Composable
internal fun PhoneLibraryPage(
    segment: PhoneLibrarySegment,
    playingTrackId: String?,
    onSelectSegment: (PhoneLibrarySegment) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onOpenArtist: (Artist) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
) {
    val deps = LocalPhoneDeps.current
    val scope = rememberCoroutineScope()
    val favoriteLibrary by deps.musicRepository.favoriteState.collectAsStateWithLifecycle()
    // 收藏切换在曲库这一层统一收口：歌曲与收藏两个列表都要用，
    // 各自写一遍的话，两处对「点了之后要不要刷新列表」的判断早晚会不一致。
    val toggleFavorite: (Track, Boolean) -> Unit = { track, current ->
        scope.launch { deps.musicRepository.toggleFavorite(track.guid.value, current, track) }
    }
    Column(Modifier.fillMaxSize()) {
        PhonePageTitle(
            title = "曲库",
            modifier = Modifier.padding(horizontal = PhoneSpace.screenHorizontal),
        )
        SegmentBar(selected = segment, onSelect = onSelectSegment)
        Box(Modifier.fillMaxSize().padding(horizontal = PhoneSpace.screenHorizontal)) {
            when (segment) {
                PhoneLibrarySegment.Albums -> PhonePagedGrid<Album>(
                    stateKey = "phone:grid:albums",
                    loader = { page -> deps.musicRepository.albums(page, GRID_PAGE_SIZE) },
                    key = { it.guid.value },
                    emptyTitle = "还没有专辑",
                    emptyCaption = "当前平台的曲库里没有专辑信息",
                ) { album -> PhoneAlbumCard(album = album, onClick = { onOpenAlbum(album) }) }

                PhoneLibrarySegment.Playlists -> PhonePagedGrid<Playlist>(
                    stateKey = "phone:grid:playlists",
                    loader = {
                        // 歌单接口一次返回全量，没有分页；包成单页 Page 复用同一套渲染，
                        // 不必为「唯一一种不分页的数据」再写一个几乎相同的网格。
                        deps.musicRepository.playlists().let { list ->
                            Page(items = list, page = 1, pageSize = list.size, total = list.size, sort = "")
                        }
                    },
                    key = { it.guid.value },
                    emptyTitle = "还没有歌单",
                    emptyCaption = "在音乐平台上建一个歌单，这里就会出现",
                ) { playlist -> PhonePlaylistCard(playlist = playlist, onClick = { onOpenPlaylist(playlist) }) }

                PhoneLibrarySegment.Artists -> PhonePagedGrid<Artist>(
                    stateKey = "phone:grid:artists",
                    loader = { page -> deps.musicRepository.artists(page, GRID_PAGE_SIZE) },
                    key = { it.guid.value },
                    emptyTitle = "还没有歌手",
                    emptyCaption = "当前平台的曲库里没有歌手信息",
                    // 歌手行是「圆形头像 + 名字 + 曲目数」的横向条，比专辑卡更吃宽度。
                    // 上限由窗口给出：竖屏 2 列保持原样，横屏/平板放宽到 3 列，
                    // 写死 2 会在 910dp 横屏上留下两条 400dp 的巨条。
                    maxColumns = LocalPhoneWindow.current.artistColumns,
                ) { artist -> PhoneArtistRow(artist = artist, onClick = { onOpenArtist(artist) }) }

                PhoneLibrarySegment.Tracks -> PhonePagedTracks(
                    stateKey = "phone:rows:tracks",
                    loader = { page -> deps.musicRepository.allTracks(page) },
                    emptyTitle = "还没有歌曲",
                    emptyCaption = "当前平台的曲库里没有可播放的曲目",
                    playingTrackId = playingTrackId,
                    onPlayTracks = onPlayTracks,
                    favoriteStatuses = favoriteLibrary.statuses,
                    onToggleFavorite = toggleFavorite,
                )

                PhoneLibrarySegment.Favorites -> PhonePagedTracks(
                    stateKey = "phone:rows:favorites",
                    loader = { page -> deps.musicRepository.favoriteTracks(page) },
                    emptyTitle = "还没有收藏",
                    emptyCaption = "在播放页点心形按钮，曲子会出现在这里",
                    playingTrackId = playingTrackId,
                    onPlayTracks = onPlayTracks,
                    trackFavorites = true,
                    favoriteStatuses = favoriteLibrary.statuses,
                    onToggleFavorite = toggleFavorite,
                )
            }
        }
    }
}

/**
 * 分段条。
 *
 * 两种排法：
 * - **窄屏**（竖屏手机）：横向滚动。五个中文标签在 390dp 上刚好卡在「放得下」与「放不下」
 *   的边界，系统字体放大到 1.3x 就一定会溢出。允许滚动比压缩字号安全——字不能为了排版变小。
 * - **宽屏**（横屏 / 平板）：等分铺满。横屏 810dp 内容区里若仍按固定间距排，
 *   五个标签只占左边三分之一，右边一大片空白，看着像没做完。
 */
@Composable
private fun SegmentBar(selected: PhoneLibrarySegment, onSelect: (PhoneLibrarySegment) -> Unit) {
    val evenSplit = LocalPhoneWindow.current.contentWidthDp >= 480
    if (evenSplit) {
        Row(Modifier.fillMaxWidth().padding(horizontal = PhoneSpace.screenHorizontal)) {
            PhoneLibrarySegment.entries.forEach { item ->
                SegmentItem(
                    label = item.label,
                    active = item == selected,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(item) },
                )
            }
        }
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = PhoneSpace.screenHorizontal),
            horizontalArrangement = Arrangement.spacedBy(PhoneSpace.xl),
        ) {
            PhoneLibrarySegment.entries.forEach { item ->
                SegmentItem(
                    label = item.label,
                    active = item == selected,
                    modifier = Modifier,
                    onClick = { onSelect(item) },
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(hairline()))
}

@Composable
private fun SegmentItem(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier.phoneTap(onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = if (active) FnColors.Text else FnColors.Muted,
            fontSize = PhoneType.Row,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.padding(vertical = 11.dp),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (active) FnColors.Coral else hairline(), RoundedCornerShape(2.dp)),
        )
    }
}

/** 分隔线：白 10%，与卡片描边同一档。 */
private fun hairline(): Color = Color.White.copy(alpha = 0.10f)

// ---------------------------------------------------------------------------
// 分页网格 / 分页列表
// ---------------------------------------------------------------------------

/**
 * 分页网格。
 *
 * 与电视端 `PagedCatalogPage` 的差异：
 * - 没有焦点管理（手机没有方向键，手指点到哪就是哪）；
 * - 不做「整行吸附」：竖屏滚动本身有惯性，露出半行反而提示「下面还有」；
 * - 列数随可用宽度反算：按最小卡宽 150dp + 12dp 间距得 2~6 列（见 `PhoneWindow.gridColumns`）。
 *
 * @param columns 固定列数，0 表示按窗口宽度自动
 * @param maxColumns 自动列数的上限，给「一行一个才好看」的内容（歌手）封顶
 */
@Composable
private fun <T> PhonePagedGrid(
    stateKey: String,
    loader: suspend (Int) -> Page<T>,
    key: (T) -> String,
    emptyTitle: String,
    emptyCaption: String?,
    columns: Int = 0,
    maxColumns: Int = Int.MAX_VALUE,
    item: @Composable (T) -> Unit,
) {
    val store = LocalPhoneStore.current
    val state = store.paged<T>(stateKey)
    val snapshot = state.snapshot
    val gridState = rememberLazyGridState()
    val load = makePagedLoader(store, state, loader, key)
    val autoColumns = LocalPhoneWindow.current.gridColumns
    val resolvedColumns = (if (columns > 0) columns else autoColumns).coerceAtMost(maxColumns)

    LaunchedEffect(stateKey) {
        if (shouldLoadInitialPage(state.snapshot)) load(1)
    }
    LaunchedEffect(gridState, snapshot.initialLoadCompleted, snapshot.page, snapshot.hasNext) {
        if (!snapshot.initialLoadCompleted) return@LaunchedEffect
        snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }.collect { (lastVisible, total) ->
            if (total > 0 && lastVisible >= total - 3) load(state.snapshot.page + 1)
        }
    }

    if (snapshot.entries.isEmpty() && !snapshot.initialLoadCompleted) {
        PhoneInitialLoading()
        return
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(resolvedColumns),
        contentPadding = PaddingValues(top = PhoneSpace.sm, bottom = PhoneSpace.xxl),
        horizontalArrangement = Arrangement.spacedBy(PhoneSpace.md),
        verticalArrangement = Arrangement.spacedBy(PhoneSpace.lg),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(snapshot.entries, key = key) { entry -> item(entry) }
        if (snapshot.entries.isEmpty() && snapshot.error == null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PhoneEmptyState(title = emptyTitle, caption = emptyCaption)
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            PhoneListFooter(
                loading = state.loading,
                hasMore = snapshot.hasNext,
                error = snapshot.error,
                onRetry = { load(if (snapshot.entries.isEmpty()) 1 else snapshot.page + 1) },
            )
        }
    }
}

/**
 * 分页曲目列表。
 *
 * [trackFavorites] 为真时跟随收藏状态的 revision 自动刷新首屏：
 * 用户在别处取消收藏后回到列表，那条歌不该还留在「收藏」页里。
 */
@Composable
private fun PhonePagedTracks(
    stateKey: String,
    loader: suspend (Int) -> Page<Track>,
    emptyTitle: String,
    emptyCaption: String?,
    playingTrackId: String?,
    onPlayTracks: (List<Track>, Int) -> Unit,
    trackFavorites: Boolean = false,
    favoriteStatuses: Map<String, Boolean> = emptyMap(),
    onToggleFavorite: (Track, Boolean) -> Unit = { _, _ -> },
) {
    val store = LocalPhoneStore.current
    val deps = LocalPhoneDeps.current
    val state = store.paged<Track>(stateKey)
    val snapshot = state.snapshot
    val listState = rememberLazyListState()
    val load = makePagedLoader(store, state, loader) { it.guid.value }

    LaunchedEffect(stateKey) {
        if (shouldLoadInitialPage(state.snapshot)) load(1)
    }
    if (trackFavorites) {
        val favoriteState by deps.musicRepository.favoriteState.collectAsStateWithLifecycle()
        LaunchedEffect(favoriteState.revision) {
            store.loadFirstPageForRevision(
                state = state,
                revision = favoriteState.revision,
                loader = loader,
                key = { it.guid.value },
            )
        }
    }
    LaunchedEffect(listState, snapshot.initialLoadCompleted, snapshot.page, snapshot.hasNext) {
        if (!snapshot.initialLoadCompleted) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }.collect { (lastVisible, total) ->
            if (total > 0 && lastVisible >= total - 4) load(state.snapshot.page + 1)
        }
    }

    if (snapshot.entries.isEmpty() && !snapshot.initialLoadCompleted) {
        PhoneInitialLoading()
        return
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = PhoneSpace.sm, bottom = PhoneSpace.xxl),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(snapshot.entries, key = { _, track -> track.guid.value }) { index, track ->
            val favorite = favoriteOf(track, favoriteStatuses)
            PhoneTrackRow(
                track = track,
                index = index,
                current = playingTrackId != null && playingTrackId == track.guid.value,
                onClick = { onPlayTracks(snapshot.entries, index) },
                favorite = favorite,
                onToggleFavorite = { onToggleFavorite(track, favorite) },
            )
        }
        if (snapshot.entries.isEmpty() && snapshot.error == null) {
            item { PhoneEmptyState(title = emptyTitle, caption = emptyCaption) }
        }
        item {
            PhoneListFooter(
                loading = state.loading,
                hasMore = snapshot.hasNext,
                error = snapshot.error,
                onRetry = { load(if (snapshot.entries.isEmpty()) 1 else snapshot.page + 1) },
            )
        }
    }
}

/**
 * 生成一个「取第 N 页并合并进快照」的 loader。
 *
 * 抽成函数是因为网格与列表的加载逻辑逐字相同（判重入、判有没有下一页、把异常翻成 AppError），
 * 而它们的渲染差异极大。同一段代码抄两遍，早晚会改一处漏一处。
 */
private fun <T> makePagedLoader(
    store: LibraryRetainedStateStore,
    state: RetainedPagedGridState<T>,
    loader: suspend (Int) -> Page<T>,
    key: (T) -> String,
): (Int) -> Unit = { target ->
    if (!state.loading && (target == 1 || state.snapshot.hasNext)) {
        state.loading = true
        store.scope.launch {
            runCatching { loader(target) }
                .onSuccess { state.snapshot = retainLoadedPage(state.snapshot, it, key) }
                .onFailure {
                    state.snapshot = state.snapshot.copy(
                        error = (it as? AppException)?.error ?: AppError.Unknown(it.message),
                        initialLoadCompleted = state.snapshot.initialLoadCompleted || target == 1,
                    )
                }
            state.loading = false
        }
    }
}
