package com.yueyin.tv.phone

import androidx.compose.foundation.border

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.yueyin.tv.core.model.library.LibraryCapabilities
import com.yueyin.tv.core.model.library.LibraryCapability
import com.yueyin.tv.ui.FnColors
import com.yueyin.tv.ui.FnSkeleton
import com.yueyin.tv.ui.LibraryRetainedStateStore
import com.yueyin.tv.ui.LibraryRoute
import com.yueyin.tv.ui.RetainedPagedGridState
import com.yueyin.tv.ui.retainLoadedPage
import com.yueyin.tv.ui.shouldLoadInitialPage
import kotlin.math.roundToInt
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
    // 本地音乐入口受能力开关控制；关掉时整条来源切换行都不出现，在线曲库维持原样。
    val localEnabled = LibraryCapabilities.has(LibraryCapability.LocalMusic)
    var localSelected by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PhonePageTitle(
            title = "曲库",
            modifier = Modifier.padding(horizontal = PhoneSpace.screenHorizontal),
        )
        if (localEnabled) {
            SourceSwitchBar(localSelected = localSelected, onSelect = { localSelected = it })
        }
        if (localEnabled && localSelected) {
            // 本地分支：整块换成 PhoneLocalMusicPage，原来的 5 个分段一律不渲染。
            PhoneLocalMusicPage(
                onBack = { localSelected = false },
                playingTrackId = playingTrackId,
            )
        } else {
            // 五个分段 = 五页，左右滑动直接切段。
            //
            // 选中态只有一份真相：路由（`segment`）。点分段条、从首页「全部 ›」进来都改路由，
            // Pager 只负责滑动表现，两个方向用 LaunchedEffect 来回同步——各自存一份状态的话，
            // 「滑过去」和「点过去」早晚会出现分段条高亮跟内容对不上的情况。
            val segments = PhoneLibrarySegment.entries
            val pagerState = rememberPagerState(initialPage = segment.ordinal) { segments.size }
            // 分段条的高亮完全跟着 Pager 的滚动进度走，而不是等路由回写：
            // 拖动到两页之间时横条就应该停在两格之间，松手后自然落到目标格。
            val pageProgress by remember(pagerState) {
                derivedStateOf {
                    (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                        .coerceIn(0f, (segments.size - 1).toFloat())
                }
            }
            // collect 协程不随重组重启，直接闭包捕获的 segment 会永远停在进页那一刻的旧值
            // （滑回原分段时 target == 旧值 → 不回写路由 → 横条卡在前一格）。用 UpdatedState 取最新值。
            val currentSegment = rememberUpdatedState(segment)
            LaunchedEffect(segment) {
                // 用户正在滑动时不要抢手势：这段滚动只服务于「点分段条 / 从首页跳进来」。
                if (!pagerState.isScrollInProgress && pagerState.currentPage != segment.ordinal) {
                    pagerState.animateScrollToPage(segment.ordinal)
                }
            }
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.currentPage to pagerState.isScrollInProgress }
                    .collect { (page, scrolling) ->
                        if (scrolling) return@collect
                        val target = segments.getOrNull(page) ?: return@collect
                        if (target != currentSegment.value) onSelectSegment(target)
                    }
            }
            SegmentBar(pageProgress = pageProgress, onSelect = onSelectSegment)
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().padding(horizontal = PhoneSpace.screenHorizontal),
                // 预组合左右各一页：翻页时相邻分段已经画好，滑过去直接是内容，
                // 不会先滑进一块空白再「啪」地长出列表（用户看到的跳动/闪动就是它）。
                // 只多预组合一页，第三页起仍然按需加载，不会在进页时把所有分段都请求一遍。
                beyondViewportPageCount = 1,
                key = { segments[it].name },
            ) { page ->
                when (segments[page]) {
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
}

/**
 * 分段条：五等分铺满，下方一条会跟着手指走的橙色指示线。
 *
 * 横条**不能**由路由 `segment` 驱动——路由只在 Pager 滑动结束后才回写，拖动全程横条
 * 就会钉在旧格子上，松手再突然跳过去（用户看到的「跟不上 / 不切换」）。现在位置直接取
 * `currentPage + currentPageOffsetFraction`，是同一个手势的连续量。
 */
@Composable
private fun SegmentBar(pageProgress: Float, onSelect: (PhoneLibrarySegment) -> Unit) {
    val segments = PhoneLibrarySegment.entries
    val activeIndex = pageProgress.roundToInt().coerceIn(0, segments.size - 1)
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = PhoneSpace.screenHorizontal)) {
            segments.forEachIndexed { index, item ->
                SegmentItem(
                    label = item.label,
                    active = index == activeIndex,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(item) },
                )
            }
        }
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PhoneSpace.screenHorizontal)
                .height(2.dp),
        ) {
            val slotWidth = maxWidth / segments.size
            Box(Modifier.fillMaxSize().background(hairline(), RoundedCornerShape(2.dp)))
            Box(
                Modifier
                    .offset(x = slotWidth * pageProgress)
                    .size(width = slotWidth, height = 2.dp)
                    .background(FnColors.Coral, RoundedCornerShape(2.dp)),
            )
        }
    }
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
            // 等分后每格只有 1/5 宽，极端字体缩放下宁可省略号也不挤出行外。
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 11.dp),
        )
    }
}

/** 分隔线：白 10%，与卡片描边同一档。 */
private fun hairline(): Color = Color.White.copy(alpha = 0.10f)

/**
 * 来源切换小胶囊行：在线 / 本地。
 *
 * 只在本地音乐能力开启时出现（由调用方守卫）。选中「本地」时下方整块换成
 * [PhoneLocalMusicPage]，五个在线分段不再渲染；默认「在线」，在线曲库行为零变化。
 */
@Composable
private fun SourceSwitchBar(localSelected: Boolean, onSelect: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PhoneSpace.screenHorizontal, vertical = PhoneSpace.sm),
        horizontalArrangement = Arrangement.spacedBy(PhoneSpace.sm),
    ) {
        SourcePill(label = "在线", active = !localSelected, onClick = { onSelect(false) })
        SourcePill(label = "本地", active = localSelected, onClick = { onSelect(true) })
    }
}

@Composable
private fun SourcePill(label: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        Modifier
            .background(if (active) FnColors.Coral else FnColors.Surface, shape)
            .border(1.dp, if (active) Color.Transparent else Color.White.copy(alpha = 0.08f), shape)
            .phoneTap(onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) FnColors.Background else FnColors.Muted,
            fontSize = PhoneType.Row,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

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
        // 骨架屏而不是居中的「正在加载…」：占位块与真实网格同一套列宽/间距，
        // 数据到位时是「内容填进原位置」，不是内容从屏幕中间跳回顶部。
        PhoneGridSkeleton(columns = resolvedColumns)
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
        PhoneRowSkeleton()
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
 * 网格首屏骨架：列数、水平间距、垂直间距与 [PhonePagedGrid] 的真实网格逐项对齐。
 *
 * 只在「首屏还没回来」时出现，目的不是好看，而是别让内容换位——
 * 之前用整屏居中的「正在加载…」，数据一到整块内容从屏幕中间跳到顶部，翻页时尤其明显。
 */
@Composable
private fun PhoneGridSkeleton(columns: Int) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = PhoneSpace.sm),
        verticalArrangement = Arrangement.spacedBy(PhoneSpace.lg),
    ) {
        repeat(3) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PhoneSpace.md),
            ) {
                repeat(columns.coerceAtLeast(1)) {
                    Column(Modifier.weight(1f)) {
                        FnSkeleton(Modifier.fillMaxWidth().aspectRatio(1f))
                        Spacer(Modifier.height(PhoneSpace.sm))
                        FnSkeleton(Modifier.fillMaxWidth(0.72f).height(10.dp))
                        Spacer(Modifier.height(6.dp))
                        FnSkeleton(Modifier.fillMaxWidth(0.45f).height(8.dp))
                    }
                }
            }
        }
    }
}

/** 曲目列表首屏骨架：与 [PhoneTrackRow] 同高（[PhoneSize.RowItem]）、同缩略图尺寸。 */
@Composable
private fun PhoneRowSkeleton() {
    Column(Modifier.fillMaxSize().padding(top = PhoneSpace.sm)) {
        repeat(8) {
            Row(
                Modifier.fillMaxWidth().height(PhoneSize.RowItem),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(22.dp))
                Spacer(Modifier.width(PhoneSpace.sm))
                FnSkeleton(Modifier.size(PhoneSize.RowArt), RoundedCornerShape(PhoneRadius.sm))
                Spacer(Modifier.width(PhoneSpace.md))
                Column(Modifier.weight(1f)) {
                    FnSkeleton(Modifier.fillMaxWidth(0.55f).height(11.dp))
                    Spacer(Modifier.height(6.dp))
                    FnSkeleton(Modifier.fillMaxWidth(0.32f).height(9.dp))
                }
            }
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
