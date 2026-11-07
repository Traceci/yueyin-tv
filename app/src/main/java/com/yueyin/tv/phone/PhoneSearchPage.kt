package com.yueyin.tv.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.yueyin.tv.core.data.repository.SearchResults
import com.yueyin.tv.core.model.Album
import com.yueyin.tv.core.model.AppError
import com.yueyin.tv.core.model.AppException
import com.yueyin.tv.core.model.Artist
import com.yueyin.tv.core.model.Playlist
import com.yueyin.tv.core.model.Track
import com.yueyin.tv.ui.FnColors
import com.yueyin.tv.ui.appErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * 搜索页里必须「跨路由活下来」的那部分状态。
 *
 * 为什么不能放在 `PhoneSearchPage` 内部的 `remember` 里：手机壳的路由是 `when (route)`，
 * 点搜索结果的专辑 / 歌手 / 歌单会 `open()` 压一层新路由，搜索页整个从组合里移除，
 * 内部 `remember` 全部丢弃。返回时页面重新组合，得到的是一个全新的空搜索页——
 * 关键词、结果、滚动位置一起没了，用户得从头再输一遍。这与「返回」这两个字的本意正好相反：
 * 返回应该是「回到刚才那个状态」，而不是「重新开始」。
 *
 * 所以把它挂到外壳上（`PhoneApp` 的 `remember`，跨路由存活），页面只负责读写它。
 * 与 `LibraryRetainedStateStore` 的区别：那边存的是可分页的曲库列表，这里是搜索会话，
 * 生命周期只到换账号 / 换平台，没必要塞进那个按 key 回收的仓库里。
 */
internal class PhoneSearchRetention {
    val query = mutableStateOf("")
    val results = mutableStateOf<SearchResults?>(null)
    val error = mutableStateOf<AppError?>(null)
    val diag = mutableStateOf<String?>(null)

    /**
     * 已经为哪个关键词出过结果（含失败）。
     *
     * 返回搜索页时靠它判断「这个词的结果已经在这里了」，不必再搜一遍——
     * 否则每退一次就闪一次 loading、还把滚动位置顶回顶部。
     * 只在请求真正落地（成功 / 超时 / 异常）时写入，飞行中被中断的那次不算：
     * 那样返回时才会如实重搜一次，而不是把上一个词的结果当成这个词的。
     */
    var settledQuery: String = ""

    /** 歌曲区当前展开到第几条（滚到底追加）。返回后保持，不会又缩回第一页。 */
    val visibleCount = mutableStateOf(SEARCH_TRACK_FIRST_PAGE)

    /** 结果列表滚动位置。整份 `LazyListState` 留在外面，返回时停在原处。 */
    val listState = LazyListState()

    /** 换账号 / 换平台时清空：否则会看到上一个数据源的结果。 */
    fun reset() {
        query.value = ""
        results.value = null
        error.value = null
        diag.value = null
        settledQuery = ""
        visibleCount.value = SEARCH_TRACK_FIRST_PAGE
        listState.requestScrollToItem(0)
    }
}

/**
 * 搜索页。
 *
 * 与电视端 `SearchScreen` 是同一套检索能力的两种呈现：
 *
 * - **数据层完全一样**：`musicRepository.search()` 内部就是中文子串 + 拼音全拼 + 拼音首字母，
 *   曲库索引的预热、超时兜底、诊断信息也全部复用。手机端没有另写一套匹配逻辑。
 * - **表现层不同**：电视端要「输入→按确定」才能搜，手机是输入即搜（180ms 防抖）；
 *   电视端结果横滑分区，手机端歌曲直出列表（点一下就能听），其余三类横滑。
 *
 * 边输边搜最容易出的两种 bug，这里都用电视端验证过的写法规避：
 * 1. **旧结果覆盖新结果**——用自增 token，只有最后一次请求允许回写界面；
 * 2. **界面永远停在「正在搜索」**——`finally` 里只在 token 仍是当前值时复位 searching。
 *
 * 关键词与结果存在 [PhoneSearchRetention] 里而不是本页 `remember` 里，理由见那个类的注释。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhoneSearchPage(
    retention: PhoneSearchRetention,
    playingTrackId: String?,
    onBack: () -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onOpenArtist: (Artist) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
) {
    val deps = LocalPhoneDeps.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val inputFocus = remember { FocusRequester() }
    val favoriteState by deps.musicRepository.favoriteState.collectAsStateWithLifecycle()

    var query by retention.query
    var results by retention.results
    var searchError by retention.error
    var searchDiag by retention.diag
    var searching by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var searchToken by remember { mutableStateOf(0) }
    var history by remember { mutableStateOf(phoneSearchHistory(context)) }

    /** 记一条历史并同步界面。只在「用户明确要搜这个词」时调用，不在每个按键上调用。 */
    fun rememberKeyword(keyword: String) {
        history = phoneRememberSearch(context, keyword)
    }

    fun runSearch(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        searchJob?.cancel()
        val token = ++searchToken
        searching = true
        searchError = null
        searchJob = scope.launch {
            try {
                val found = withTimeout(SEARCH_TIMEOUT_MS) { deps.musicRepository.search(trimmed) }
                if (token != searchToken) return@launch
                results = found
                // 新词才算「换了一批结果」：回到列表顶部、收起已展开的条数。
                // 同一个词的重试（结果页上的「重试」）不应该把用户已经滚到的位置抹掉。
                if (retention.settledQuery != trimmed) {
                    retention.settledQuery = trimmed
                    retention.visibleCount.value = SEARCH_TRACK_FIRST_PAGE
                    retention.listState.requestScrollToItem(0)
                }
                // 无结果时把索引诊断（曲库规模 / 建索引失败原因）留在界面上：
                // 在线音源搜不到，八成是索引没建起来而不是歌真的不在。
                searchDiag = if (found.isEmpty) {
                    deps.musicRepository.lastSearchDiagnostics().orEmpty().takeIf { it.isNotBlank() }
                } else {
                    null
                }
            } catch (e: TimeoutCancellationException) {
                if (token != searchToken) return@launch
                searchError = AppError.Unknown("搜索超时，请稍后重试")
                searchDiag = deps.musicRepository.lastSearchDiagnostics().orEmpty().takeIf { it.isNotBlank() }
                retention.settledQuery = trimmed
            } catch (e: CancellationException) {
                return@launch
            } catch (e: Throwable) {
                if (token != searchToken) return@launch
                searchError = (e as? AppException)?.error
                    ?: AppError.Unknown(e.message ?: "搜索失败，请重试")
                searchDiag = deps.musicRepository.lastSearchDiagnostics().orEmpty().takeIf { it.isNotBlank() }
                retention.settledQuery = trimmed
            } finally {
                if (token == searchToken) searching = false
            }
        }
    }

    // 索引预热：手机上第一次搜索往往是「打开 App 顺手搜一下」，
    // 没预热的话首个字符就要等整本曲库索引建完。
    LaunchedEffect(Unit) {
        runCatching { deps.musicRepository.warmSearchIndex() }
        // 从专辑 / 歌手 / 歌单详情返回时不要再抢焦点、不要再弹软键盘：
        // 用户退回来是想接着看结果，键盘弹出来会把半个列表盖住。
        if (retention.settledQuery.isNotEmpty()) return@LaunchedEffect
        delay(120)
        runCatching { inputFocus.requestFocus() }
        runCatching { keyboard?.show() }
    }

    // 输入即搜：停止输入 180ms 后自动搜，和前一次 delay 天然互斥形成防抖。
    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            searchToken++
            searchJob?.cancel()
            results = null
            searching = false
            searchError = null
            searchDiag = null
            retention.settledQuery = ""
            return@LaunchedEffect
        }
        // 返回搜索页：这个词的结果已经在 retention 里躺着，再搜一遍只会闪一次 loading、
        // 并把滚动位置顶回顶部。只有当「词没变但结果不在了」（搜索途中退出等）才补搜。
        if (trimmed == retention.settledQuery &&
            (retention.results.value != null || retention.error.value != null)
        ) {
            return@LaunchedEffect
        }
        delay(180L)
        runSearch(trimmed)
    }

    // 不加 imePadding：Activity 已是 edge-to-edge + 窗口随软键盘缩小，
    // 再叠一层键盘内边距会把内容重复上推（实测会把底部导航挤出屏幕）。
    Column(Modifier.fillMaxSize()) {
        SearchTopBar(
            query = query,
            onQueryChange = { query = it },
            onBack = onBack,
            onSubmit = {
                keyboard?.hide()
                rememberKeyword(query)
            },
            onClear = { query = "" },
            focusRequester = inputFocus,
        )

        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                searchError != null -> SearchErrorState(
                    error = searchError!!,
                    diag = searchDiag,
                    onRetry = { runSearch(query) },
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                results == null && searching -> Box(
                    Modifier.fillMaxWidth().padding(top = 64.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Text(
                        // 首次搜索要等索引：明确说清在等什么，比一个转圈更让人安心
                        "正在搜索…（首次需建立曲库索引）",
                        color = FnColors.Muted,
                        fontSize = PhoneType.Caption,
                    )
                }

                results != null && results!!.isEmpty -> SearchEmptyState(
                    query = query.trim(),
                    diag = searchDiag,
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                results != null -> SearchResultList(
                    results = results!!,
                    retention = retention,
                    playingTrackId = playingTrackId,
                    favoriteStatuses = favoriteState.statuses,
                    onPlayTrack = { tracks, index ->
                        rememberKeyword(query)
                        keyboard?.hide()
                        onPlayTracks(tracks, index)
                    },
                    onToggleFavorite = { track, current ->
                        scope.launch {
                            deps.musicRepository.toggleFavorite(track.guid.value, current, track)
                        }
                    },
                    onOpenAlbum = onOpenAlbum,
                    onOpenArtist = onOpenArtist,
                    onOpenPlaylist = onOpenPlaylist,
                )

                else -> SearchIdleState(
                    history = history,
                    onPickHistory = {
                        query = it
                        rememberKeyword(it)
                    },
                    onClearHistory = { history = phoneClearSearchHistory(context) },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

/** 单次搜索兜底超时，与电视端同一量级：只为兜住网络异常，不截断正常的大曲库建索引。 */
private const val SEARCH_TIMEOUT_MS = 120_000L

/** 歌曲区首屏直出条数；滚到底再追加 [SEARCH_TRACK_PAGE] 条，直到显示完整结果。 */
private const val SEARCH_TRACK_FIRST_PAGE = 30
private const val SEARCH_TRACK_PAGE = 30

// ---------------------------------------------------------------------------
// 顶部输入
// ---------------------------------------------------------------------------

@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    focusRequester: FocusRequester,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = PhoneSpace.sm,
                end = PhoneSpace.screenHorizontal,
                top = PhoneSpace.sm,
                bottom = PhoneSpace.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(LocalCarProfile.current.touchTarget).phoneTap(onBack),
            contentAlignment = Alignment.Center,
        ) {
            PhoneIcon(PhoneGlyph.Back, FnColors.Text, Modifier.size(22.dp))
        }
        Spacer(Modifier.width(PhoneSpace.xs))
        Row(
            Modifier
                .weight(1f)
                .height(44.dp)
                .background(FnColors.Surface, shape)
                .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhoneIcon(PhoneGlyph.Search, FnColors.Muted, Modifier.size(16.dp), strokeWidth = 1.9.dp)
            Spacer(Modifier.width(PhoneSpace.sm))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    textStyle = TextStyle(fontSize = PhoneType.Row, color = FnColors.Text),
                    singleLine = true,
                    cursorBrush = SolidColor(FnColors.Coral),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                "搜索歌曲、歌手、专辑",
                                color = FnColors.Muted,
                                fontSize = PhoneType.Row,
                                maxLines = 1,
                            )
                        }
                        inner()
                    },
                )
            }
            if (query.isNotEmpty()) {
                // 清除用文字「×」而不是自绘图标：它在 44dp 热区里居中即可，
                // 且各厂商输入法的清除符号都是这个形状，用户不用学。
                Box(
                    Modifier.size(28.dp).phoneTap(onClear),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("×", color = FnColors.Muted, fontSize = 18.sp, maxLines = 1)
                }
            }
        }
    }
}

/** 让 18dp 的字号在 tv-material 的 Text 上也能用（它只接受 TextUnit）。 */

// ---------------------------------------------------------------------------
// 空态
// ---------------------------------------------------------------------------

@Composable
private fun SearchIdleState(
    history: List<String>,
    onPickHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = PhoneSpace.screenHorizontal, vertical = PhoneSpace.lg),
    ) {
        if (history.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "最近搜索",
                    color = FnColors.Text,
                    fontSize = PhoneType.Section,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "清空",
                    color = FnColors.Muted,
                    fontSize = PhoneType.Caption,
                    modifier = Modifier.phoneTap(onClearHistory).padding(PhoneSpace.xs),
                )
            }
            Spacer(Modifier.height(PhoneSpace.md))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(PhoneSpace.sm),
                verticalArrangement = Arrangement.spacedBy(PhoneSpace.sm),
            ) {
                history.forEach { keyword ->
                    Box(
                        Modifier
                            .background(FnColors.Surface, RoundedCornerShape(999.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(999.dp))
                            .phoneTap { onPickHistory(keyword) }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(
                            keyword,
                            color = FnColors.Text.copy(alpha = 0.86f),
                            fontSize = PhoneType.Caption,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(PhoneSpace.xl))
        }
        SearchHintCard()
    }
}

/** 搜索提示卡：把「拼音首字母也能搜」这个隐藏能力讲明白，否则用户永远只用中文搜。 */
@Composable
private fun SearchHintCard() {
    Column(
        Modifier
            .fillMaxWidth()
            .background(FnColors.Surface, RoundedCornerShape(PhoneRadius.lg))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(PhoneRadius.lg))
            .padding(PhoneSpace.lg),
    ) {
        Text("支持三种输入", color = FnColors.Text, fontSize = PhoneType.Row, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(PhoneSpace.sm))
        listOf(
            "中文：" to "周杰伦",
            "全拼：" to "zhoujielun",
            "首字母：" to "zjl",
        ).forEach { (label, sample) ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(label, color = FnColors.Muted, fontSize = PhoneType.Caption)
                Text(sample, color = FnColors.Coral, fontSize = PhoneType.Caption, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun SearchEmptyState(query: String, diag: String?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = PhoneSpace.screenHorizontal, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("没有找到「$query」", color = FnColors.Text, fontSize = PhoneType.Row, maxLines = 2)
        Spacer(Modifier.height(PhoneSpace.sm))
        Text("试试拼音首字母，例如 zjl", color = FnColors.Muted, fontSize = PhoneType.Caption)
        diag?.let {
            Spacer(Modifier.height(PhoneSpace.md))
            Text(
                it.take(160),
                color = FnColors.Muted.copy(alpha = 0.7f),
                fontSize = PhoneType.Micro,
                maxLines = 3,
            )
        }
    }
}

@Composable
private fun SearchErrorState(
    error: AppError,
    diag: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = PhoneSpace.screenHorizontal, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(appErrorMessage(error), color = FnColors.Coral, fontSize = PhoneType.Row, maxLines = 3)
        diag?.let {
            Spacer(Modifier.height(PhoneSpace.sm))
            Text(
                it.take(160),
                color = FnColors.Muted.copy(alpha = 0.7f),
                fontSize = PhoneType.Micro,
                maxLines = 3,
            )
        }
        Spacer(Modifier.height(PhoneSpace.lg))
        Box(
            Modifier
                .background(FnColors.Surface, RoundedCornerShape(999.dp))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                .phoneTap(onRetry)
                .padding(horizontal = PhoneSpace.xl, vertical = 9.dp),
        ) {
            Text("重试", color = FnColors.Text, fontSize = PhoneType.Row, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------------------
// 结果
// ---------------------------------------------------------------------------

@Composable
private fun SearchResultList(
    results: SearchResults,
    retention: PhoneSearchRetention,
    playingTrackId: String?,
    favoriteStatuses: Map<String, Boolean>,
    onPlayTrack: (List<Track>, Int) -> Unit,
    onToggleFavorite: (Track, Boolean) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onOpenArtist: (Artist) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
) {
    // 滚动位置与已展开条数都留在 retention 里：进详情再返回时接着看，而不是回到顶部、
    // 也不是缩回第一页（缩回的话用户滚到第 60 首再回来会发现下半截又不见了）。
    val listState = retention.listState
    // 搜索结果不再硬截断：先出一页，滚到底自动追加。
    // 之前固定 take(20)，标题却显示全量（例如 116 首），用户会以为后面的歌丢了；
    // 更糟的是点播队列也只用这 20 首，播到第 20 首「下一曲」就没了。
    val visibleCount = retention.visibleCount.value
    val trackHits = remember(results, visibleCount) {
        results.tracks.take(visibleCount.coerceAtMost(results.tracks.size))
    }

    // 接近列表底部就再放一页。这里刻意每次现读 retention.visibleCount，而不是捕获
    // 上面那个局部值：collect 的闭包只创建一次，捕获的快照会一直停在旧值上，
    // 追加一页之后就再也不涨了。
    LaunchedEffect(listState, results) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { lastVisible ->
                val total = results.tracks.size
                val shown = retention.visibleCount.value
                if (shown < total && lastVisible >= shown - 2) {
                    retention.visibleCount.value = (shown + SEARCH_TRACK_PAGE).coerceAtMost(total)
                }
            }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = PhoneSpace.xxl),
        modifier = Modifier.fillMaxSize(),
    ) {
        // 分类片区（专辑/歌手/歌单）排在歌曲列表之前，和电视端同一顺序：
        // 歌曲动辄上百首，分类横排要是压在列表末尾，用户永远滑不到——
        // 「搜周杰伦看他的专辑」这件事就等于没做。
        if (results.albums.isNotEmpty()) {
            item {
                SearchSectionLabel("专辑", "${results.albums.size} 张")
                SearchCardStrip {
                    items(results.albums.take(20), key = { "al:${it.guid.value}" }) { album ->
                        Box(Modifier.width(PhoneSize.StripItem)) {
                            PhoneAlbumCard(album = album, onClick = { onOpenAlbum(album) })
                        }
                    }
                }
            }
        }
        if (results.artists.isNotEmpty()) {
            item {
                SearchSectionLabel("歌手", "${results.artists.size} 位")
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(PhoneSpace.md),
                    contentPadding = PaddingValues(horizontal = PhoneSpace.screenHorizontal),
                    modifier = Modifier.fillMaxWidth().padding(bottom = PhoneSpace.sm),
                ) {
                    // 歌手结果只给前 20 位：搜索是「快速定位」，不是浏览完整目录。
                    items(results.artists.take(20), key = { "a:${it.guid.value}" }) { artist ->
                        SearchArtistCard(artist = artist, onClick = { onOpenArtist(artist) })
                    }
                }
            }
        }
        if (results.playlists.isNotEmpty()) {
            item {
                SearchSectionLabel("歌单", "${results.playlists.size} 个")
                SearchCardStrip {
                    items(results.playlists.take(20), key = { "p:${it.guid.value}" }) { playlist ->
                        Box(Modifier.width(PhoneSize.StripItem)) {
                            PhonePlaylistCard(playlist = playlist, onClick = { onOpenPlaylist(playlist) })
                        }
                    }
                }
            }
        }
        if (trackHits.isNotEmpty()) {
            item {
                SearchSectionLabel("歌曲", "${results.tracks.size} 首")
            }
            itemsIndexed(trackHits, key = { _, track -> "t:${track.guid.value}" }) { index, track ->
                Row(Modifier.padding(horizontal = PhoneSpace.screenHorizontal)) {
                    val favorite = favoriteOf(track, favoriteStatuses)
                    PhoneTrackRow(
                        track = track,
                        index = index,
                        current = playingTrackId != null && playingTrackId == track.guid.value,
                        // 队列给全量结果，而不是截断后的这一页：否则播到页尾就「没有下一首」。
                        onClick = { onPlayTrack(results.tracks, index) },
                        favorite = favorite,
                        onToggleFavorite = { onToggleFavorite(track, favorite) },
                    )
                }
            }
        }
    }
}

/** 结果分区标题：标题 + 右侧数量。数量能让用户知道「还有更多，只是我只需要这条」。 */@Composable
private fun SearchSectionLabel(title: String, countLabel: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = PhoneSpace.screenHorizontal,
                end = PhoneSpace.screenHorizontal,
                top = PhoneSpace.lg,
                bottom = PhoneSpace.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = FnColors.Text, fontSize = PhoneType.Section, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(PhoneSpace.sm))
        Text(countLabel, color = FnColors.Muted, fontSize = PhoneType.Micro, maxLines = 1)
    }
}

/** 横滑卡片带：统一左右留白与项间距，两个分区不会因为各自写了一套参数而错位。 */
@Composable
private fun SearchCardStrip(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(PhoneSpace.md),
        contentPadding = PaddingValues(horizontal = PhoneSpace.screenHorizontal),
        modifier = Modifier.fillMaxWidth().padding(bottom = PhoneSpace.sm),
        content = content,
    )
}

/**
 * 搜索结果里的歌手卡：圆形头像 + 名字，竖向排列。
 *
 * 不用列表行（[PhoneArtistRow]）：横滑带里的行会横着占满一屏，露出半个第二项，
 * 用户根本看不出「右边还有」。竖卡 96dp 宽，一屏露 3 个半，横滑意图明确。
 */
@Composable
private fun SearchArtistCard(artist: Artist, onClick: () -> Unit) {
    Column(
        Modifier.width(96.dp).phoneTap(onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PhoneArtwork(
            coverId = artist.coverId,
            title = artist.name,
            modifier = Modifier.size(78.dp),
            shape = CircleShape,
        )
        Spacer(Modifier.height(PhoneSpace.sm))
        Text(
            artist.name,
            color = FnColors.Text,
            fontSize = PhoneType.Caption,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        artist.trackCount?.let {
            Spacer(Modifier.height(1.dp))
            Text(
                trackCountLabel(it).orEmpty(),
                color = FnColors.Muted,
                fontSize = PhoneType.Micro,
                maxLines = 1,
            )
        }
    }
}

