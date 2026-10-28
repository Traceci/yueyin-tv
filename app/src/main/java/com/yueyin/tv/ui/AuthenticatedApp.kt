package com.yueyin.tv.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import androidx.tv.material3.Button as TvMaterialButton
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.yueyin.tv.AuthenticatedAppDependencies
import com.yueyin.tv.R
import com.yueyin.tv.core.model.connect.ConnectType
import com.yueyin.tv.ui.connect.ConnectFormScreen
import com.yueyin.tv.ui.connect.PlatformIcon
import com.yueyin.tv.ui.connect.PlatformLibraryScreen
import com.yueyin.tv.ui.connect.PlatformManageScreen
import com.yueyin.tv.ui.connect.PlatformPickerScreen
import com.yueyin.tv.CrashLogStore
import com.yueyin.tv.NowPlayingPresentation
import com.yueyin.tv.NowPlayingResourceState
import com.yueyin.tv.decodeArtwork
import com.yueyin.tv.core.data.repository.CurrentLyrics
import com.yueyin.tv.core.data.repository.SearchResults
import com.yueyin.tv.core.data.repository.SessionState
import com.yueyin.tv.core.model.Album
import com.yueyin.tv.core.model.AppError
import com.yueyin.tv.core.model.AppException
import com.yueyin.tv.core.model.Artist
import com.yueyin.tv.core.model.CoverVariant
import com.yueyin.tv.core.model.Page
import com.yueyin.tv.core.model.PlayerStyle
import com.yueyin.tv.core.model.Playlist
import com.yueyin.tv.core.model.Track
import com.yueyin.tv.core.model.playback.QueueSource
import com.yueyin.tv.core.model.playback.QueueKind
import com.yueyin.tv.core.model.playback.PlayMode
import com.yueyin.tv.core.model.playback.PlaybackQueueItem
import com.yueyin.tv.core.model.playback.NowPlayingIdentity
import com.yueyin.tv.core.model.playback.MAX_ACTIVE_QUEUE_ITEMS
import com.yueyin.tv.core.model.playback.QueuePageItem
import com.yueyin.tv.core.model.playback.QueuePageSegment
import com.yueyin.tv.core.model.playback.boundedQueueWindow
import com.yueyin.tv.core.playback.PlaybackUiState
import com.yueyin.tv.core.playback.PlaybackProgressState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.math.pow
import kotlin.math.sqrt

internal val LocalAuthenticatedDependencies = staticCompositionLocalOf<AuthenticatedAppDependencies> {
    error("Missing authenticated app dependencies")
}

internal val LocalLibraryRetainedState = staticCompositionLocalOf<LibraryRetainedStateStore> {
    error("Missing library retained state")
}

/**
 * 「焦点即切换」导航控制器。
 *
 * 电视遥控器把焦点移到「首页 / 我的」上时立刻切换页面，省掉再按一次确定键的步骤。
 * 切换发生在页面重建之前，若不做处理，新页面会把初始焦点抢到内容区，
 * 导致焦点从导航条上跑掉、无法继续左右移动。这里用一个待处理标记，
 * 让新页面在首次布局时把焦点放回刚选中的导航项。
 */
internal data class NavFocusController(
    /** 待处理的导航项："home" / "my"，null 表示按常规逻辑把焦点放到内容区。 */
    val pending: String?,
    /** 导航项获得焦点、准备切换页面时调用，记录焦点应回到哪一项。 */
    val requestNavSwitch: (String) -> Unit,
    /** 新页面已消费该标记。 */
    val onConsumed: () -> Unit,
    /**
     * Tab「获得焦点」是否算作"用户想切页"。
     * 路由刚变化的一小段时间内为 false——那期间 Tab 拿到的焦点来自系统自动恢复
     * （新页面渲染后默认焦点会落到第一个可聚焦元素，也就是「首页」Tab），
     * 若当成用户意图就会把刚打开的页面立刻弹回首页（表现为"点全部歌单退回首页"）。
     */
    val autoSwitchAllowed: Boolean = true,
) {
    companion object {
        val None = NavFocusController(null, {}, {})
    }
}

internal val LocalNavFocusController = staticCompositionLocalOf { NavFocusController.None }

/**
 * 常驻导航条的焦点锚点。
 *
 * 导航条被提到路由之外（`AnimatedContent` 外面）常驻，页面切换时不再重建它，
 * 因此这几个 requester 必须全 App 唯一且长期存活：否则页面一重建，
 * requester 换实例，焦点就掉进内容区，"右键到不了下一个 Tab"。
 */
internal class NavRequesters(
    val home: FocusRequester = FocusRequester(),
    val albums: FocusRequester = FocusRequester(),
    val playlists: FocusRequester = FocusRequester(),
    val my: FocusRequester = FocusRequester(),
    val search: FocusRequester = FocusRequester(),
    val nowPlaying: FocusRequester = FocusRequester(),
) {
    fun forTab(id: String): FocusRequester? = when (id) {
        "home" -> home
        "albums" -> albums
        "playlists" -> playlists
        "my" -> my
        else -> null
    }
}

internal val LocalNavRequesters = staticCompositionLocalOf { NavRequesters() }

/**
 * 播放页是否压在最上层。
 * 详情页（歌手/专辑/歌单）在"进播放页"的瞬间会做焦点恢复，把焦点抢回列表，
 * 导致播放页的方向键失效（表现为"从歌手页进去听时快进没反应"）。这些页面据此让位。
 */
internal val LocalPlayerRouteOpen = compositionLocalOf { false }

/**
 * 焦点光晕：卡片获得焦点时向外扩一圈主色半透明光晕并轻微上浮 2dp，
 * 让 3 米外的电视屏幕也能一眼看到当前焦点位置（配合 FnFocus.ringWidth 的描边）。
 * 宽度/透明度全部取自 FnFocus，与 fnFocusRing 共用同一套规范。
 */
/**
 * 焦点外观：已改为纯描边（见 [fnFocusRing]），这里保留函数只为兼容既有调用点。
 * 不再上浮、不再画光晕/蒙层——取 Amcfy 那种"干练"的表达。
 */
private fun Modifier.focusGlow(
    focused: Boolean,
    cornerRadius: Dp = 18.dp,
    glowColor: Color = FnColors.Coral,
): Modifier = this

private const val FULL_CATALOG_PAGE_SIZE = 12

/**
 * 路由变化后多久内，导航条 Tab 的"获得焦点"不算用户想切页。
 * 太短会漏掉系统自动恢复焦点的那一帧，太长会让用户在切页后马上按左右键时"没反应"。
 */
private const val TAB_SWITCH_SUPPRESS_MS = 700L

/** 顶栏一级导航的三个入口（注意与导航胶囊单个按钮的 LibraryTab 组件区分）。 */
internal enum class LibraryNavTab { Home, Albums, Playlists, My }

// 首页歌单区的列数不再写死：由 FnGrid.columnsFor(可用宽度) 计算（960 画布下为 5 列），
// 首页展示「列数 × 2 行」个，其余收进「全部歌单」。

@Composable
internal fun AuthenticatedApp(
    container: AuthenticatedAppDependencies,
    session: SessionState.SignedIn,
    playback: PlaybackUiState,
    onExitApplication: () -> Unit,
) {
    var stack by remember(session.user.guid) { mutableStateOf(listOf<LibraryRoute>(LibraryRoute.Home)) }
    var lastHomeBackAt by remember(session.user.guid) { mutableStateOf(0L) }
    val route = stack.last()
    val context = LocalContext.current
    val stateHolder = rememberSaveableStateHolder()
    val retainedScope = rememberCoroutineScope()
    val retainedState = remember(session.user.guid, retainedScope) {
        LibraryRetainedStateStore(retainedScope)
    }
    val routeStateLifecycle = remember(session.user.guid) { LibraryRouteStateLifecycle() }
    val playerVisualContinuity = remember(session.user.guid) { PlayerVisualContinuity() }
    // 导航方向：驱动页面过渡动画（1=进入二级页，-1=返回，0=平级切换）。
    var navDirection by remember(session.user.guid) { mutableStateOf(0) }
    // 页面过渡位移量（dp→px）：transitionSpec 不是 composable 上下文，这里预先算好。
    val navDensity = LocalDensity.current
    val navInOffsetPx = with(navDensity) { 24.dp.toPx() }.toInt()
    val navOutOffsetPx = with(navDensity) { 12.dp.toPx() }.toInt()
    val open: (LibraryRoute) -> Unit = { stack = stack + it; navDirection = 1 }
    val root: (LibraryRoute) -> Unit = { stack = listOf(it); navDirection = 0 }
    val back: () -> Unit = {
        if (stack.size > 1) {
            stack = stack.dropLast(1)
            navDirection = -1
        }
    }
    // 打开 App / 回到前台时自动同步服务器歌单（只读，不反向写入服务器）。
    // 首次进入由首页首次加载即可拿到最新数据；此后每次回到前台都强制刷新歌单列表，
    // 使服务器端新增/改名/删除的歌单同步显示。首页与“全部歌单”共用同一份歌单状态。
    var playlistResumeSynced by remember(session.user.guid) { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (playlistResumeSynced) {
            val playlistState = retainedState.list<Playlist>("playlists")
            retainedState.syncList(
                state = playlistState,
                loader = { container.musicRepository.playlists(forceRefresh = true) },
            )
        } else {
            playlistResumeSynced = true
        }
    }
    // 登录后后台预热搜索索引（歌手、专辑全量载入内存），保证搜索即时返回且覆盖完整；账号切换时自动重建
    LaunchedEffect(session.user.guid) {
        runCatching { container.musicRepository.warmSearchIndex() }
    }
    LaunchedEffect(route) {
        container.updateController.setAutomaticPromptAllowed(route !is LibraryRoute.Player)
    }
    DisposableEffect(container.updateController) {
        onDispose { container.updateController.setAutomaticPromptAllowed(true) }
    }
    LaunchedEffect(route.storageKey()) { lastHomeBackAt = 0L }
    // 切换平台（飞牛 ↔ Navidrome/Jellyfin/Emby…）后，保留的分页/列表缓存全部作废：
    // 不清的话回到首页/我的还是上一个平台的歌，看起来像"切了没反应"。
    val connectState by com.yueyin.tv.ui.connect.ConnectRuntime.store.state.collectAsStateWithLifecycle()
    val activePlatformId = connectState.activeId
    // 有没有可用数据源：登录了真飞牛，或激活了某个协议平台
    val hasFnSession = session.server.guid.value != "local"
    val hasDataSource = activePlatformId != null || hasFnSession
    // 平台相关页面在"没有数据源"时也必须能进（否则用户无处添加平台）
    val isPlatformRoute = route is LibraryRoute.PlatformManage ||
        route == LibraryRoute.PlatformPicker ||
        route is LibraryRoute.PlatformForm ||
        route is LibraryRoute.PlatformLibrary
    var boundPlatformId by remember(session.user.guid) { mutableStateOf<String?>(null) }
    LaunchedEffect(activePlatformId) {
        if (boundPlatformId != activePlatformId) {
            boundPlatformId = activePlatformId
            retainedState.clearAll()
            // 切平台必须停掉当前播放并清空队列：否则上一平台的歌还在播/还在队列里，
            // 用户会以为"切了没反应"。
            // 注意：这里必须用 stopAndClearQueue 而不是 clearSession——clearSession 会把
            // 播放命名空间一起清空，而换平台不会触发新的会话凭据下发，namespace 就永久为 null，
            // 之后 playQueue 拿不到快照命名空间直接返回 null，表现为"任何歌都播不了"。
            runCatching { container.playbackController.stopAndClearQueue() }
        }
    }
    LaunchedEffect(stack) {
        routeStateLifecycle.update(stack).forEach { removedRoute ->
            stateHolder.removeState(removedRoute.storageKey())
            retainedState.remove(removedRoute.retainedStateKeys())
        }
    }
    BackHandler {
        when {
            stack.size > 1 -> {
                stack = stack.dropLast(1)
                navDirection = -1
            }
            // 一级 tab 页按返回回到首页（四个 Tab 互为平级，不再有二级页的回退语义）
            route == LibraryRoute.My || route == LibraryRoute.AllPlaylists ||
                route == LibraryRoute.Albums -> root(LibraryRoute.Home)
            route == LibraryRoute.Home -> {
                val now = SystemClock.elapsedRealtime()
                if (isHomeBackConfirmed(lastHomeBackAt, now)) {
                    lastHomeBackAt = 0L
                    onExitApplication()
                } else {
                    lastHomeBackAt = now
                    Toast.makeText(context, "再按一次退出", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    LaunchedEffect(playback.error) {
        if (playback.error?.requiresSessionVerification == true) {
            container.authenticatedActions.verifyCurrentSession()
        }
    }

    // 导航条「焦点即切换」：记录切换后焦点应回到哪个导航项
    var navFocusTarget by remember { mutableStateOf<String?>(null) }
    // 路由刚变的一小段时间里，Tab「获得焦点」不算用户想切页。
    // 原因：进入新页面时系统会把默认焦点落到第一个可聚焦元素（也就是「首页」Tab），
    // 若照常按"焦点即切页"处理，用户从首页点「全部歌单」就会被瞬间弹回首页。
    var suppressTabSwitch by remember { mutableStateOf(false) }
    LaunchedEffect(route) {
        suppressTabSwitch = true
        delay(TAB_SWITCH_SUPPRESS_MS)
        suppressTabSwitch = false
    }
    val navFocusController = remember(navFocusTarget, suppressTabSwitch) {
        NavFocusController(
            pending = navFocusTarget,
            requestNavSwitch = { navFocusTarget = it },
            onConsumed = { navFocusTarget = null },
            autoSwitchAllowed = !suppressTabSwitch,
        )
    }
    // 常驻导航条的焦点锚点：全 App 唯一，随 AuthenticatedApp 生命周期存活。
    val navRequesters = remember { NavRequesters() }
    // 当前路由对应的一级导航项；null 表示这一页不显示导航条（详情页 / 搜索 / 播放页 / 设置）。
    val navTab = when (route) {
        LibraryRoute.Home -> LibraryNavTab.Home
        LibraryRoute.Albums -> LibraryNavTab.Albums
        LibraryRoute.AllPlaylists -> LibraryNavTab.Playlists
        LibraryRoute.My -> LibraryNavTab.My
        else -> null
    }
    // 焦点保底：切页会销毁旧内容里的焦点节点。若新页面还在加载、迟迟没人接焦点，
    // Compose 焦点树就会处于「无焦点」状态；此时按方向键，系统会执行 restoreDefaultFocus，
    // 在 Compose 内部命中一个已 detach 的节点并抛
    // "visitAncestors called on an unattached node"，直接崩掉整个进程
    // （用户看到的就是"闪退 + 声音立刻停"）。
    // 这里在切页后等几帧，若内容区仍然没有焦点，就先把焦点交给常驻的导航条 Tab，
    // 让焦点树里始终有一个 attached 的节点。
    // 用 AtomicBoolean 而不是 Compose State：焦点每次移动都写 State 会让整个
    // AuthenticatedApp 重组，代价太大，这里只需要读一个瞬时值。
    val contentFocused = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val firstRouteSeen = remember { mutableStateOf(true) }
    LaunchedEffect(route, navTab) {
        // 首次组合不干预：刚启动时不存在"被销毁的焦点节点"，让首页按原逻辑抢焦点。
        if (firstRouteSeen.value) {
            firstRouteSeen.value = false
            return@LaunchedEffect
        }
        repeat(3) { withFrameNanos { } }
        if (contentFocused.get()) return@LaunchedEffect
        // 详情页/播放页/设置页不显示导航条，没有常驻锚点可用，只能靠进程级兜底。
        val anchor = navTab?.let { navRequesters.forTab(it.name.lowercase()) } ?: return@LaunchedEffect
        runCatching { anchor.requestFocus() }
    }
    CompositionLocalProvider(
        LocalAuthenticatedDependencies provides container,
        LocalLibraryRetainedState provides retainedState,
        LocalNavFocusController provides navFocusController,
        LocalNavRequesters provides navRequesters,
        LocalPlayerRouteOpen provides (route is LibraryRoute.Player),
    ) {
        Box(Modifier.fillMaxSize()) {
            // 全局氛围背景：所有路由共用一层缓慢漂移的光斑，避免大面积纯黑显得沉闷。
            // 播放页自带整屏沉浸背景（不透明，会把这层完全盖住），因此直接不画——
            // 省掉一整屏 24fps 的径向渐变填充，是这轮最划算的一处优化。
            if (route !is LibraryRoute.Player) {
                FnAmbientBackdrop(Modifier.fillMaxSize())
            }
            Column(Modifier.fillMaxSize()) {
                // 一级导航条常驻在 AnimatedContent **外面**：
                // 切页时它不参与重建，焦点永远停在同一个 Tab 上，左右键链不会断。
                if (navTab != null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 48.dp)
                            .padding(top = 24.dp),
                    ) {
                        LibraryTopBar(
                            playback = playback,
                            selectedTab = navTab,
                            navRequesters = navRequesters,
                            onHome = { root(LibraryRoute.Home) },
                            onAlbums = { root(LibraryRoute.Albums) },
                            onPlaylists = { root(LibraryRoute.AllPlaylists) },
                            onMy = { root(LibraryRoute.My) },
                            onSearch = { open(LibraryRoute.Search) },
                            onPlayer = { open(LibraryRoute.Player(null)) },
                        )
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        // 只观察、不参与：记录内容区（含子节点）当前是否有焦点，供上面的焦点保底判断。
                        .onFocusChanged { contentFocused.set(it.hasFocus) },
                ) {
                    if (!hasDataSource && !isPlatformRoute) {
                        // 首次安装 / 平台全被删掉时的空态：一张大卡引导去连接平台。
                        // 连上任意平台后它就永久消失，正常情况下只会看到一次。
                        ConnectFirstScreen(
                            savedPlatforms = connectState.profiles,
                            onConnect = { open(LibraryRoute.PlatformManage) },
                            onPickSaved = {
                                com.yueyin.tv.ui.connect.ConnectRuntime.activate(it)
                            },
                        )
                    } else {
                    stateHolder.SaveableStateProvider(route.storageKey()) {
                        AnimatedContent(
                            targetState = route,
                            modifier = Modifier.fillMaxSize(),
                            contentKey = { it.storageKey() },
                            transitionSpec = {
                                // 页面过渡：二级页从右缘滑入 24dp + 淡入 150ms；返回反向；平级切换纯淡入。
                                val dir = navDirection
                                if (dir == 0) {
                                    fadeIn(tween(150)) togetherWith fadeOut(tween(110)) using
                                        SizeTransform(clip = false)
                                } else {
                                    val towards = if (dir > 0) SlideDirection.Left else SlideDirection.Right
                                    (
                                        slideIntoContainer(
                                            towards = towards,
                                            animationSpec = tween(150, easing = LinearEasing),
                                            initialOffset = { navInOffsetPx },
                                        ) + fadeIn(tween(150))
                                    ) togetherWith (
                                        slideOutOfContainer(
                                            towards = towards,
                                            animationSpec = tween(110),
                                            targetOffset = { navOutOffsetPx },
                                        ) + fadeOut(tween(110))
                                    ) using SizeTransform(clip = false)
                                }
                            },
                            label = "route",
                        ) { targetRoute ->
                            when (targetRoute) {
            LibraryRoute.Home -> BrowseHome(
                container,
                playback,
                onAlbums = { open(LibraryRoute.Albums) },
                onMy = { root(LibraryRoute.My) },
                onSearch = { open(LibraryRoute.Search) },
                onPlaylist = { open(LibraryRoute.PlaylistDetail(it)) },
                onFavorites = { open(LibraryRoute.Favorites) },
                onAll = { open(LibraryRoute.AllPlaylists) },
                onPlayer = { open(LibraryRoute.Player(null)) },
            )
            LibraryRoute.My -> BrowseMy(
                container,
                session,
                playback,
                onHome = { root(LibraryRoute.Home) },
                onSearch = { open(LibraryRoute.Search) },
                onArtists = { open(LibraryRoute.Artists) },
                onAlbums = { open(LibraryRoute.Albums) },
                onAllTracks = { open(LibraryRoute.AllTracks) },
                onPlaylists = { open(LibraryRoute.AllPlaylists) },
                onPlaylist = { open(LibraryRoute.PlaylistDetail(it)) },
                onArtist = { open(LibraryRoute.ArtistDetail(it)) },
                onAlbum = { open(LibraryRoute.AlbumDetail(it)) },
                onSettings = { open(LibraryRoute.Settings) },
                onManagePlatforms = { open(LibraryRoute.PlatformManage) },
                onPlayer = { open(LibraryRoute.Player(null)) },
            )
            // 平台管理走正式路由：AnimatedContent 会用新的子树替换旧页面，
            // 因此不会出现「覆盖层里有下层页面在抢焦点」的问题（这也是之前"点不动"的根因）。
            LibraryRoute.PlatformManage -> PlatformManageScreen(
                onAdd = { open(LibraryRoute.PlatformPicker) },
                onBrowse = { open(LibraryRoute.PlatformLibrary(it)) },
                onRelogin = {
                    // 换飞牛账号（或首次登录飞牛）：先把数据源指回飞牛，再走登录
                    com.yueyin.tv.ui.connect.ConnectRuntime.activate(null)
                    retainedScope.launch { container.authenticatedActions.switchAccount() }
                },
                onEdit = { open(LibraryRoute.PlatformForm(it.type, it.id)) },
                onSignOutFn = {
                    retainedScope.launch { container.authenticatedActions.signOutFnSession() }
                },
                onDelete = { profile ->
                    val wasActive = com.yueyin.tv.ui.connect.ConnectRuntime.store.state.value.activeId == profile.id
                    com.yueyin.tv.ui.connect.ConnectRuntime.store.remove(profile.id)
                    // 删掉的正是当前平台：自动切到剩下的第一个；一个都不剩就回飞牛（无会话则回空态）
                    if (wasActive) {
                        val next = com.yueyin.tv.ui.connect.ConnectRuntime.store.state.value.profiles.firstOrNull()
                        com.yueyin.tv.ui.connect.ConnectRuntime.activate(next)
                    }
                },
                fnSessionActive = hasFnSession,
                onBack = back,
            )
            LibraryRoute.PlatformPicker -> PlatformPickerScreen(
                onPick = { type ->
                    if (type == ConnectType.FEINIU) {
                        // 飞牛音乐走账号登录：先把数据源指回飞牛，再退回平台页并拉起登录
                        com.yueyin.tv.ui.connect.ConnectRuntime.activate(null)
                        back()
                        retainedScope.launch { container.authenticatedActions.switchAccount() }
                    } else {
                        open(LibraryRoute.PlatformForm(type))
                    }
                },
                onBack = back,
            )
            is LibraryRoute.PlatformForm -> ConnectFormScreen(
                type = targetRoute.type,
                existing = targetRoute.editProfileId?.let { id ->
                    com.yueyin.tv.ui.connect.ConnectRuntime.store.state.value.profiles
                        .firstOrNull { it.id == id }
                },
                // 保存成功后直接退出「选平台 + 填表单」这两层，回到平台列表
                // （列表里能看到刚加的平台，也方便立刻切换）
                onSaved = {
                    stack = stack.dropLast(2).ifEmpty { listOf(LibraryRoute.My) }
                    navDirection = -1
                },
                onBack = back,
            )
            is LibraryRoute.PlatformLibrary -> PlatformLibraryScreen(
                profile = targetRoute.profile,
                playbackController = container.playbackController,
                onBack = back,
            )
            LibraryRoute.AllPlaylists -> AllPlaylists(
                container = container,
                playback = playback,
                onHome = { root(LibraryRoute.Home) },
                onAlbums = { open(LibraryRoute.Albums) },
                onMy = { root(LibraryRoute.My) },
                onSearch = { open(LibraryRoute.Search) },
                onPlayer = { open(LibraryRoute.Player(null)) },
                onOpen = { open(LibraryRoute.PlaylistDetail(it)) },
            )
            LibraryRoute.Search -> SearchScreen(
                container,
                onPlayTrack = { open(LibraryRoute.Player(it)) },
                onOpenPlaylist = { open(LibraryRoute.PlaylistDetail(it)) },
                onOpenArtist = { open(LibraryRoute.ArtistDetail(it)) },
                onOpenAlbum = { open(LibraryRoute.AlbumDetail(it)) },
                onBack = back,
            )
            is LibraryRoute.PlaylistDetail -> TrackCollection(
                container = container,
                stateKey = "playlist:${targetRoute.playlist.guid.value}:tracks",
                title = targetRoute.playlist.name,
                coverId = targetRoute.playlist.coverId,
                loader = { container.musicRepository.playlistTracks(targetRoute.playlist.guid.value, it) },
                queueSource = { sort -> QueueSource.Playlist(targetRoute.playlist.guid.value, sort) },
                onPlayer = { open(LibraryRoute.Player(it)) },
                detailHeader = TrackDetailHeader(
                    kind = "歌单",
                    declaredTrackCount = targetRoute.playlist.trackCount,
                    onBack = back,
                ),
            )
            LibraryRoute.Artists -> ArtistGrid(container, onOpen = { open(LibraryRoute.ArtistDetail(it)) })
            LibraryRoute.Albums -> AlbumGrid(container, onOpen = { open(LibraryRoute.AlbumDetail(it)) })
            LibraryRoute.AllTracks -> TrackCollection(
                container = container,
                stateKey = "all-tracks",
                title = "全部歌曲",
                coverId = null,
                loader = container.musicRepository::allTracks,
                queueSource = QueueSource::LibraryAllTracks,
                onPlayer = { open(LibraryRoute.Player(it)) },
                detailHeader = TrackDetailHeader(
                    kind = "音乐库",
                    artworkFallback = CollectionArtworkFallback.Collection,
                    onBack = back,
                ),
                primaryAction = TrackCollectionPrimaryAction.StartRoam {
                    open(LibraryRoute.Player(null))
                },
            )
            LibraryRoute.Favorites -> {
                val favoriteState by container.musicRepository.favoriteState.collectAsStateWithLifecycle()
                TrackCollection(
                    container = container,
                    stateKey = "favorites:tracks",
                    title = "收藏",
                    loader = container.musicRepository::favoriteTracks,
                    queueSource = QueueSource::Favorites,
                    onPlayer = { open(LibraryRoute.Player(it)) },
                    detailHeader = TrackDetailHeader(
                        kind = "收藏",
                        artworkFallback = CollectionArtworkFallback.Favorites,
                        onBack = back,
                    ),
                    contentRevision = favoriteState.revision,
                    emptyMessage = "还没有收藏歌曲",
                )
            }
            is LibraryRoute.ArtistDetail -> ArtistDetail(
                container = container,
                artist = targetRoute.artist,
                onBack = back,
                onAlbum = { open(LibraryRoute.AlbumDetail(it)) },
                onPlayer = { open(LibraryRoute.Player(it)) },
            )
            is LibraryRoute.AlbumDetail -> TrackCollection(
                container = container,
                stateKey = "album:${targetRoute.album.guid.value}:tracks",
                title = targetRoute.album.name,
                subtitle = targetRoute.album.artistName.orEmpty(),
                coverId = targetRoute.album.coverId,
                loader = { container.musicRepository.albumTracks(targetRoute.album.guid.value, it) },
                queueSource = { sort -> QueueSource.Album(targetRoute.album.guid.value, sort) },
                onPlayer = { open(LibraryRoute.Player(it)) },
                detailHeader = TrackDetailHeader(
                    kind = "专辑",
                    extraMetadata = targetRoute.album.releaseDate,
                    declaredTrackCount = targetRoute.album.trackCount,
                    onBack = back,
                ),
            )
            is LibraryRoute.Player -> ImmersivePlayer(
                container = container,
                playback = playback,
                visualContinuity = playerVisualContinuity,
                onExitRoam = {
                    retainedScope.launch {
                        stack = if (container.playbackController.exitRoamDurably()) {
                            stack.dropLast(1) + LibraryRoute.Player(null)
                        } else {
                            listOf(LibraryRoute.Home)
                        }
                    }
                },
            )
                            LibraryRoute.Settings -> SettingsScreen(container)
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

/**
 * 常驻一级导航条。
 *
 * 它渲染在 `AnimatedContent` **外面**（见 [AuthenticatedApp]），页面怎么切都不会重建，
 * 所以里面的 6 个 FocusRequester 必须来自全 App 唯一的 [NavRequesters]：
 * 只要实例不换，「首页 → 专辑 → 歌单 → 我的 → 搜索」这条左右键链就永远成立。
 */
@Composable
private fun LibraryTopBar(
    playback: PlaybackUiState,
    selectedTab: LibraryNavTab,
    navRequesters: NavRequesters,
    onHome: () -> Unit,
    onAlbums: () -> Unit,
    onPlaylists: () -> Unit,
    onMy: () -> Unit,
    onSearch: () -> Unit,
    onPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(70.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧：一级导航（首页 / 歌单 / 我的）+ 搜索。
            // 遥控器导航习惯是从左上开始向右推进，把最高频的导航放在左上更符合直觉；
            // 「正在播放」移到右上角，成为右下内容区之外的第二个入口。
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                SegmentedLibraryTabs(
                    selectedTab = selectedTab,
                    navRequesters = navRequesters,
                    onHome = onHome,
                    onAlbums = onAlbums,
                    onPlaylists = onPlaylists,
                    onMy = onMy,
                )
                // 搜索：只有一个放大镜，不套任何外圈/底色（TV 上越干净越好认）。
                // 焦点反馈靠「变白 + 轻微放大」，而不是加框。
                var searchFocused by remember { mutableStateOf(false) }
                Button(
                    onClick = onSearch,
                    modifier = Modifier
                        .size(48.dp)
                        .focusProperties {
                            left = navRequesters.my
                            right = if (playback.hasMedia) navRequesters.nowPlaying else FocusRequester.Cancel
                        }
                        .focusRequester(navRequesters.search)
                        .onFocusChanged { searchFocused = it.isFocused },
                    shape = ButtonDefaults.shape(CircleShape, CircleShape, CircleShape, CircleShape, CircleShape),
                    scale = ButtonDefaults.scale(focusedScale = 1f),
                    colors = ButtonDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = FnColors.Text,
                        focusedContainerColor = Color.Transparent,
                        focusedContentColor = FnColors.Text,
                        pressedContainerColor = Color.Transparent,
                        pressedContentColor = FnColors.Text,
                    ),
                    border = ButtonDefaults.border(
                        border = Border(BorderStroke(0.dp, Color.Transparent), shape = CircleShape),
                        focusedBorder = Border(BorderStroke(0.dp, Color.Transparent), shape = CircleShape),
                        pressedBorder = Border(BorderStroke(0.dp, Color.Transparent), shape = CircleShape),
                    ),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    val tint = if (searchFocused) FnColors.Text else FnColors.Muted
                    val glyphSize = if (searchFocused) 28.dp else 26.dp
                    Canvas(Modifier.size(glyphSize)) {
                        val stroke = 2.6.dp.toPx()
                        val radius = size.minDimension * 0.335f
                        val center = androidx.compose.ui.geometry.Offset(
                            size.width * 0.42f,
                            size.height * 0.42f,
                        )
                        drawCircle(
                            color = tint,
                            radius = radius,
                            center = center,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                        )
                        drawLine(
                            color = tint,
                            start = androidx.compose.ui.geometry.Offset(
                                center.x + radius * 0.72f,
                                center.y + radius * 0.72f,
                            ),
                            end = androidx.compose.ui.geometry.Offset(size.width * 0.93f, size.height * 0.93f),
                            strokeWidth = stroke,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        )
                    }
                }
            }
            if (playback.hasMedia) {
                NowPlayingPill(
                    playback = playback,
                    onClick = onPlayer,
                    modifier = Modifier
                        .focusProperties {
                            left = navRequesters.search
                            right = FocusRequester.Cancel
                        }
                        .focusRequester(navRequesters.nowPlaying),
                )
            }
        }
        // 顶栏底部发丝线：一道极淡的横向渐变，把导航区与内容区分开，
        // 同时给整块顶栏一个清晰的“落边”，避免大黑面上元素发飘。
        Canvas(Modifier.fillMaxWidth().height(1.dp)) {
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.5f to Color.White.copy(alpha = 0.12f),
                    1f to Color.Transparent,
                ),
            )
        }
    }
}

@Composable
private fun SegmentedLibraryTabs(
    selectedTab: LibraryNavTab,
    navRequesters: NavRequesters,
    onHome: () -> Unit,
    onAlbums: () -> Unit,
    onPlaylists: () -> Unit,
    onMy: () -> Unit,
) {
    val navFocus = LocalNavFocusController.current
    val view = LocalView.current
    // 焦点落到 Tab 即切页——但**不能在这一刻同步切**。
    // onFocusChanged 仍处在按键事件派发过程中，同步切页会当场销毁旧内容里的焦点节点，
    // 让 Compose 焦点图与组合树短暂不同步；此时若再有一次方向键，系统会执行
    // restoreDefaultFocus → 命中已 detach 的节点 → 抛
    // "visitAncestors called on an unattached node" 直接崩掉进程。
    // 所以这里只登记目标，真正的切页用 View.post 放到当前消息（按键派发）之后执行。
    fun applySwitch(target: LibraryNavTab) {
        when (target) {
            LibraryNavTab.Home -> onHome()
            LibraryNavTab.Albums -> onAlbums()
            LibraryNavTab.Playlists -> onPlaylists()
            LibraryNavTab.My -> onMy()
        }
    }
    var queuedTab by remember { mutableStateOf<LibraryNavTab?>(null) }
    var postScheduled by remember { mutableStateOf(false) }
    val scheduleSwitch: (LibraryNavTab) -> Unit = { target ->
        queuedTab = target
        if (!postScheduled) {
            postScheduled = true
            val posted = view.post {
                postScheduled = false
                val pending = queuedTab
                queuedTab = null
                if (pending != null) applySwitch(pending)
            }
            if (!posted) {
                // 视图已 detach（理论上不会走到），退回同步切换，至少功能可用。
                postScheduled = false
                queuedTab = null
                applySwitch(target)
            }
        }
    }
    // Tab 拿到焦点时的统一处理。
    fun onTabFocused(tab: LibraryNavTab) {
        if (selectedTab == tab) return
        if (!navFocus.autoSwitchAllowed) {
            // 路由刚变化：这次焦点来自系统自动恢复（新页面渲染后默认焦点落在
            // 第一个可聚焦元素，也就是「首页」Tab），不是用户想切页。
            // 把焦点纠正回当前选中项，否则会出现"从首页点「全部歌单」立刻被弹回首页"。
            runCatching {
                navRequesters.forTab(selectedTab.name.lowercase())?.requestFocus()
            }
            return
        }
        navFocus.requestNavSwitch(tab.name.lowercase())
        scheduleSwitch(tab)
    }
    val containerShape = CircleShape
    val tabWidth = 78.dp
    val pillInset = 3.dp
    val containerWidth = tabWidth * 4 + pillInset * 2
    // 滑动指示器：随选中项平滑滑动
    val indicatorX by animateDpAsState(
        targetValue = pillInset + tabWidth * selectedTab.ordinal,
        animationSpec = tween(durationMillis = FnMotion.Normal, easing = FastOutSlowInEasing),
        label = "navIndicator",
    )
    Box(
        Modifier.size(width = containerWidth, height = 53.dp),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(width = containerWidth, height = 49.dp)
                .background(FnColors.SurfaceAlt, containerShape)
                .border(0.5.dp, Color.White.copy(alpha = 0.10f), containerShape)
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryTab(
                label = "首页",
                selected = selectedTab == LibraryNavTab.Home,
                modifier = Modifier
                    .focusProperties {
                        left = FocusRequester.Cancel
                        right = navRequesters.albums
                    }
                    .focusRequester(navRequesters.home)
                    // 焦点落到该项即刻切换，不必再按确定键
                    .onFocusChanged {
                        if (it.isFocused) onTabFocused(LibraryNavTab.Home)
                    },
                onClick = { if (selectedTab != LibraryNavTab.Home) onHome() },
            )
            LibraryTab(
                label = "专辑",
                selected = selectedTab == LibraryNavTab.Albums,
                modifier = Modifier
                    .focusProperties {
                        left = navRequesters.home
                        right = navRequesters.playlists
                    }
                    .focusRequester(navRequesters.albums)
                    .onFocusChanged {
                        if (it.isFocused) onTabFocused(LibraryNavTab.Albums)
                    },
                onClick = { if (selectedTab != LibraryNavTab.Albums) onAlbums() },
            )
            LibraryTab(
                label = "歌单",
                selected = selectedTab == LibraryNavTab.Playlists,
                modifier = Modifier
                    .focusProperties {
                        left = navRequesters.albums
                        right = navRequesters.my
                    }
                    .focusRequester(navRequesters.playlists)
                    .onFocusChanged {
                        if (it.isFocused) onTabFocused(LibraryNavTab.Playlists)
                    },
                onClick = { if (selectedTab != LibraryNavTab.Playlists) onPlaylists() },
            )
            LibraryTab(
                label = "我的",
                selected = selectedTab == LibraryNavTab.My,
                modifier = Modifier
                    .focusProperties {
                        left = navRequesters.playlists
                        right = navRequesters.search
                    }
                    .focusRequester(navRequesters.my)
                    .onFocusChanged {
                        if (it.isFocused) onTabFocused(LibraryNavTab.My)
                    },
                onClick = { if (selectedTab != LibraryNavTab.My) onMy() },
            )
        }
        // 滑动指示器：选中项下方一段渐变亮条（比 tab 略窄、居中）
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset(x = indicatorX + 11.dp, y = 49.5.dp)
                .size(width = tabWidth - 22.dp, height = 2.5.dp)
                .background(FnGradients.Primary, RoundedCornerShape(FnRadius.sm)),
        )
    }
}

@Composable
private fun LibraryTab(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    icon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val shape = CircleShape
    Button(
        onClick = onClick,
        modifier = modifier
            .size(width = 78.dp, height = 43.dp)
            .semantics { this.selected = selected },
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) FnColors.Text else FnColors.Muted,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = FnColors.Text,
            pressedContainerColor = Color.Transparent,
            pressedContentColor = FnColors.Text,
        ),
        border = ButtonDefaults.border(
            // 选中态用「白色细描边」表示，不再是珊瑚色实心填充（无蒙层）
            border = Border(
                BorderStroke(
                    if (selected) 1.5.dp else FnFocus.hairlineWidth,
                    if (selected) FnFocus.ringColor.copy(alpha = 0.55f) else FnFocus.hairlineColor,
                ),
                shape = shape,
            ),
            focusedBorder = Border(BorderStroke(FnFocus.ringWidth, FnFocus.ringColor), shape = shape),
            pressedBorder = Border(BorderStroke(FnFocus.ringWidth, FnFocus.ringColor), shape = shape),
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (icon != null) {
                icon()
            } else {
                Text(label, fontSize = FnType.CardTitle, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
}

@Composable
internal fun NowPlayingPill(
    playback: PlaybackUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onTitleTextLayout: (TextLayoutResult) -> Unit = {},
) {
    val shape = CircleShape
    val coverId = playback.coverId
    val fontScale = LocalDensity.current.fontScale
    val pillHeight = (42f + (fontScale - 1f).coerceAtLeast(0f) * 28f).dp
    // 标题切换时重置挤压标记，让新标题重新获得一次「标题 + 歌手」的测量机会
    var titleCrowded by remember(playback.title) { mutableStateOf(false) }
    // 真实播放进度：进度流每 250ms 更新一次，这里刻意只在「绘制阶段」取值（draw 相位订阅），
    // 这样进度刷新只触发重绘，不会重组整个胶囊，也不会牵动封面加载。
    val progressState = LocalAuthenticatedDependencies.current.playbackController.progress
        .collectAsStateWithLifecycle()
    Button(
        onClick = onClick,
        modifier = modifier
            .size(width = 186.dp, height = pillHeight)
            // 胶囊底部内嵌一条细进度线：左右各内缩 14dp 避开两端圆角，
            // 已播放段用品牌珊瑚色 + 末端光点；暂停时整条压暗、去掉光点。
            .drawWithContent {
                drawContent()
                val state = progressState.value
                val durationMs = state.durationMs
                if (durationMs <= 0L) return@drawWithContent
                val trackHeight = 2.5.dp.toPx()
                val inset = 14.dp.toPx()
                val left = inset
                val top = size.height - trackHeight - 3.5.dp.toPx()
                val trackWidth = (size.width - inset * 2f).coerceAtLeast(1f)
                drawRoundRect(
                    color = Color.White.copy(alpha = if (playback.isPlaying) 0.16f else 0.09f),
                    topLeft = Offset(left, top),
                    size = Size(trackWidth, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f),
                )
                val fraction = (state.positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                if (fraction <= 0f) return@drawWithContent
                val playedWidth = (trackWidth * fraction).coerceAtLeast(trackHeight)
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(FnColors.Coral.copy(alpha = 0.62f), FnColors.Coral),
                        startX = left,
                        endX = left + playedWidth,
                    ),
                    topLeft = Offset(left, top),
                    size = Size(playedWidth, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f),
                )
                if (playback.isPlaying) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.92f),
                        radius = trackHeight * 0.85f,
                        center = Offset(left + playedWidth, top + trackHeight / 2f),
                    )
                }
            }
            .semantics { contentDescription = "当前播放：${playback.title}" },
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = FnColors.SurfaceAlt,
            contentColor = FnColors.Text,
            focusedContainerColor = Color(0xFF1D2632),
            focusedContentColor = FnColors.Text,
            pressedContainerColor = Color(0xFF232E3C),
            pressedContentColor = FnColors.Text,
        ),
        border = ButtonDefaults.border(
            border = Border(BorderStroke(0.5.dp, Color.White.copy(alpha = 0.14f)), shape = shape),
            focusedBorder = Border(BorderStroke(3.dp, FnFocus.ringColor), shape = shape),
            pressedBorder = Border(BorderStroke(3.dp, FnFocus.ringColor), shape = shape),
        ),
        contentPadding = PaddingValues(start = 5.dp, top = 4.5.dp, end = 9.dp, bottom = 4.5.dp),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (coverId != null) {
                RemoteArtwork(
                    container = LocalAuthenticatedDependencies.current,
                    coverId = coverId,
                    variant = CoverVariant.Compact,
                    modifier = Modifier.size(27.dp),
                    shape = CircleShape,
                    contentScale = ContentScale.Crop,
                    placeholderContent = { NowPlayingArtworkFallback(playback.title) },
                )
            } else {
                NowPlayingArtworkFallback(playback.title)
            }
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(3.5.dp).background(if (playback.isPlaying) FnColors.Coral else FnColors.Muted, CircleShape))
                    Spacer(Modifier.width(3.5.dp))
                    Text(
                        if (playback.isPlaying) "正在播放" else "已暂停",
                        color = FnColors.Muted,
                        fontSize = 9.sp,
                        lineHeight = 9.sp,
                        style = TextStyle(
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                        ),
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(1.5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        playback.title.ifBlank { "正在播放" },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        style = TextStyle(
                            platformStyle = PlatformTextStyle(includeFontPadding = true),
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { result ->
                            // 标题被截断时收起歌手名，把宽度让给标题（同一次标题生命周期内只做单向判定，避免反复抖动）
                            if (result.hasVisualOverflow) titleCrowded = true
                            onTitleTextLayout(result)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    // 标题放不下时优先保标题，歌手名整体隐藏（由 onTextLayout 的溢出判定驱动）
                    if (playback.artist.isNotBlank() && !titleCrowded) {
                        Spacer(Modifier.width(4.5.dp))
                        Text(
                            playback.artist,
                            color = FnColors.Muted,
                            fontSize = 9.sp,
                            lineHeight = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(0.62f, fill = false),
                        )
                    }
                }
            }
            Spacer(Modifier.width(5.dp))
            Text("›", color = FnColors.Muted, fontSize = 16.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun NowPlayingArtworkFallback(title: String) {
    Box(
        Modifier.size(27.dp).background(Color(0xFF20332E), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title.trim().take(1).ifBlank { "音" }.uppercase(),
            color = FnColors.Teal,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BrowseHome(
    container: AuthenticatedAppDependencies,
    playback: PlaybackUiState,
    onAlbums: () -> Unit,
    onMy: () -> Unit,
    onSearch: () -> Unit,
    onPlaylist: (Playlist) -> Unit,
    onFavorites: () -> Unit,
    onAll: () -> Unit,
    onPlayer: () -> Unit,
) {
    val retainedStore = LocalLibraryRetainedState.current
    val preferences by container.appPreferences.state.collectAsStateWithLifecycle()
    val playlistState = retainedStore.list<Playlist>("playlists")
    val albumState = retainedStore.paged<Album>("grid:albums")
    val favoritePreviewState = retainedStore.paged<Track>("home:favorites-preview")
    val favoriteLibraryState by container.musicRepository.favoriteState.collectAsStateWithLifecycle()
    val playlistSnapshot = playlistState.snapshot
    val albums = albumState.snapshot.entries
    val favoriteTracks = favoritePreviewState.snapshot.entries
    var actionError by remember { mutableStateOf<AppError?>(null) }
    var roamActionRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        retainedStore.loadListOnce(playlistState, container.musicRepository::playlists)
        retainedStore.loadFirstPageOnce(
            albumState,
            { page -> container.musicRepository.albums(page, FULL_CATALOG_PAGE_SIZE) },
        ) { it.guid.value }
    }
    LaunchedEffect(favoriteLibraryState.revision) {
        retainedStore.loadFirstPageForRevision(
            state = favoritePreviewState,
            revision = favoriteLibraryState.revision,
            loader = container.musicRepository::favoriteTracks,
            key = { it.guid.value },
        )
    }
    val orderedPlaylists = remember(playlistSnapshot.entries, preferences.playlistOrder) {
        applyPlaylistOrder(playlistSnapshot.entries, preferences.playlistOrder)
    }
    val roamArtwork = remember(albums, orderedPlaylists) {
        featureArtworkSlots(
            primary = albums.map { FeatureArtworkItem(it.name, it.coverId) },
            fallback = orderedPlaylists.map { FeatureArtworkItem(it.name, it.coverId) },
        )
    }
    val favoriteArtwork = remember(favoriteTracks) {
        featureArtworkSlots(
            primary = favoriteTracks.map { FeatureArtworkItem(it.title, it.coverId) },
        )
    }
    HomeScreenLayout(
        onAlbums = onAlbums,
        playlists = orderedPlaylists,
        playlistsLoaded = playlistSnapshot.initialLoadCompleted,
        playback = playback,
        roamArtwork = roamArtwork,
        favoriteArtwork = favoriteArtwork,
        actionError = actionError,
        playlistError = playlistSnapshot.error,
        roamBusy = roamActionRunning || playback.roamBusy,
        onRoam = {
            // 随心听：不再依赖飞牛的漫游接口，改为「当前平台的全部曲目里随机取 50 首」直接起播。
            // 这样飞牛、Navidrome、Jellyfin/Emby 都能用同一套逻辑（也修掉了 Emby 下漫游不能播的问题）。
            if (roamActionRunning) {
                // 正在准备，忽略重复点击
            } else {
            roamActionRunning = true
            scope.launch {
                try {
                    val picked = runCatching {
                        container.musicRepository.allTracks(1).items.shuffled().take(50)
                    }.getOrDefault(emptyList())
                    val fallback = if (picked.isEmpty()) {
                        // 有些平台（或权限受限的账号）拿不到全量曲目，退一步用第一个歌单
                        runCatching {
                            container.musicRepository.playlists().firstOrNull()?.let { playlist ->
                                container.musicRepository.playlistTracks(playlist.guid.value, 1)
                                    .items.shuffled().take(50)
                            }
                        }.getOrNull().orEmpty()
                    } else {
                        emptyList()
                    }
                    val queued = container.musicRepository.prepareQueue(if (picked.isEmpty()) fallback else picked)
                    if (queued.isEmpty()) {
                        actionError = AppError.Empty
                    } else {
                        actionError = null
                        container.playbackController.playQueue(queued, 0)
                        onPlayer()
                    }
                } finally {
                    roamActionRunning = false
                }
            }
            }
        },
        onFavorites = onFavorites,
        onPlaylist = onPlaylist,
        onAll = onAll,
        onMy = onMy,
        onSearch = onSearch,
        onPlayer = onPlayer,
    )
}

/**
 * 首页版式。数据由 [BrowseHome] 加载后传入，便于预览与测试。
 */
@Composable
internal fun HomeScreenLayout(
    playlists: List<Playlist>,
    playlistsLoaded: Boolean,
    playback: PlaybackUiState,
    roamArtwork: List<FeatureArtworkItem>,
    favoriteArtwork: List<FeatureArtworkItem>,
    actionError: AppError?,
    playlistError: AppError?,
    roamBusy: Boolean,
    onRoam: () -> Unit,
    onFavorites: () -> Unit,
    onPlaylist: (Playlist) -> Unit,
    onAll: () -> Unit,
    onAlbums: () -> Unit,
    onMy: () -> Unit,
    onSearch: () -> Unit,
    onPlayer: () -> Unit,
) {
    // 首页按用户排序铺满「自适应列数 × 2 行」，其余全部收进「全部歌单」。
    // 列数由可用宽度算出（960 画布下为 5 列），两行即 10 个。
    val playlistColumns = FnGrid.columnsFor(
        rememberCanvasWidthDp() - FnCanvas.MinSafeHorizontal.value * 2 - FnGrid.ContentInsetDp,
    )
    val homeLimit = playlistColumns * 2
    val homePlaylists = playlists.take(homeLimit)
    var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var initialFocusRequested by remember { mutableStateOf(false) }
    val contentFocus = remember { FocusRequester() }
    // 导航条已上提到路由之外常驻，这里只借用它的全局锚点做「上键回导航条」
    val nav = LocalNavRequesters.current
    val roamFocus = remember { FocusRequester() }
    val favoritesFocus = remember { FocusRequester() }
    val playlistFirstFocus = remember { FocusRequester() }
    val allPlaylistsFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    val navFocus = LocalNavFocusController.current
    LaunchedEffect(playlistsLoaded, playback.hasMedia, focusedKey, navFocus.pending) {
        if (initialFocusRequested) return@LaunchedEffect
        // 导航条「焦点即切换」：导航条常驻、焦点本来就停在被选中的 Tab 上，
        // 这里只要不把焦点抢到内容区即可。
        if (navFocus.pending != null) {
            initialFocusRequested = true
            navFocus.onConsumed()
            return@LaunchedEffect
        }
        val availableKeys = buildList {
            add("roam")
            add("favorites")
            if (playlistsLoaded) {
                addAll(homePlaylists.map { "playlist:${it.guid.value}" })
                add("all-playlists")
            }
        }
        focusedKey = focusedKey?.takeIf(availableKeys::contains) ?: availableKeys.firstOrNull()
        yield()
        if (focusedKey != null) {
            runCatching { contentFocus.requestFocus() }
            initialFocusRequested = true
        }
    }
    // 内容边距 48dp：官方过扫描保护下限（960×5%）。
    // 一级导航条已上提到路由之外常驻，这里只负责内容区。
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp).padding(bottom = 24.dp)) {
        Spacer(Modifier.height(6.dp))
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(playlistColumns),
            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "home:header") {
                // 与启动页同一套品牌 lockup（B 方案）：黑胶在左，名称 + 标语在右
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_logo),
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        // 品牌名与 App 显示名保持一致（strings.xml 的 app_name = 悦音）
                        Text("悦音", fontSize = FnType.PageTitle, lineHeight = 33.sp, fontWeight = FontWeight.Bold)
                        Text("你的曲库为你发声", color = FnColors.Muted, fontSize = FnType.Sub, lineHeight = 16.sp)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "home:features") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    HomeFeatureCard(
                        title = "随心听",
                        kind = HomeArtworkKind.Roam,
                        artwork = roamArtwork,
                        modifier = Modifier
                            .weight(1f)
                            .focusProperties {
                                up = if (playback.hasMedia) nav.nowPlaying else nav.home
                                right = favoritesFocus
                                down = playlistFirstFocus
                            }
                            .focusRequester(roamFocus)
                            .then(if (focusedKey == "roam") Modifier.focusRequester(contentFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) focusedKey = "roam" },
                        onClick = {
                            focusedKey = "roam"
                            if (roamBusy) return@HomeFeatureCard
                            onRoam()
                        },
                    )
                    HomeFeatureCard(
                        title = "收藏",
                        kind = HomeArtworkKind.Favorites,
                        artwork = favoriteArtwork,
                        modifier = Modifier
                            .weight(1f)
                            .focusProperties {
                                up = nav.my
                                left = roamFocus
                                right = FocusRequester.Cancel
                                down = playlistFirstFocus
                            }
                            .focusRequester(favoritesFocus)
                            .then(if (focusedKey == "favorites") Modifier.focusRequester(contentFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) focusedKey = "favorites" },
                        onClick = {
                            focusedKey = "favorites"
                            onFavorites()
                        },
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "home:playlists-title") {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("我的歌单", fontSize = FnType.CardTitle, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(11.dp))
                    Text(
                        text = when {
                            !playlistsLoaded -> "正在载入歌单…"
                            playlists.isEmpty() -> "还没有歌单"
                            else -> "共 ${playlists.size} 个 · 首页展示前 $homeLimit 个"
                        },
                        color = FnColors.Muted,
                        fontSize = FnType.Caption,
                        lineHeight = 14.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    HomeAllPlaylistsButton(
                        total = playlists.size,
                        limit = homeLimit,
                        modifier = Modifier
                            .focusRequester(allPlaylistsFocus)
                            .then(if (focusedKey == "all-playlists") Modifier.focusRequester(contentFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) focusedKey = "all-playlists" },
                        onClick = {
                            focusedKey = "all-playlists"
                            onAll()
                        },
                    )
                }
            }
            if (!playlistsLoaded) {
                // 首屏骨架：歌单未到位前按「当前列数 × 2 行」铺占位块，尺寸与加载完成后的歌单卡完全一致，
                // 避免“先空一片、再整块弹出”的布局跳变。
                // 骨架屏也走同一套尺寸：封面高、两段文字高都与真卡一致，加载完不会跳一下。
                items(homeLimit, key = { "home:skeleton:$it" }) {
                    val metrics = rememberPlaylistMetrics()
                    val textLine = metrics.titleBlock / PlaylistTitleLines.toFloat()
                    Column(Modifier.fillMaxWidth()) {
                        FnSkeleton(
                            Modifier.fillMaxWidth().height(metrics.cover),
                            shape = RoundedCornerShape(FnRadius.lg),
                        )
                        Spacer(Modifier.height(PlaylistCoverGap))
                        FnSkeleton(
                            Modifier.fillMaxWidth(0.72f).height(textLine * 0.62f),
                            shape = RoundedCornerShape(FnRadius.sm),
                        )
                        Spacer(Modifier.height(5.dp))
                        FnSkeleton(
                            Modifier.fillMaxWidth(0.42f).height(metrics.captionBlock * 0.85f),
                            shape = RoundedCornerShape(FnRadius.sm),
                        )
                    }
                }
            }
            homePlaylists.forEachIndexed { index, playlist ->
                val key = "playlist:${playlist.guid.value}"
                item(key = "playlist:${playlist.guid.value}") {
                    HomePlaylistLockup(
                        title = playlist.name,
                        subtitle = playlistSubtitle(playlist),
                        coverId = playlist.coverId,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (index == 0) Modifier.focusRequester(playlistFirstFocus) else Modifier,
                            )
                            .then(if (focusedKey == key) Modifier.focusRequester(contentFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) focusedKey = key },
                        onClick = {
                            focusedKey = key
                            onPlaylist(playlist)
                        },
                    )
                }
            }
            if (playlistsLoaded && playlists.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "home:empty") {
                    Text("还没有歌单，去服务器创建后会自动同步", color = FnColors.Muted, fontSize = 14.sp)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "home:footer") {
                Spacer(Modifier.height(10.dp))
            }
        }
        (actionError ?: playlistError)?.let { InlineError(it) }
    }
}


private fun playlistSubtitle(playlist: Playlist): String =
    playlist.trackCount?.takeIf { it > 0 }?.let { "$it 首" } ?: "歌单"

@Composable
private fun HomeAllPlaylistsButton(
    total: Int,
    limit: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(999.dp)
    Button(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.06f),
            contentColor = FnColors.Text,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = FnColors.Text,
            pressedContainerColor = Color.Transparent,
            pressedContentColor = FnColors.Text,
        ),
        border = ButtonDefaults.border(
            border = Border(BorderStroke(0.5.dp, Color.White.copy(alpha = 0.14f)), shape = shape),
            focusedBorder = Border(BorderStroke(2.5.dp, FnFocus.ringColor), shape = shape),
            pressedBorder = Border(BorderStroke(2.5.dp, FnFocus.ringColor), shape = shape),
        ),
        contentPadding = PaddingValues(horizontal = 18.dp),
    ) {
        Text(
            text = if (total > limit) "全部歌单（$total）›" else "全部歌单 ›",
            fontSize = 14.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

internal data class FeatureArtworkItem(
    val title: String,
    val coverId: String?,
)

internal fun featureArtworkSlots(
    primary: List<FeatureArtworkItem>,
    fallback: List<FeatureArtworkItem> = emptyList(),
    limit: Int = 3,
): List<FeatureArtworkItem> {
    if (limit <= 0) return emptyList()
    return (primary + fallback)
        .asSequence()
        .mapNotNull { item ->
            item.coverId?.trim()?.takeIf(String::isNotEmpty)?.let { item.copy(coverId = it) }
        }
        .distinctBy { it.coverId }
        .take(limit)
        .toList()
}

/**
 * 把用户自定义歌单顺序（guid 列表）应用到歌单列表：
 * 已知 guid 先按用户顺序排列，其余歌单保持服务器原有顺序追加在末尾。
 * order 为空时直接返回服务器顺序。
 */
internal fun applyPlaylistOrder(
    playlists: List<Playlist>,
    order: List<String>,
): List<Playlist> {
    if (order.isEmpty()) return playlists
    val byGuid = playlists.associateBy { it.guid.value }
    val ordered = order.mapNotNull(byGuid::get)
    val known = order.toSet()
    val remaining = playlists.filter { it.guid.value !in known }
    return ordered + remaining
}

@Composable
private fun HomeFeatureCard(
    title: String,
    kind: HomeArtworkKind,
    artwork: List<FeatureArtworkItem>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(FnRadius.xl)
    val background = when (kind) {
        HomeArtworkKind.Roam -> Brush.linearGradient(
            listOf(Color(0xFF07201C), Color(0xFF0E3A31), Color(0xFF12312C)),
        )
        HomeArtworkKind.Favorites -> Brush.linearGradient(
            listOf(Color(0xFF1C1114), Color(0xFF331A1B), Color(0xFF271319)),
        )
        HomeArtworkKind.Collection,
        HomeArtworkKind.PlaylistGrid,
        -> Brush.linearGradient(listOf(Color(0xFF141C2A), Color(0xFF1E2A3A)))
    }
    Button(
        onClick = onClick,
        modifier = modifier.height(118.dp),
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = FnColors.Text,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = FnColors.Text,
            pressedContainerColor = Color.Transparent,
            pressedContentColor = FnColors.Text,
        ),
        border = ButtonDefaults.border(
            border = Border(BorderStroke(0.5.dp, Color.White.copy(alpha = 0.06f)), shape = shape),
            focusedBorder = Border(BorderStroke(3.dp, FnColors.Amber), shape = shape),
            pressedBorder = Border(BorderStroke(3.dp, FnColors.Amber), shape = shape),
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(Modifier.fillMaxSize().background(background).clip(shape)) {
            FeatureCoverDeck(kind, artwork, Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.27f),
                            0.38f to Color.Black.copy(alpha = 0.06f),
                            1f to Color.Black.copy(alpha = 0.13f),
                        ),
                    ),
            )
            FeatureGlyph(
                kind = kind,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 20.dp, top = 18.dp).size(48.dp),
            )
            Text(
                title,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 18.dp),
                fontSize = 19.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun FeatureCoverDeck(
    kind: HomeArtworkKind,
    artwork: List<FeatureArtworkItem>,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val placements = deckPlacements(kind, artwork.size)
    Box(modifier.clipToBounds()) {
        placements.forEach { placement ->
            val item = artwork.getOrNull(placement.itemIndex) ?: return@forEach
            val artworkShape = RoundedCornerShape(FnRadius.md)
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(112.dp)
                    .graphicsLayer {
                        translationX = with(density) { placement.translationXDp.dp.toPx() }
                        translationY = with(density) { placement.translationYDp.dp.toPx() }
                        rotationZ = placement.rotation
                        shadowElevation = with(density) { 7.dp.toPx() }
                        shape = artworkShape
                        clip = true
                    }
                    .border(0.5.dp, Color.White.copy(alpha = 0.22f), artworkShape),
            ) {
                val coverId = item.coverId
                if (coverId != null) {
                    RemoteArtwork(
                        container = LocalAuthenticatedDependencies.current,
                        coverId = coverId,
                        variant = CoverVariant.Grid,
                        fallbackVariant = CoverVariant.Compact,
                        modifier = Modifier.fillMaxSize(),
                        shape = artworkShape,
                        contentScale = ContentScale.Crop,
                        placeholderContent = {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF242927), artworkShape),
                            )
                        },
                    )
                } else {
                    InitialArtworkPlaceholder(
                        text = item.title,
                        accent = if (kind == HomeArtworkKind.Roam) FnColors.Teal else FnColors.Coral,
                        modifier = Modifier.fillMaxSize(),
                        shape = artworkShape,
                    )
                }
            }
        }
    }
}

private fun deckPlacements(kind: HomeArtworkKind, artworkCount: Int): List<DeckPlacement> =
    when (kind) {
        HomeArtworkKind.Roam -> when (artworkCount.coerceAtMost(3)) {
            1 -> listOf(DeckPlacement(0, -72f, 3f, 0f))
            2 -> listOf(
                DeckPlacement(0, -118f, 7f, -8f),
                DeckPlacement(1, -24f, 5f, 8f),
            )
            3 -> listOf(
                DeckPlacement(0, -146f, 6f, -11f),
                DeckPlacement(1, -76f, 8f, 0f),
                DeckPlacement(2, -8f, 3f, 10f),
            )
            else -> emptyList()
        }
        HomeArtworkKind.Favorites -> when (artworkCount.coerceAtMost(3)) {
            1 -> listOf(DeckPlacement(0, -72f, 0f, 0f))
            2 -> listOf(
                DeckPlacement(0, -118f, 7f, -6f),
                DeckPlacement(1, -24f, 7f, 6f),
            )
            3 -> listOf(
                DeckPlacement(0, -140f, 8f, -7f),
                DeckPlacement(2, -8f, 8f, 7f),
                DeckPlacement(1, -72f, 0f, 0f),
            )
            else -> emptyList()
        }
        HomeArtworkKind.Collection,
        HomeArtworkKind.PlaylistGrid,
        -> emptyList()
    }

private data class DeckPlacement(
    val itemIndex: Int,
    val translationXDp: Float,
    val translationYDp: Float,
    val rotation: Float,
)

@Composable
private fun FeatureGlyph(kind: HomeArtworkKind, modifier: Modifier = Modifier) {
    val accent = if (kind == HomeArtworkKind.Roam) FnColors.Teal else FnColors.Coral
    Box(
        modifier
            .background(Color(0xFF0E1314).copy(alpha = 0.92f), CircleShape)
            .border(0.5.dp, Color.White.copy(alpha = 0.14f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(25.dp)) {
            if (kind == HomeArtworkKind.Favorites) {
                drawPath(heartPath(size), color = accent, style = Stroke(width = 2.2.dp.toPx()))
            } else {
                val stroke = 2.1.dp.toPx()
                val startX = size.width * 0.08f
                val endX = size.width * 0.88f
                fun point(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x, y)
                drawLine(accent, point(startX, size.height * 0.24f), point(size.width * 0.34f, size.height * 0.24f), stroke, StrokeCap.Round)
                drawLine(accent, point(size.width * 0.34f, size.height * 0.24f), point(size.width * 0.67f, size.height * 0.76f), stroke, StrokeCap.Round)
                drawLine(accent, point(size.width * 0.67f, size.height * 0.76f), point(endX, size.height * 0.76f), stroke, StrokeCap.Round)
                drawLine(accent, point(startX, size.height * 0.76f), point(size.width * 0.34f, size.height * 0.76f), stroke, StrokeCap.Round)
                drawLine(accent, point(size.width * 0.34f, size.height * 0.76f), point(size.width * 0.67f, size.height * 0.24f), stroke, StrokeCap.Round)
                drawLine(accent, point(size.width * 0.67f, size.height * 0.24f), point(endX, size.height * 0.24f), stroke, StrokeCap.Round)
                drawLine(accent, point(endX, size.height * 0.24f), point(size.width * 0.75f, size.height * 0.12f), stroke, StrokeCap.Round)
                drawLine(accent, point(endX, size.height * 0.24f), point(size.width * 0.75f, size.height * 0.36f), stroke, StrokeCap.Round)
                drawLine(accent, point(endX, size.height * 0.76f), point(size.width * 0.75f, size.height * 0.64f), stroke, StrokeCap.Round)
                drawLine(accent, point(endX, size.height * 0.76f), point(size.width * 0.75f, size.height * 0.88f), stroke, StrokeCap.Round)
            }
        }
    }
}

/*
 * 歌单卡（首页「我的歌单」与「全部歌单」共用）。
 *
 * 卡片总高固定 182dp 不动 —— 首页是「品牌头 + 两张功能卡 + 标题行 + 两行歌单」，
 * 一屏几乎没有余量，卡一长高第二行歌单就会被挤出屏幕。
 *
 * 所以文字块高度改成按字号换算（`sp.toDp()`，内部已乘系统字体缩放），封面吃掉剩下的空间。
 * 之前文字块写死 44dp / 15dp：电视把系统字体放大到 1.17 倍时，两行歌单名需要 51dp 却只有 44dp、
 * 12sp 汉字自然行高 17.4sp 却只给了 15dp，于是长名字被压成一行省略号、曲目数下缘被卡片底边裁掉。
 * 现在字体放大多少文字块就长多少，封面相应变矮，一个字都不会被切；
 * 字体 1.0 倍的设备上封面为 104dp（原 111dp），肉眼几乎无差。
 */
private val PlaylistTileHeight = 182.dp
private val PlaylistCoverGap = 9.dp
private val PlaylistCaptionGap = 3.dp

/** 19sp 标题的行高。24sp（1.26em）给汉字下缘留出余量，22sp 时第二行底部会被压掉。 */
private val PlaylistTitleLineHeight = 24.sp

/** 12sp 副标题的行高。汉字自然行高约 1.45em = 17.4sp，给到 18sp 才不切字。 */
private val PlaylistCaptionLineHeight = 18.sp
private const val PlaylistTitleLines = 2

/** 歌单卡三块尺寸：文字块由字号换算，封面吃掉 182dp 里剩下的部分。 */
private data class PlaylistMetrics(val cover: Dp, val titleBlock: Dp, val captionBlock: Dp)

@Composable
private fun rememberPlaylistMetrics(): PlaylistMetrics {
    val density = LocalDensity.current
    return remember(density) {
        with(density) {
            val titleBlock = (PlaylistTitleLineHeight.value * PlaylistTitleLines).sp.toDp()
            val captionBlock = PlaylistCaptionLineHeight.toDp()
            val cover = (PlaylistTileHeight - PlaylistCoverGap - PlaylistCaptionGap - titleBlock - captionBlock)
                .coerceAtLeast(72.dp)
            PlaylistMetrics(cover = cover, titleBlock = titleBlock, captionBlock = captionBlock)
        }
    }
}

@Composable
internal fun HomePlaylistLockup(
    title: String,
    subtitle: String,
    coverId: String?,
    modifier: Modifier = Modifier,
    fallback: HomeArtworkKind? = null,
    onClick: () -> Unit,
) {
    val metrics = rememberPlaylistMetrics()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(FnRadius.lg)
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(PlaylistTileHeight)
            .focusGlow(focused = focused, cornerRadius = 16.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = FnColors.Text,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = FnColors.Text,
            pressedContainerColor = Color.Transparent,
            pressedContentColor = FnColors.Text,
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(metrics.cover)
                    // 封面区焦点外观统一走 FnFocus：聚焦主色描边 + 光晕，未聚焦一道淡描边
                    .fnFocusRing(focused = focused, shape = shape, cornerRadius = 14.dp)
                    .clip(shape),
            ) {
                PlaylistTileArtwork(
                    title = title,
                    coverId = coverId,
                    accent = FnColors.Coral,
                    modifier = Modifier.fillMaxSize(),
                    featureArtwork = fallback,
                )
            }
            Spacer(Modifier.height(PlaylistCoverGap))
            // 歌单名允许两行：一列只有 187dp 左右，19sp 一行只放得下 9 个字，
            // 「我喜欢的音乐（每周更新）」这类名字会被截成半截。与专辑卡同一套规则：
            // 标题固定占两行的高度，行内卡片才对得齐；曲目数跟在后面。
            Text(
                title,
                fontSize = FnType.Body,
                lineHeight = PlaylistTitleLineHeight,
                fontWeight = FontWeight.SemiBold,
                maxLines = PlaylistTitleLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().height(metrics.titleBlock),
            )
            Spacer(Modifier.height(PlaylistCaptionGap))
            Text(
                subtitle,
                color = FnColors.Muted,
                fontSize = FnType.Caption,
                lineHeight = PlaylistCaptionLineHeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().height(metrics.captionBlock),
            )
        }
    }
}

internal fun isHomeBackConfirmed(
    previousBackAt: Long,
    currentBackAt: Long,
    windowMs: Long = 2_000L,
): Boolean = previousBackAt > 0L && currentBackAt >= previousBackAt &&
    currentBackAt - previousBackAt <= windowMs

@Composable
private fun BrowseMy(
    container: AuthenticatedAppDependencies,
    session: SessionState.SignedIn,
    playback: PlaybackUiState,
    onHome: () -> Unit,
    onSearch: () -> Unit,
    onArtists: () -> Unit,
    onAlbums: () -> Unit,
    onAllTracks: () -> Unit,
    onPlaylists: () -> Unit,
    onPlaylist: (Playlist) -> Unit,
    onArtist: (Artist) -> Unit,
    onAlbum: (Album) -> Unit,
    onSettings: () -> Unit,
    onManagePlatforms: () -> Unit,
    onPlayer: () -> Unit,
) {
    val retainedStore = LocalLibraryRetainedState.current
    val preferences by container.appPreferences.state.collectAsStateWithLifecycle()
    val artistState = retainedStore.paged<Artist>("grid:artists")
    val albumState = retainedStore.paged<Album>("grid:albums")
    val playlistState = retainedStore.list<Playlist>("playlists")
    val artists = artistState.snapshot.entries
    val albums = albumState.snapshot.entries
    val playlists = playlistState.snapshot.entries
    val myPlaylists = applyPlaylistOrder(playlists, preferences.playlistOrder).take(8)
    val playlistsLoaded = playlistState.snapshot.initialLoadCompleted
    val artistsLoaded = artistState.snapshot.initialLoadCompleted
    val albumsLoaded = albumState.snapshot.initialLoadCompleted
    var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var initialFocusRequested by remember { mutableStateOf(false) }
    val contentFocus = remember { FocusRequester() }
    val nav = LocalNavRequesters.current
    val settingsFocus = remember { FocusRequester() }
    val switchAccountFocus = remember { FocusRequester() }
    val artistRowFocus = remember { FocusRequester() }
    val albumRowFocus = remember { FocusRequester() }
    val playlistRowFocus = remember { FocusRequester() }
    val libraryRowFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = retainedStore.scope
    val uiContext = LocalContext.current
    LaunchedEffect(Unit) {
        retainedStore.loadFirstPageOnce(
            artistState,
            { page -> container.musicRepository.artists(page, FULL_CATALOG_PAGE_SIZE) },
        ) { it.guid.value }
        retainedStore.loadFirstPageOnce(
            albumState,
            { page -> container.musicRepository.albums(page, FULL_CATALOG_PAGE_SIZE) },
        ) { it.guid.value }
        retainedStore.loadListOnce(playlistState, container.musicRepository::playlists)
    }
    val navFocus = LocalNavFocusController.current
    LaunchedEffect(artistsLoaded, albumsLoaded, playback.hasMedia, focusedKey, navFocus.pending) {
        if (initialFocusRequested) return@LaunchedEffect
        // 导航条常驻，焦点本来就在被选中的 Tab 上，这里不抢焦点即可
        if (navFocus.pending != null) {
            initialFocusRequested = true
            navFocus.onConsumed()
            return@LaunchedEffect
        }
        val allContentLoaded = artistsLoaded && albumsLoaded
        val restoringChrome = focusedKey == "settings" || focusedKey == "switch-account"
        if (!allContentLoaded && !restoringChrome) return@LaunchedEffect
        val availableKeys = buildList {
            if (allContentLoaded) {
                addAll(artists.take(8).map { "artist:${it.guid.value}" })
                add("all-artists")
                addAll(albums.take(8).map { "album:${it.guid.value}" })
                add("all-albums")
                addAll(myPlaylists.map { "playlist:${it.guid.value}" })
                add("all-playlists")
                add("sync-playlists")
                add("all-tracks")
            }
            add("settings")
            add("switch-account")
        }
        focusedKey = focusedKey?.takeIf(availableKeys::contains) ?: availableKeys.firstOrNull()
        yield()
        if (focusedKey != null) {
            runCatching { contentFocus.requestFocus() }
            initialFocusRequested = true
        }
    }
    val activeProfile by com.yueyin.tv.ui.connect.ConnectRuntime.store.state.collectAsState()
    // 资料条要能一眼看出"当前在哪个平台"：飞牛显示 fnOS，其它显示平台名
    val platformLabel = when (activeProfile.active?.type) {
        null -> "fnOS"
        com.yueyin.tv.core.model.connect.ConnectType.FEINIU -> "fnOS"
        com.yueyin.tv.core.model.connect.ConnectType.NAVIDROME -> "Navidrome"
        com.yueyin.tv.core.model.connect.ConnectType.SUBSONIC -> "Subsonic"
        com.yueyin.tv.core.model.connect.ConnectType.JELLYFIN -> "Jellyfin"
        com.yueyin.tv.core.model.connect.ConnectType.EMBY -> "Emby"
        else -> activeProfile.active?.type?.displayName.orEmpty()
    }
    // Dialog 是独立 Window，不会继承 YueYinTheme 里放大过的 LocalDensity；
    // 不透传就会导致弹层里 48dp 边距在 4K 上几乎贴边、560dp 输入框却占满屏（比例全乱）。
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        ProfileStrip(
            username = session.user.username,
            serverName = platformLabel,
            platformType = activeProfile.active?.type
                ?: if (session.server.guid.value != "local") ConnectType.FEINIU else null,
            focusedKey = focusedKey,
            restoredFocus = contentFocus,
            settingsFocus = settingsFocus,
            switchAccountFocus = switchAccountFocus,
            nav = nav,
            artistRowFocus = artistRowFocus,
            onFocused = { focusedKey = it },
            onSettings = {
                focusedKey = "settings"
                onSettings()
            },
            onSwitchAccount = {
                focusedKey = "switch-account"
                onManagePlatforms()
            },
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(state = listState) {
            item {
                MediaBand(
                    "歌手",
                    artists.take(8).map {
                        val key = "artist:${it.guid.value}"
                        BandEntry(it.name, "${it.trackCount ?: 0} 首", it.coverId, BandKind.Artist, key) {
                            focusedKey = key
                            onArtist(it)
                        }
                    },
                    BandEntry("全部歌手", "浏览完整列表", null, BandKind.Artist, "all-artists") {
                        focusedKey = "all-artists"
                        onArtists()
                    },
                    focusedKey,
                    contentFocus,
                    rowFocusRequester = artistRowFocus,
                    upFocusRequester = settingsFocus,
                    downFocusRequester = albumRowFocus,
                    onFocused = { focusedKey = it },
                    borderless = true,
                )
            }
            item {
                MediaBand(
                    "专辑",
                    albums.take(8).map {
                        val key = "album:${it.guid.value}"
                        BandEntry(it.name, it.artistName.orEmpty(), it.coverId, BandKind.Album, key) {
                            focusedKey = key
                            onAlbum(it)
                        }
                    },
                    BandEntry("全部专辑", "浏览完整列表", null, BandKind.Album, "all-albums") {
                        focusedKey = "all-albums"
                        onAlbums()
                    },
                    focusedKey,
                    contentFocus,
                    rowFocusRequester = albumRowFocus,
                    upFocusRequester = artistRowFocus,
                    downFocusRequester = playlistRowFocus,
                    onFocused = { focusedKey = it },
                    borderless = true,
                )
            }
            item {
                MediaBand(
                    "歌单",
                    myPlaylists.map { playlist ->
                        val key = "playlist:${playlist.guid.value}"
                        BandEntry(playlist.name, "${playlist.trackCount ?: 0} 首", playlist.coverId, BandKind.Playlist, key) {
                            focusedKey = key
                            onPlaylist(playlist)
                        }
                    },
                    BandEntry("同步歌单", "刷新最新自建歌单", null, BandKind.Playlist, "sync-playlists", glyph = PlaylistGlyph.Sync) {
                        focusedKey = "sync-playlists"
                        // 之前这里只发起刷新、不给任何反馈：数据没变化时（进入页面时
                        // ON_RESUME 已经同步过一次）用户完全看不出点了有没有生效，
                        // 失败时错误也被吞掉。这里补一条结果提示。
                        retainedStore.syncList(
                            state = playlistState,
                            loader = { container.musicRepository.playlists(forceRefresh = true) },
                            onFinished = { result ->
                                val message = result.fold(
                                    onSuccess = { count -> "歌单已同步：共 $count 个" },
                                    onFailure = { cause ->
                                        "同步失败：" + (cause.message?.takeIf(String::isNotBlank) ?: "请稍后重试")
                                    },
                                )
                                Toast.makeText(uiContext, message, Toast.LENGTH_SHORT).show()
                            },
                        )
                    },
                    focusedKey,
                    contentFocus,
                    rowFocusRequester = playlistRowFocus,
                    upFocusRequester = albumRowFocus,
                    downFocusRequester = libraryRowFocus,
                    onFocused = { focusedKey = it },
                    borderless = true,
                )
            }
            item {
                MediaBand(
                    "音乐库",
                    emptyList(),
                    BandEntry("全部歌曲", "完整曲库", null, BandKind.Library, "all-tracks") {
                        focusedKey = "all-tracks"
                        onAllTracks()
                    },
                    focusedKey,
                    contentFocus,
                    rowFocusRequester = libraryRowFocus,
                    upFocusRequester = playlistRowFocus,
                    downFocusRequester = FocusRequester.Cancel,
                    onFocused = { focusedKey = it },
                    borderless = true,
                )
            }
        }
    }

    }
}

@Composable
private fun ProfileStrip(
    username: String,
    serverName: String,
    platformType: ConnectType?,
    focusedKey: String?,
    restoredFocus: FocusRequester,
    settingsFocus: FocusRequester,
    switchAccountFocus: FocusRequester,
    nav: NavRequesters,
    artistRowFocus: FocusRequester,
    onFocused: (String) -> Unit,
    onSettings: () -> Unit,
    onSwitchAccount: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(username, Modifier.size(56.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                username.ifBlank { "音乐用户" },
                fontSize = FnType.Emphasis,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            PlatformChip(serverName, platformType)
        }
        Spacer(Modifier.width(16.dp))
        ProfileActionButton(
            label = "设置",
            width = 96.dp,
            modifier = Modifier
                .focusProperties {
                    left = FocusRequester.Cancel
                    right = switchAccountFocus
                    up = nav.home
                    down = artistRowFocus
                }
                .focusRequester(settingsFocus)
                .then(if (focusedKey == "settings") Modifier.focusRequester(restoredFocus) else Modifier)
                .onFocusChanged { if (it.isFocused) onFocused("settings") },
            onClick = onSettings,
        )
        Spacer(Modifier.width(12.dp))
        ProfileActionButton(
            label = "平台",
            width = 124.dp,
            modifier = Modifier
                .focusProperties {
                    left = settingsFocus
                    right = FocusRequester.Cancel
                    up = nav.my
                    down = artistRowFocus
                }
                .focusRequester(switchAccountFocus)
                .then(if (focusedKey == "switch-account") Modifier.focusRequester(restoredFocus) else Modifier)
                .onFocusChanged { if (it.isFocused) onFocused("switch-account") },
            onClick = onSwitchAccount,
        )
    }
}

@Composable
private fun ProfileAvatar(username: String, modifier: Modifier = Modifier) {
    Box(
        modifier.background(
            brush = Brush.linearGradient(
                listOf(Color(0xFFFF8A70), Color(0xFFD6A35E), Color(0xFF70C8AF)),
            ),
            shape = CircleShape,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            username.trim().take(1).ifBlank { "音" }.uppercase(),
            color = FnColors.Background,
            fontSize = 23.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 资料条上的「当前平台」徽章：平台图标 + 名称 + 当前平台。
 * 这是用户判断"现在看的是谁家曲库"和"切换有没有生效"最直接的反馈。
 */
@Composable
private fun PlatformChip(serverName: String, type: ConnectType?) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .widthIn(min = 140.dp, max = 320.dp)
            .height(34.dp)
            .border(0.5.dp, Color.White.copy(alpha = 0.17f), shape)
            .padding(start = 6.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (type != null) {
            PlatformIcon(type, 24.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "${serverName.ifBlank { type.displayName }} · 当前平台",
                color = Color(0xFFC7CACC),
                fontSize = 14.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.width(4.dp))
            ProfileGlyphCanvas(ProfileGlyph.Server, Modifier.size(15.dp), FnColors.Coral)
            Spacer(Modifier.width(7.dp))
            Text(
                "未连接平台 · 点「平台」添加",
                color = FnColors.Coral,
                fontSize = 14.sp,
                lineHeight = 16.sp,
                maxLines = 1,
            )
        }
    }
}

private enum class ProfileGlyph { Settings, SwitchAccount, Server }

@Composable
private fun ProfileActionButton(
    label: String,
    modifier: Modifier = Modifier,
    width: Dp = 108.dp,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(FnRadius.md)
    Button(
        onClick = onClick,
        modifier = modifier
            .width(width)
            .height(48.dp)
            .onFocusChanged { focused = it.isFocused }
            // 描边用 fnFocusRing（画在边界内），否则 TV M3 的 focusedBorder 会外扩一圈，
            // 看起来像"边框和卡片分离"，而且文字对不齐
            .fnFocusRing(focused = focused, shape = shape, cornerRadius = 8.dp),
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = FnColors.Surface,
            contentColor = FnColors.Text,
            focusedContainerColor = FnColors.Surface,
            focusedContentColor = FnColors.Text,
            pressedContainerColor = FnColors.SurfaceAlt,
            pressedContentColor = FnColors.Text,
        ),
        border = ButtonDefaults.border(
            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), shape = shape),
            focusedBorder = Border(BorderStroke(0.dp, Color.Transparent), shape = shape),
            pressedBorder = Border(BorderStroke(0.dp, Color.Transparent), shape = shape),
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun ProfileGlyphCanvas(
    glyph: ProfileGlyph,
    modifier: Modifier = Modifier,
    color: Color,
) {
    Canvas(modifier) {
        val stroke = 1.45.dp.toPx()
        when (glyph) {
            ProfileGlyph.Settings -> {
                drawCircle(color, size.minDimension * 0.24f, center, style = Stroke(stroke))
                drawCircle(color, size.minDimension * 0.06f, center)
                repeat(8) { index ->
                    val angle = index * Math.PI.toFloat() / 4f
                    val inner = size.minDimension * 0.34f
                    val outer = size.minDimension * 0.46f
                    drawLine(
                        color,
                        androidx.compose.ui.geometry.Offset(center.x + kotlin.math.cos(angle) * inner, center.y + kotlin.math.sin(angle) * inner),
                        androidx.compose.ui.geometry.Offset(center.x + kotlin.math.cos(angle) * outer, center.y + kotlin.math.sin(angle) * outer),
                        stroke,
                        StrokeCap.Round,
                    )
                }
            }
            ProfileGlyph.SwitchAccount -> {
                drawLine(color, androidx.compose.ui.geometry.Offset(size.width * 0.15f, size.height * 0.33f), androidx.compose.ui.geometry.Offset(size.width * 0.82f, size.height * 0.33f), stroke, StrokeCap.Round)
                drawLine(color, androidx.compose.ui.geometry.Offset(size.width * 0.82f, size.height * 0.33f), androidx.compose.ui.geometry.Offset(size.width * 0.66f, size.height * 0.17f), stroke, StrokeCap.Round)
                drawLine(color, androidx.compose.ui.geometry.Offset(size.width * 0.82f, size.height * 0.33f), androidx.compose.ui.geometry.Offset(size.width * 0.66f, size.height * 0.49f), stroke, StrokeCap.Round)
                drawLine(color, androidx.compose.ui.geometry.Offset(size.width * 0.85f, size.height * 0.69f), androidx.compose.ui.geometry.Offset(size.width * 0.18f, size.height * 0.69f), stroke, StrokeCap.Round)
                drawLine(color, androidx.compose.ui.geometry.Offset(size.width * 0.18f, size.height * 0.69f), androidx.compose.ui.geometry.Offset(size.width * 0.34f, size.height * 0.53f), stroke, StrokeCap.Round)
                drawLine(color, androidx.compose.ui.geometry.Offset(size.width * 0.18f, size.height * 0.69f), androidx.compose.ui.geometry.Offset(size.width * 0.34f, size.height * 0.85f), stroke, StrokeCap.Round)
            }
            ProfileGlyph.Server -> {
                val shape = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx())
                drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.06f, size.height * 0.08f), size = androidx.compose.ui.geometry.Size(size.width * 0.88f, size.height * 0.34f), cornerRadius = shape, style = Stroke(stroke))
                drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.06f, size.height * 0.58f), size = androidx.compose.ui.geometry.Size(size.width * 0.88f, size.height * 0.34f), cornerRadius = shape, style = Stroke(stroke))
            }
        }
    }
}

internal enum class BandKind { Artist, Album, Library, Playlist }

internal enum class PlaylistGlyph { All, Sync }

internal data class BandEntry(
    val title: String,
    val subtitle: String,
    val coverId: String?,
    val kind: BandKind,
    val focusKey: String,
    val glyph: PlaylistGlyph? = null,
    val action: (() -> Unit)? = null,
)

internal fun mediaBandReturnFocusKey(
    entryKeys: List<String>,
    terminalKey: String,
    lastFocusedKey: String?,
): String = lastFocusedKey
    ?.takeIf { it == terminalKey || it in entryKeys }
    ?: entryKeys.firstOrNull()
    ?: terminalKey

@Composable
internal fun MediaBand(
    title: String,
    entries: List<BandEntry>,
    terminalEntry: BandEntry,
    focusedKey: String?,
    focusRequester: FocusRequester,
    rowFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester,
    onFocused: (String) -> Unit,
    borderless: Boolean = false,
    extraTerminalEntry: BandEntry? = null,
) {
    var lastFocusedEntryKey by rememberSaveable(terminalEntry.focusKey) {
        mutableStateOf<String?>(null)
    }
    val entryKeys = entries.map(BandEntry::focusKey)
    val terminalKeys = listOfNotNull(terminalEntry.focusKey, extraTerminalEntry?.focusKey)
    val returnFocusKey = mediaBandReturnFocusKey(
        entryKeys = entryKeys,
        terminalKey = terminalKeys.firstOrNull() ?: terminalEntry.focusKey,
        lastFocusedKey = lastFocusedEntryKey,
    ).let { key -> key.takeIf { it in entryKeys || it in terminalKeys } ?: terminalEntry.focusKey }
    Column {
        Text(title, fontSize = FnType.PageTitle, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(FnGrid.Gutter),
        ) {
            itemsIndexed(entries, key = { _, entry -> entry.focusKey }) { index, entry ->
                BandLockup(
                    entry,
                    borderless = borderless,
                    Modifier
                        .focusProperties {
                            if (index == 0) left = FocusRequester.Cancel
                            up = upFocusRequester
                            down = downFocusRequester
                        }
                        .then(if (returnFocusKey == entry.focusKey) Modifier.focusRequester(rowFocusRequester) else Modifier)
                        .then(if (focusedKey == entry.focusKey) Modifier.focusRequester(focusRequester) else Modifier)
                        .onFocusChanged {
                            if (it.isFocused) {
                                lastFocusedEntryKey = entry.focusKey
                                onFocused(entry.focusKey)
                            }
                        },
                )
            }
            item {
                BandLockup(
                    terminalEntry,
                    borderless = borderless,
                    Modifier
                        .focusProperties {
                            if (entries.isEmpty()) left = FocusRequester.Cancel
                            // 有额外末尾条目（如“同步歌单”）时右向焦点放行，让焦点可移动到该条目。
                            if (extraTerminalEntry == null) right = FocusRequester.Cancel
                            up = upFocusRequester
                            down = downFocusRequester
                        }
                        .then(if (returnFocusKey == terminalEntry.focusKey) Modifier.focusRequester(rowFocusRequester) else Modifier)
                        .then(if (focusedKey == terminalEntry.focusKey) Modifier.focusRequester(focusRequester) else Modifier)
                        .onFocusChanged {
                            if (it.isFocused) {
                                lastFocusedEntryKey = terminalEntry.focusKey
                                onFocused(terminalEntry.focusKey)
                            }
                        },
                )
            }
            if (extraTerminalEntry != null) {
                item {
                BandLockup(
                    extraTerminalEntry,
                    borderless = borderless,
                        Modifier
                            .focusProperties {
                                right = FocusRequester.Cancel
                                up = upFocusRequester
                                down = downFocusRequester
                            }
                            .then(if (returnFocusKey == extraTerminalEntry.focusKey) Modifier.focusRequester(rowFocusRequester) else Modifier)
                            .then(if (focusedKey == extraTerminalEntry.focusKey) Modifier.focusRequester(focusRequester) else Modifier)
                            .onFocusChanged {
                                if (it.isFocused) {
                                    lastFocusedEntryKey = extraTerminalEntry.focusKey
                                    onFocused(extraTerminalEntry.focusKey)
                                }
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun BandLockup(entry: BandEntry, borderless: Boolean = false, modifier: Modifier = Modifier) {
    when (entry.kind) {
        BandKind.Artist -> ArtistLockup(
            entry.title,
            entry.subtitle,
            entry.coverId,
            modifier = modifier,
            enabled = entry.action != null,
            borderless = borderless,
        ) { entry.action?.invoke() }
        BandKind.Album -> AlbumLockup(
            entry.title,
            entry.subtitle,
            entry.coverId,
            modifier = modifier,
            enabled = entry.action != null,
            borderless = borderless,
        ) { entry.action?.invoke() }
        BandKind.Library -> MyLibraryLockup(
            entry.title,
            entry.subtitle,
            modifier = modifier,
            enabled = entry.action != null,
            borderless = borderless,
        ) { entry.action?.invoke() }
        BandKind.Playlist -> PlaylistBandLockup(
            entry.title,
            entry.subtitle,
            entry.coverId,
            glyph = entry.glyph,
            modifier = modifier,
            enabled = entry.action != null,
            borderless = borderless,
        ) { entry.action?.invoke() }
    }
}

@Composable
private fun MyLibraryLockup(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    borderless: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(FnRadius.md)
    val artworkShape = RoundedCornerShape(FnRadius.sm)
    Button(
        enabled = enabled,
        onClick = onClick,
        modifier = modifier
            .size(width = 232.dp, height = 132.dp)
            .focusGlow(focused = focused, cornerRadius = 10.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = lockupButtonColors(),
        border = lockupButtonBorder(shape, borderless),
        contentPadding = PaddingValues(10.dp),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            CollectionArtworkFallbackContent(
                title = title,
                fallback = CollectionArtworkFallback.Collection,
                modifier = Modifier.size(100.dp),
                shape = artworkShape,
            )
            Spacer(Modifier.width(9.dp))
            LockupLabels(title, subtitle, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PlaylistBandLockup(
    title: String,
    subtitle: String,
    coverId: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glyph: PlaylistGlyph? = null,
    borderless: Boolean = false,
    onClick: () -> Unit,
) {
    val container = LocalAuthenticatedDependencies.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(FnRadius.md)
    val artworkShape = RoundedCornerShape(FnRadius.sm)
    Button(
        enabled = enabled,
        onClick = onClick,
        modifier = modifier
            .size(width = 232.dp, height = 132.dp)
            .focusGlow(focused = focused, cornerRadius = 10.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = lockupButtonColors(),
        border = lockupButtonBorder(shape, borderless),
        contentPadding = PaddingValues(10.dp),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            when {
                glyph != null -> PlaylistGlyphIcon(
                    glyph = glyph,
                    color = FnColors.Coral,
                    modifier = Modifier.size(88.dp).background(Color(0xFF2D4A46), artworkShape),
                )
                coverId != null -> RemoteArtwork(
                    container = container,
                    coverId = coverId,
                    variant = CoverVariant.Compact,
                    modifier = Modifier.size(88.dp),
                    shape = artworkShape,
                    contentScale = ContentScale.Crop,
                    placeholderContent = {
                        InitialArtworkPlaceholder(title, FnColors.Coral, Modifier.size(88.dp), artworkShape)
                    },
                )
                else -> InitialArtworkPlaceholder(title, FnColors.Coral, Modifier.size(88.dp), artworkShape)
            }
            Spacer(Modifier.width(10.dp))
            LockupLabels(title, subtitle, Modifier.weight(1f))
        }
    }
}

/** “全部歌单 / 同步歌单”操作条目的图标：全部=四宫格，同步=双向环形箭头。 */
@Composable
private fun PlaylistGlyphIcon(
    glyph: PlaylistGlyph,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        when (glyph) {
            PlaylistGlyph.All -> {
                // 四宫格：2x2 圆角方块
                val cell = size.minDimension * 0.30f
                val gap = size.minDimension * 0.12f
                val left = (size.width - cell * 2 - gap) / 2
                val top = (size.height - cell * 2 - gap) / 2
                listOf(
                    androidx.compose.ui.geometry.Offset(left, top),
                    androidx.compose.ui.geometry.Offset(left + cell + gap, top),
                    androidx.compose.ui.geometry.Offset(left, top + cell + gap),
                    androidx.compose.ui.geometry.Offset(left + cell + gap, top + cell + gap),
                ).forEach { origin ->
                    drawRoundRect(
                        color = color,
                        topLeft = origin,
                        size = androidx.compose.ui.geometry.Size(cell, cell),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cell * 0.22f),
                    )
                }
            }
            PlaylistGlyph.Sync -> {
                // 双向环形刷新箭头：两条相对的圆弧
                val stroke = 2.2.dp.toPx()
                val radius = size.minDimension * 0.34f
                val center = this.center
                val rect = androidx.compose.ui.geometry.Rect(
                    center.x - radius,
                    center.y - radius,
                    center.x + radius,
                    center.y + radius,
                )
                drawArc(
                    color = color,
                    startAngle = 205f,
                    sweepAngle = 190f,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(rect.left, rect.top),
                    size = androidx.compose.ui.geometry.Size(rect.width, rect.height),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = color,
                    startAngle = 25f,
                    sweepAngle = -190f,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(rect.left, rect.top),
                    size = androidx.compose.ui.geometry.Size(rect.width, rect.height),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
private fun AllPlaylists(
    container: AuthenticatedAppDependencies,
    playback: PlaybackUiState,
    onHome: () -> Unit,
    onAlbums: () -> Unit,
    onMy: () -> Unit,
    onSearch: () -> Unit,
    onPlayer: () -> Unit,
    onOpen: (Playlist) -> Unit,
) {
    // 与首页同一套口径：按可用宽度算列数（960 画布 + 48dp 边距 → 5 列）。
    // 排序模式的四向移动依赖这个值，所以必须与 GridCells 用同一个来源。
    val playlistColumns = FnGrid.columnsFor(
        rememberCanvasWidthDp() - FnCanvas.MinSafeHorizontal.value * 2 - FnGrid.ContentInsetDp,
    )
    val retainedStore = LocalLibraryRetainedState.current
    val playlistState = retainedStore.list<Playlist>("playlists")
    val playlists = playlistState.snapshot.entries
    val preferences by container.appPreferences.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        retainedStore.loadListOnce(playlistState, container.musicRepository::playlists)
    }
    // 用户自定义排序（guid 草稿）：非空时用其显示；为空时用“用户排序 + 服务器顺序”的基准顺序。
    var draftGuids by remember(playlists, preferences.playlistOrder) {
        mutableStateOf<List<String>?>(null)
    }
    val baseOrdered = applyPlaylistOrder(playlists, preferences.playlistOrder)
    val displayList = if (draftGuids != null) {
        draftGuids.orEmpty().mapNotNull { guid -> playlists.firstOrNull { it.guid.value == guid } }
    } else {
        baseOrdered
    }
    var reordering by remember { mutableStateOf(false) }
    var pickedUp by remember { mutableStateOf(false) }
    var focusedIndex by remember { mutableStateOf(0) }
    var centerDownAt by remember { mutableStateOf(0L) }
    val gridState = rememberLazyGridState()
    val focusRequesters = remember(displayList.size) { List(displayList.size) { FocusRequester() } }
    val nav = LocalNavRequesters.current
    var initialFocusRequested by remember { mutableStateOf(false) }

    fun enterReorder() {
        if (displayList.isEmpty()) return
        draftGuids = draftGuids ?: displayList.map { it.guid.value }
        reordering = true
        pickedUp = false
    }

    fun commitReorder() {
        draftGuids?.let { container.appPreferences.setPlaylistOrder(it) }
        draftGuids = null
        reordering = false
        pickedUp = false
    }

    fun cancelReorder() {
        draftGuids = null
        reordering = false
        pickedUp = false
    }

    /** 两段式排序：确定键拾起 → 方向键移动（网格四向）→ 确定键放下。 */
    fun moveBy(rowDelta: Int, columnDelta: Int) {
        if (!reordering) return
        val from = focusedIndex
        val to = (from + rowDelta * playlistColumns + columnDelta)
            .coerceIn(0, displayList.lastIndex.coerceAtLeast(0))
        if (to == from) return
        val guids = (draftGuids ?: displayList.map { it.guid.value }).toMutableList()
        val moved = guids.removeAt(from)
        guids.add(to, moved)
        draftGuids = guids
        focusedIndex = to
        runCatching { focusRequesters[to].requestFocus() }
        scope.launch { runCatching { gridState.animateScrollToItem(to) } }
    }

    // 从顶部导航切进来时，导航条常驻、焦点本来就停在「歌单」Tab 上，只需消费标记不抢焦点
    val navFocusController = LocalNavFocusController.current
    LaunchedEffect(navFocusController.pending) {
        if (navFocusController.pending != null) {
            initialFocusRequested = true
            navFocusController.onConsumed()
        }
    }
    LaunchedEffect(displayList.size, focusedIndex, reordering) {
        if (navFocusController.pending != null) return@LaunchedEffect
        if (displayList.isEmpty() || initialFocusRequested && !reordering) return@LaunchedEffect
        val target = if (reordering) focusedIndex else focusedIndex.coerceIn(0, displayList.lastIndex.coerceAtLeast(0))
        runCatching { focusRequesters[target].requestFocus() }
        if (!reordering) initialFocusRequested = true
    }

    Box(
        Modifier
            .fillMaxSize()
            // 与首页统一为 48dp（官方过扫描下限）：两页同宽，翻页时卡片不会左右跳动
            .padding(horizontal = 48.dp, vertical = 28.dp)
            .onPreviewKeyEvent { event ->
                val key = event.key
                val type = event.type
                if (key == Key.DirectionCenter || key == Key.Enter) {
                    if (type == KeyEventType.KeyDown) {
                        // 只记录首次按下的时间；遥控器长按会触发按键自动重复，不能每次都重置计时。
                        if (centerDownAt == 0L) centerDownAt = SystemClock.elapsedRealtime()
                    } else if (type == KeyEventType.KeyUp) {
                        val held = SystemClock.elapsedRealtime() - centerDownAt
                        when {
                            reordering && pickedUp -> pickedUp = false
                            reordering -> pickedUp = true
                            held >= REORDER_LONG_PRESS_THRESHOLD_MS -> enterReorder()
                            else -> displayList.getOrNull(focusedIndex)?.let(onOpen)
                        }
                        centerDownAt = 0L
                    }
                    return@onPreviewKeyEvent true
                }
                if (reordering && (key == Key.DirectionLeft || key == Key.DirectionRight ||
                        key == Key.DirectionUp || key == Key.DirectionDown)) {
                    if (type == KeyEventType.KeyDown) {
                        moveBy(
                            rowDelta = when (key) {
                                Key.DirectionDown -> 1
                                Key.DirectionUp -> -1
                                else -> 0
                            },
                            columnDelta = when (key) {
                                Key.DirectionRight -> 1
                                Key.DirectionLeft -> -1
                                else -> 0
                            },
                        )
                    }
                    return@onPreviewKeyEvent true
                }
                if (key == Key.Back && type == KeyEventType.KeyDown) {
                    if (reordering) {
                        commitReorder()
                        return@onPreviewKeyEvent true
                    }
                }
                false
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("歌单", fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            reordering && pickedUp -> "移动中：方向键调整位置，按确定键放下，返回键保存"
                            reordering -> "排序模式：方向键选择歌单，按确定键拾起"
                            else -> "共 ${displayList.size} 个歌单 · 全部铺开 · 长按确定键进入排序"
                        },
                        color = FnColors.Muted,
                        fontSize = 14.sp,
                    )
                }
                if (reordering) {
                    Text(
                        if (pickedUp) "移动中…" else "排序中…",
                        color = FnColors.Coral,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            if (displayList.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("还没有歌单", color = FnColors.Muted, fontSize = 19.sp)
                }
            } else {
                // 与「全部专辑」同一套吸附规则：底部只剩一条切边时收掉这半行，
                // 否则最后一行会被页面底边横着切一半（文字正好被切断）。
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(playlistColumns),
                        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(catalogGridHeight(maxHeight, PlaylistTileHeight, gutter = 18.dp)),
                    ) {
                        gridItemsIndexed(
                            displayList,
                            key = { _, playlist -> playlist.guid.value },
                        ) { index, playlist ->
                            val isPicked = reordering && pickedUp && index == focusedIndex
                            HomePlaylistLockup(
                                title = playlist.name,
                                subtitle = "${playlist.trackCount ?: 0} 首",
                                coverId = playlist.coverId,
                                modifier = Modifier
                                    .focusRequester(focusRequesters[index])
                                    .then(
                                        if (reordering) {
                                            Modifier.focusProperties {
                                                up = FocusRequester.Cancel
                                                down = FocusRequester.Cancel
                                                left = FocusRequester.Cancel
                                                right = FocusRequester.Cancel
                                            }
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .then(
                                        if (isPicked) {
                                            Modifier.graphicsLayer {
                                                alpha = 0.55f
                                                scaleX = 1.05f
                                                scaleY = 1.05f
                                            }
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .onFocusChanged { if (it.isFocused) focusedIndex = index },
                                onClick = {
                                    focusedIndex = index
                                    onOpen(playlist)
                                },
                            )
                        }
                        item(span = { GridItemSpan(maxLineSpan) }, key = "all-playlists-footer") {
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            }
        }
    }
}


private const val REORDER_LONG_PRESS_THRESHOLD_MS = 600L

@Composable
private fun ArtistGrid(container: AuthenticatedAppDependencies, onOpen: (Artist) -> Unit) {
    PagedCatalogPage(
        stateKey = "artists",
        title = "全部歌手",
        totalLabel = { "$it 位歌手" },
        loader = { page -> container.musicRepository.artists(page, FULL_CATALOG_PAGE_SIZE) },
        key = { it.guid.value },
    ) { artist, modifier ->
        ArtistLockup(artist.name, "${artist.trackCount ?: 0} 首", artist.coverId, modifier = modifier) { onOpen(artist) }
    }
}

@Composable
private fun AlbumGrid(container: AuthenticatedAppDependencies, onOpen: (Album) -> Unit) {
    // 文字块高度随系统字体缩放变化，行高必须用同一个值算，否则「整行吸附」会算错
    val metrics = rememberAlbumPosterMetrics()
    PagedCatalogPage(
        stateKey = "albums",
        title = "全部专辑",
        totalLabel = { "$it 张专辑" },
        // 列数按海报卡的目标宽度算（960 画布 → 6 列、每列 131dp），
        // 与卡片自身的宽度来源保持同一个值，否则又会「算 5 列、卡片按 6 列画」。
        cardWidth = FnGrid.PosterCardWidth,
        // 海报卡高 = 列宽（封面 1:1） + 文字块，交给网格做整行吸附，
        // 否则底部会留下一条只露封面顶边的「切边」
        rowHeightFor = { columnWidth -> columnWidth + metrics.textBlock },
        loader = { page -> container.musicRepository.albums(page, FULL_CATALOG_PAGE_SIZE) },
        key = { it.guid.value },
    ) { album, modifier ->
        AlbumPosterCard(album.name, album.artistName.orEmpty(), album.coverId, modifier = modifier) { onOpen(album) }
    }
}

@Composable
private fun <T> PagedCatalogPage(
    stateKey: String,
    title: String,
    totalLabel: (Int) -> String,
    loader: suspend (Int) -> Page<T>,
    key: (T) -> String,
    cardWidth: Dp = FnGrid.MinCardWidth,
    /**
     * 已知卡片行高时传入（入参是每列实际宽度），用于「整行吸附」：
     * 底部只剩一条切边时直接收掉这半行。不传则保持原来的 `weight(1f)` 撑满。
     */
    rowHeightFor: ((columnWidth: Dp) -> Dp)? = null,
    item: @Composable (T, Modifier) -> Unit,
) {
    val retainedStore = LocalLibraryRetainedState.current
    val retained = retainedStore.paged<T>("grid:$stateKey")
    val snapshot = retained.snapshot
    val entries = snapshot.entries
    var focusedKey by rememberSaveable(stateKey) { mutableStateOf<String?>(null) }
    var initialFocusRequested by remember(stateKey) { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val contentFocus = remember(stateKey) { FocusRequester() }
    val retryFocus = remember(stateKey) { FocusRequester() }

    fun load(target: Int) {
        if (retained.loading || (target > 1 && !retained.snapshot.hasNext)) return
        retained.loading = true
        retainedStore.scope.launch {
            runCatching { loader(target) }
                .onSuccess { retained.snapshot = retainLoadedPage(retained.snapshot, it, key) }
                .onFailure {
                    retained.snapshot = retained.snapshot.copy(
                        error = (it as? AppException)?.error ?: AppError.Unknown(),
                        initialLoadCompleted = retained.snapshot.initialLoadCompleted || target == 1,
                    )
                }
            retained.loading = false
        }
    }

    LaunchedEffect(stateKey) {
        if (shouldLoadInitialPage(retained.snapshot)) load(1)
    }

    val navFocus = LocalNavFocusController.current
    LaunchedEffect(snapshot.initialLoadCompleted, entries.size, focusedKey, navFocus.pending) {
        if (!snapshot.initialLoadCompleted || entries.isEmpty() || initialFocusRequested) return@LaunchedEffect
        // 从导航条切进来（专辑 Tab）时，焦点必须留在导航条上，不能掉进网格
        if (navFocus.pending != null) {
            initialFocusRequested = true
            navFocus.onConsumed()
            return@LaunchedEffect
        }
        val target = focusedKey?.let { retainedKey -> entries.firstOrNull { key(it) == retainedKey } }
            ?: entries.first()
        focusedKey = key(target)
        yield()
        runCatching { contentFocus.requestFocus() }
        initialFocusRequested = true
    }

    // 连续滚动：滚到接近底部时自动加载下一页，去掉「上一页 / 下一页」按钮。
    LaunchedEffect(gridState, snapshot.initialLoadCompleted, snapshot.page, snapshot.hasNext, entries.size) {
        if (!snapshot.initialLoadCompleted) return@LaunchedEffect
        snapshotFlow {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible to info.totalItemsCount
        }.collect { (lastVisible, total) ->
            if (total > 0 && lastVisible >= total - 4) {
                load(retained.snapshot.page + 1)
            }
        }
    }

    // 与首页 / 歌单页统一口径：48dp 页边距 + 自适应列数（960 画布 → 5 列），
    // 否则专辑页会比别的页少一列、卡也更宽，翻页时明显不协调。
    val availableWidthDp =
        rememberCanvasWidthDp() - FnCanvas.MinSafeHorizontal.value * 2 - FnGrid.ContentInsetDp
    val columns = FnGrid.columnsFor(availableWidthDp, targetCardWidth = cardWidth)
    // 每列实际宽度：整行吸附要靠它反推行高（海报卡高 = 列宽 + 文字块）
    val columnWidth =
        ((availableWidthDp - (columns - 1) * FnGrid.Gutter.value) / columns).dp
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp).padding(top = 20.dp, bottom = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            snapshot.total?.let { total ->
                Spacer(Modifier.width(14.dp))
                Text(totalLabel(total), color = FnColors.Muted, fontSize = 12.sp)
            }
            snapshot.error?.let { error ->
                Spacer(Modifier.width(18.dp))
                Text(appErrorMessage(error), color = FnColors.Coral, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(FnGrid.Gutter),
                verticalArrangement = Arrangement.spacedBy(FnGrid.Gutter),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(catalogGridHeight(maxHeight, rowHeightFor?.invoke(columnWidth))),
            ) {
                items(entries, key = { key(it) }) { entry ->
                    val entryKey = key(entry)
                    item(
                        entry,
                        Modifier
                            .then(if (focusedKey == entryKey) Modifier.focusRequester(contentFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) focusedKey = entryKey },
                    )
                }
                if (entries.isEmpty() && snapshot.error != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        CatalogRetryButton(
                            loading = retained.loading,
                            modifier = Modifier.focusRequester(retryFocus),
                        ) { load(1) }
                    }
                }
                if (retained.loading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("正在加载更多…", color = FnColors.Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/**
 * 网格可视高度：能放几整行就放几整行。
 *
 * 底部只剩「半行的一小条」（专辑页实测约 10dp，只露出封面的一道顶边）时，
 * 看起来像排版被切坏，所以剩余空间不足一行 40% 时直接收掉这半行，
 * 把余量并入底部留白；剩余空间足够（≥ 40%）仍保留半行，作为「下面还有内容」的提示。
 *
 * [available] 是外层可用高度，[rowHeight] 为卡片行高（含封面与文字块），null 表示不吸附。
 */
private fun catalogGridHeight(available: Dp, rowHeight: Dp?, gutter: Dp = FnGrid.Gutter): Dp {
    if (rowHeight == null || rowHeight <= 0.dp) return available
    val verticalInset = 8.dp // contentPadding 上下各 4dp
    val step = rowHeight + gutter
    if (step <= 0.dp) return available
    val rows = ((available - verticalInset + gutter) / step).toInt().coerceAtLeast(1)
    val fullHeight = step * rows.toFloat() - gutter + verticalInset
    // 一行的整数倍反而更高（可用高度还不够一行）时不做吸附，交给系统裁剪
    if (fullHeight >= available) return available
    return if (available - fullHeight < rowHeight * 0.4f) fullHeight else available
}

@Composable
private fun <T> GridPage(
    title: String,
    entries: List<T>,
    key: (T) -> String,
    item: @Composable (T, Modifier) -> Unit,
) {
    var focusedKey by rememberSaveable(title) { mutableStateOf<String?>(null) }
    var initialFocusRequested by remember(title) { mutableStateOf(false) }
    val contentFocus = remember(title) { FocusRequester() }
    val gridState = rememberLazyGridState()
    LaunchedEffect(entries, focusedKey) {
        if (entries.isEmpty() || initialFocusRequested) return@LaunchedEffect
        val keys = entries.map(key)
        focusedKey = focusedKey?.takeIf(keys::contains) ?: keys.first()
        yield()
        runCatching { contentFocus.requestFocus() }
        initialFocusRequested = true
    }
    val columns = FnGrid.columnsFor(
        rememberCanvasWidthDp() - FnCanvas.MinSafeHorizontal.value * 2 - FnGrid.ContentInsetDp,
    )
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp).padding(top = 20.dp, bottom = 28.dp)) {
        Text(title, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(FnGrid.Gutter),
            verticalArrangement = Arrangement.spacedBy(FnGrid.Gutter),
        ) {
            items(entries, key = key) { entry ->
                val entryKey = key(entry)
                item(
                    entry,
                    Modifier.then(if (focusedKey == entryKey) Modifier.focusRequester(contentFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) focusedKey = entryKey },
                )
            }
        }
    }
}

private enum class ArtistDetailSection { Songs, Albums }

private data class TrackDetailTab(
    val key: String,
    val label: String,
    val selected: Boolean,
    val onFocus: () -> Unit,
    val onSelect: () -> Unit,
)

private data class TrackDetailHeader(
    val kind: String,
    val declaredTrackCount: Int? = null,
    val extraMetadata: String? = null,
    val artworkFallback: CollectionArtworkFallback = CollectionArtworkFallback.Initial,
    val tabs: List<TrackDetailTab> = emptyList(),
    val onBack: () -> Unit,
)

private enum class CollectionArtworkFallback { Initial, Artist, Favorites, Collection }

private sealed interface TrackCollectionPrimaryAction {
    data object PlayAll : TrackCollectionPrimaryAction
    data class StartRoam(val onStarted: () -> Unit) : TrackCollectionPrimaryAction
}

@Composable
private fun ArtistDetail(
    container: AuthenticatedAppDependencies,
    artist: Artist,
    onBack: () -> Unit,
    onAlbum: (Album) -> Unit,
    onPlayer: (Track) -> Unit,
) {
    val stateKey = "artist:${artist.guid.value}"
    val retainedStore = LocalLibraryRetainedState.current
    val albumState = retainedStore.paged<Album>("$stateKey:albums")
    val albumSnapshot = albumState.snapshot
    val albums = albumSnapshot.entries
    var selectedSection by rememberSaveable(stateKey) { mutableStateOf(ArtistDetailSection.Songs) }
    var focusedArea by rememberSaveable(stateKey) { mutableStateOf("songs") }
    fun loadAlbums(target: Int) {
        if (albumState.loading || target > 1 && !albumState.snapshot.hasNext) return
        albumState.loading = true
        retainedStore.scope.launch {
            runCatching { container.musicRepository.artistAlbums(artist.guid.value, target) }
                .onSuccess {
                    albumState.snapshot = retainLoadedPage(albumState.snapshot, it) { album -> album.guid.value }
                }
                .onFailure {
                    albumState.snapshot = albumState.snapshot.copy(
                        error = (it as? AppException)?.error ?: AppError.Unknown(),
                        initialLoadCompleted = albumState.snapshot.initialLoadCompleted || target == 1,
                    )
                }
            albumState.loading = false
        }
    }
    LaunchedEffect(stateKey) {
        if (shouldLoadInitialPage(albumState.snapshot)) loadAlbums(1)
    }
    val tabs = ArtistDetailSection.entries.map { section ->
        TrackDetailTab(
            key = "artist-tab:${section.name}",
            label = if (section == ArtistDetailSection.Songs) "歌曲" else "专辑",
            selected = selectedSection == section,
            onFocus = { focusedArea = "tabs" },
            onSelect = {
                selectedSection = section
                focusedArea = "tabs"
            },
        )
    }
    TrackCollection(
        container = container,
        stateKey = "$stateKey:tracks",
        title = artist.name,
        coverId = artist.coverId,
        loader = { container.musicRepository.artistTracks(artist.guid.value, it) },
        queueSource = { sort -> QueueSource.Artist(artist.guid.value, sort) },
        onPlayer = onPlayer,
        initialFocusEnabled = selectedSection == ArtistDetailSection.Songs && focusedArea == "songs",
        onFocusOwnerChanged = { focusedArea = "songs" },
        detailHeader = TrackDetailHeader(
            kind = "歌手",
            declaredTrackCount = artist.trackCount,
            extraMetadata = artist.albumCount?.let { "$it 张专辑" },
            artworkFallback = CollectionArtworkFallback.Artist,
            tabs = tabs,
            onBack = onBack,
        ),
        showTrackList = selectedSection == ArtistDetailSection.Songs,
        alternateContent = {
            ArtistAlbumGrid(
                stateKey = "$stateKey:album-grid",
                snapshot = albumSnapshot,
                loading = albumState.loading,
                initialFocusEnabled = selectedSection == ArtistDetailSection.Albums && focusedArea == "albums",
                onLoad = ::loadAlbums,
                onFocused = { focusedArea = "albums" },
                onAlbum = onAlbum,
            )
        },
    )
}

@Composable
private fun ArtistAlbumGrid(
    stateKey: String,
    snapshot: RetainedPageSnapshot<Album>,
    loading: Boolean,
    initialFocusEnabled: Boolean,
    onLoad: (Int) -> Unit,
    onFocused: () -> Unit,
    onAlbum: (Album) -> Unit,
) {
    val albums = snapshot.entries
    var focusedKey by rememberSaveable(stateKey) { mutableStateOf<String?>(null) }
    var initialFocusRequested by remember(stateKey) { mutableStateOf(false) }
    val restoredFocus = remember(stateKey) { FocusRequester() }
    val gridState = rememberLazyGridState()
    val playerRouteOpen = LocalPlayerRouteOpen.current
    LaunchedEffect(snapshot.initialLoadCompleted, albums, initialFocusEnabled, focusedKey) {
        // 播放页在上层时不抢焦点，交给播放页（否则方向键会被这里吃掉）
        if (playerRouteOpen) return@LaunchedEffect
        if (!initialFocusEnabled || !snapshot.initialLoadCompleted || albums.isEmpty() || initialFocusRequested) return@LaunchedEffect
        val availableKeys = albums.map { it.guid.value }
        val targetKey = focusedKey?.takeIf(availableKeys::contains) ?: availableKeys.first()
        if (focusedKey != targetKey) {
            focusedKey = targetKey
            return@LaunchedEffect
        }
        repeat(3) {
            withFrameNanos { }
            if (runCatching { restoredFocus.requestFocus() }.getOrDefault(false)) {
                initialFocusRequested = true
                return@LaunchedEffect
            }
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = Modifier.fillMaxSize().padding(top = 12.dp),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (albums.isEmpty() && loading) {
            // 骨架屏：占位块的尺寸/排布与真实专辑卡一致，首屏加载不再“先空一片再弹出”
            items(6) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(106.dp)
                        .background(FnColors.Surface, RoundedCornerShape(FnRadius.sm))
                        .padding(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FnSkeleton(Modifier.size(88.dp), shape = RoundedCornerShape(FnRadius.sm))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        FnSkeleton(
                            Modifier.fillMaxWidth(0.62f).height(15.dp),
                            shape = RoundedCornerShape(FnRadius.sm),
                        )
                        Spacer(Modifier.height(8.dp))
                        FnSkeleton(
                            Modifier.fillMaxWidth(0.38f).height(11.dp),
                            shape = RoundedCornerShape(FnRadius.sm),
                        )
                    }
                }
            }
        }
        items(albums, key = { it.guid.value }) { album ->
            val index = albums.indexOf(album)
            DetailAlbumCard(
                album = album,
                modifier = Modifier
                    .then(if (focusedKey == album.guid.value) Modifier.focusRequester(restoredFocus) else Modifier)
                    .onFocusChanged {
                        if (it.isFocused) {
                            focusedKey = album.guid.value
                            onFocused()
                            if (snapshot.hasNext && index >= albums.size - 6) onLoad(snapshot.page + 1)
                        }
                    },
                onClick = {
                    focusedKey = album.guid.value
                    onAlbum(album)
                },
            )
        }
        if (albums.isEmpty() && !loading && snapshot.error != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Button(onClick = { onLoad(1) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("专辑加载失败，重试")
                }
            }
        }
        if (snapshot.hasNext) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Button(
                    enabled = !loading,
                    onClick = { onLoad(snapshot.page + 1) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(if (loading) "正在加载" else "加载更多专辑")
                }
            }
        }
    }
}

@Composable
private fun DetailAlbumCard(album: Album, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val container = LocalAuthenticatedDependencies.current
    val albumCoverId = album.coverId
    val shape = RoundedCornerShape(FnRadius.sm)
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(106.dp)
            .onFocusChanged { state ->
                if (state.isFocused && albumCoverId != null) {
                    container.artworkBitmapCache.prefetch(albumCoverId, CoverVariant.Grid)
                }
            },
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = FnColors.Surface,
            contentColor = FnColors.Text,
            focusedContainerColor = FnColors.FocusFill,
            focusedContentColor = FnColors.Text,
            pressedContainerColor = FnColors.FocusFill,
            pressedContentColor = FnColors.Text,
        ),
        contentPadding = PaddingValues(9.dp),
    ) {
        val contentColor = LocalContentColor.current
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            val artworkShape = RoundedCornerShape(FnRadius.sm)
            if (albumCoverId != null) {
                RemoteArtwork(
                    container = container,
                    coverId = albumCoverId,
                    variant = CoverVariant.Compact,
                    modifier = Modifier.size(88.dp),
                    shape = artworkShape,
                    contentScale = ContentScale.Crop,
                    placeholderContent = {
                        InitialArtworkPlaceholder(album.name, FnColors.Coral, Modifier.fillMaxSize(), artworkShape)
                    },
                )
            } else {
                InitialArtworkPlaceholder(album.name, FnColors.Coral, Modifier.size(88.dp), artworkShape)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    album.name,
                    color = contentColor,
                    fontSize = 16.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val metadata = listOfNotNull(
                    // 日期只保留年份：完整日期 + 首数在 110dp 标签区内必被截断
                    album.releaseDate?.takeIf(String::isNotBlank)?.take(4),
                    album.trackCount?.let { "$it 首" },
                ).joinToString(" · ")
                if (metadata.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        metadata,
                        color = contentColor.copy(alpha = 0.68f),
                        fontSize = FnType.Caption,
                        lineHeight = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackCollection(
    container: AuthenticatedAppDependencies,
    stateKey: String,
    title: String,
    subtitle: String = "",
    coverId: String? = null,
    loader: suspend (Int) -> Page<Track>,
    queueSource: (String) -> QueueSource,
    onPlayer: (Track) -> Unit,
    initialFocusEnabled: Boolean = true,
    onFocusOwnerChanged: () -> Unit = {},
    detailHeader: TrackDetailHeader,
    primaryAction: TrackCollectionPrimaryAction = TrackCollectionPrimaryAction.PlayAll,
    showTrackList: Boolean = true,
    alternateContent: @Composable () -> Unit = {},
    contentRevision: Long = 0L,
    emptyMessage: String = "暂无歌曲",
) {
    val retainedStore = LocalLibraryRetainedState.current
    val retained = retainedStore.tracks(stateKey)
    val snapshot = retained.snapshot
    val tracks = snapshot.tracks
    val loadedPages = snapshot.loadedPages
    val page = snapshot.page
    val hasNext = snapshot.hasNext
    val loading = retained.loading
    val error = snapshot.error
    val expectedTotal = snapshot.expectedTotal
    val expectedSort = snapshot.expectedSort
    var focusedKey by rememberSaveable(stateKey) { mutableStateOf<String?>(null) }
    var initialFocusRequested by remember(stateKey) { mutableStateOf(false) }
    val restoredFocus = remember(stateKey) { FocusRequester() }
    val listState = rememberLazyListState()
    val actionScope = rememberCoroutineScope()
    var primaryActionRunning by remember(stateKey) { mutableStateOf(false) }
    var loadedContentRevision by rememberSaveable(stateKey) { mutableStateOf<Long?>(null) }
    fun setError(value: AppError?) {
        retained.snapshot = retained.snapshot.copy(error = value)
    }
    fun load(target: Int) {
        if (retained.loading) return
        retained.loading = true
        retainedStore.scope.launch {
            runCatching { loader(target) }
                .onSuccess {
                    retained.snapshot = retainTrackCollectionPage(retained.snapshot, it, target)
                }
                .onFailure {
                    retained.snapshot = retained.snapshot.copy(
                        error = (it as? AppException)?.error ?: AppError.Unknown(),
                        initialLoadCompleted = retained.snapshot.initialLoadCompleted || target == 1,
                    )
                }
            retained.loading = false
        }
    }
    fun play(index: Int) {
        val target = tracks.getOrNull(index)?.takeIf(::isTrackPlayable) ?: return
        val window = exactTrackQueueWindow(loadedPages, index) ?: run {
            setError(AppError.CollectionChanged)
            return
        }
        val queue = container.musicRepository.prepareQueue(window.items)
        val queueIds = queue.map { it.track.guid.value }
        val segmentIds = window.segments.flatMap(QueuePageSegment::mediaIds)
        val queueIndex = queue.indexOfFirst { it.track.guid == target.guid }
        if (queueIds != segmentIds || queueIndex < 0) {
            setError(AppError.TranscodeUnavailable)
            return
        }
        if (queue.isEmpty()) {
            setError(AppError.TranscodeUnavailable)
            return
        }
        val sort = expectedSort ?: run {
            setError(AppError.CollectionChanged)
            return
        }
        actionScope.launch {
            val transition = runCatching {
                container.playbackController.playQueue(
                    tracks = queue,
                    startIndex = queueIndex,
                    source = queueSource(sort),
                    windowStart = window.segments.first().sourceStartIndex,
                    firstPage = window.segments.first().page,
                    lastPage = window.segments.last().page,
                    knownTotal = expectedTotal,
                    segments = window.segments,
                )
            }.getOrElse {
                setError(AppError.Unknown(it.message))
                return@launch
            }
            if (transition == null) {
                setError(AppError.TranscodeUnavailable)
                return@launch
            }
            runCatching { transition.awaitCommitted() }
                .onSuccess {
                    setError(null)
                    onPlayer(target)
                }
                .onFailure { setError(AppError.Unknown(it.message)) }
        }
    }
    fun runPrimaryAction() {
        when (val action = primaryAction) {
            TrackCollectionPrimaryAction.PlayAll -> play(tracks.indexOfFirst(::isTrackPlayable))
            is TrackCollectionPrimaryAction.StartRoam -> {
                if (primaryActionRunning) return
                if (container.playbackController.state.value.queueKind == QueueKind.Roam) {
                    action.onStarted()
                    return
                }
                primaryActionRunning = true
                actionScope.launch {
                    if (container.playbackController.startRoam()) {
                        setError(null)
                        action.onStarted()
                    } else {
                        setError(container.playbackController.state.value.roamError ?: AppError.Unknown())
                    }
                    primaryActionRunning = false
                }
            }
        }
    }
    LaunchedEffect(stateKey, contentRevision) {
        if (loadedContentRevision != null && loadedContentRevision != contentRevision) {
            retained.snapshot = RetainedTrackCollectionSnapshot()
            initialFocusRequested = false
        }
        loadedContentRevision = contentRevision
        if (!retained.snapshot.initialLoadCompleted) {
            load(1)
        }
    }
    val playableTracks = tracks.filter(::isTrackPlayable)
    val primaryActionEnabled = when (primaryAction) {
        TrackCollectionPrimaryAction.PlayAll -> playableTracks.isNotEmpty()
        is TrackCollectionPrimaryAction.StartRoam -> true
    }
    val primaryActionLabel = when (primaryAction) {
        TrackCollectionPrimaryAction.PlayAll -> "播放全部"
        is TrackCollectionPrimaryAction.StartRoam -> "随机漫游"
    }
    LaunchedEffect(snapshot.initialLoadCompleted, tracks, focusedKey, initialFocusEnabled, showTrackList, primaryActionEnabled) {
        if (!initialFocusEnabled || !snapshot.initialLoadCompleted || initialFocusRequested) return@LaunchedEffect
        val availableKeys = buildList {
            if (primaryActionEnabled) add("primary-action")
            detailHeader.tabs.filter { it.selected }.forEach { add(it.key) }
            if (showTrackList) addAll(playableTracks.map { it.guid.value })
            add("detail-back")
        }
        val targetKey = focusedKey?.takeIf(availableKeys::contains) ?: availableKeys.firstOrNull()
        if (focusedKey != targetKey) {
            focusedKey = targetKey
            return@LaunchedEffect
        }
        if (targetKey != null) {
            repeat(3) {
                withFrameNanos { }
                if (runCatching { restoredFocus.requestFocus() }.getOrDefault(false)) {
                    initialFocusRequested = true
                    return@LaunchedEffect
                }
            }
        }
    }
    DetailTrackCollection(
        container = container,
        header = detailHeader,
        title = title,
        subtitle = subtitle,
        coverId = coverId,
        trackCount = detailHeader.declaredTrackCount ?: expectedTotal?.takeIf { it > 0 },
        tracks = tracks,
        loading = loading,
        error = error,
        hasNext = hasNext,
        listState = listState,
        focusedKey = focusedKey,
        restoredFocus = restoredFocus,
        primaryActionLabel = primaryActionLabel,
        primaryActionEnabled = primaryActionEnabled,
        showTrackList = showTrackList,
        onFocusKey = {
            focusedKey = it
            onFocusOwnerChanged()
        },
        onPrimaryAction = ::runPrimaryAction,
        onTrackFocused = { index, key ->
            focusedKey = key
            onFocusOwnerChanged()
            if (hasNext && index >= tracks.size - 15) load(page + 1)
        },
        onTrack = ::play,
        onLoadMore = { load(page + 1) },
        alternateContent = alternateContent,
        emptyMessage = emptyMessage,
    )
}

@Composable
private fun DetailTrackCollection(
    container: AuthenticatedAppDependencies,
    header: TrackDetailHeader,
    title: String,
    subtitle: String,
    coverId: String?,
    trackCount: Int?,
    tracks: List<Track>,
    loading: Boolean,
    error: AppError?,
    hasNext: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    focusedKey: String?,
    restoredFocus: FocusRequester,
    primaryActionLabel: String,
    primaryActionEnabled: Boolean,
    showTrackList: Boolean,
    onFocusKey: (String) -> Unit,
    onPrimaryAction: () -> Unit,
    onTrackFocused: (Int, String) -> Unit,
    onTrack: (Int) -> Unit,
    onLoadMore: () -> Unit,
    alternateContent: @Composable () -> Unit,
    emptyMessage: String,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(184.dp), verticalAlignment = Alignment.Top) {
            DetailBackButton(
                modifier = Modifier
                    .then(if (focusedKey == "detail-back") Modifier.focusRequester(restoredFocus) else Modifier)
                    .onFocusChanged { if (it.isFocused) onFocusKey("detail-back") },
                onClick = header.onBack,
            )
            Spacer(Modifier.width(18.dp))
            val artworkShape = RoundedCornerShape(FnRadius.sm)
            CollectionArtwork(
                container = container,
                title = title,
                coverId = coverId,
                fallback = header.artworkFallback,
                modifier = Modifier.size(184.dp),
                shape = artworkShape,
            )
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (header.kind.isNotBlank() && header.kind != title) {
                    Text(header.kind, color = FnColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(7.dp))
                }
                Text(
                    title,
                    fontSize = FnType.PageTitle,
                    lineHeight = 35.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val metadata = buildList {
                    if (subtitle.isNotBlank()) add(subtitle)
                    trackCount?.let { add("$it 首") }
                    header.extraMetadata?.takeIf(String::isNotBlank)?.let(::add)
                }.joinToString("  ·  ")
                if (metadata.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        metadata,
                        color = FnColors.Muted,
                        fontSize = FnType.Sub,
                        lineHeight = 17.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    header.tabs.forEach { tab ->
                        DetailTabButton(
                            label = tab.label,
                            selected = tab.selected,
                            modifier = Modifier
                                .then(if (focusedKey == tab.key) Modifier.focusRequester(restoredFocus) else Modifier)
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        onFocusKey(tab.key)
                                        tab.onFocus()
                                    }
                                },
                            onClick = tab.onSelect,
                        )
                    }
                    val primaryShape = RoundedCornerShape(FnRadius.sm)
                    Button(
                        enabled = primaryActionEnabled,
                        onClick = onPrimaryAction,
                        modifier = Modifier
                            .then(if (focusedKey == "primary-action") Modifier.focusRequester(restoredFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) onFocusKey("primary-action") }
                            .height(44.dp),
                        shape = ButtonDefaults.shape(
                            primaryShape,
                            primaryShape,
                            primaryShape,
                            primaryShape,
                            primaryShape,
                        ),
                        scale = ButtonDefaults.scale(focusedScale = 1f),
                        colors = ButtonDefaults.colors(
                            containerColor = FnColors.Surface,
                            contentColor = FnColors.Text,
                            focusedContainerColor = FnColors.FocusFill,
                            focusedContentColor = FnColors.Text,
                            pressedContainerColor = FnColors.FocusFill,
                            pressedContentColor = FnColors.Text,
                        ),
                        border = ButtonDefaults.border(
                            border = Border(BorderStroke(1.dp, FnColors.Coral.copy(alpha = 0.76f)), shape = primaryShape),
                            focusedBorder = Border(BorderStroke(3.dp, FnFocus.ringColor), shape = primaryShape),
                            pressedBorder = Border(BorderStroke(3.dp, FnFocus.ringColor), shape = primaryShape),
                        ),
                        contentPadding = PaddingValues(horizontal = 19.dp, vertical = 0.dp),
                    ) {
                        Text("▶  $primaryActionLabel", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))
        if (showTrackList) {
            DetailTrackColumnHeader()
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.09f)))
            LazyColumn(
                Modifier.weight(1f),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                // 左右不再额外内缩：焦点框左缘落在页面基准线（42dp）上，
                // 与上方标题、返回键同一条竖线；行内再统一内缩 20dp 放文字。
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp),
            ) {
                if (error != null) {
                    item { InlineError(error) }
                }
                if (tracks.isEmpty() && loading) {
                    // 骨架屏：与真实歌曲行同高同结构，加载过程不再只有一行灰字
                    items(8) {
                        Row(
                            Modifier.fillMaxWidth().height(56.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(Modifier.width(TrackRowHorizontalPadding))
                            FnSkeleton(Modifier.size(42.dp), shape = RoundedCornerShape(FnRadius.sm))
                            Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f)) {
                                FnSkeleton(
                                    Modifier.fillMaxWidth(0.52f).height(15.dp),
                                    shape = RoundedCornerShape(FnRadius.sm),
                                )
                                Spacer(Modifier.height(7.dp))
                                FnSkeleton(
                                    Modifier.fillMaxWidth(0.34f).height(11.dp),
                                    shape = RoundedCornerShape(FnRadius.sm),
                                )
                            }
                            Spacer(Modifier.width(13.dp))
                            FnSkeleton(
                                Modifier.size(width = 46.dp, height = 12.dp),
                                shape = RoundedCornerShape(FnRadius.sm),
                            )
                        }
                    }
                }
                if (tracks.isEmpty() && !loading && error == null) {
                    item {
                        Text(
                            emptyMessage,
                            color = FnColors.Muted,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                itemsIndexed(tracks, key = { _, track -> track.guid.value }) { index, track ->
                    DetailTrackRow(
                        index = index,
                        track = track,
                        enabled = isTrackPlayable(track),
                        modifier = Modifier
                            .then(if (focusedKey == track.guid.value) Modifier.focusRequester(restoredFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) onTrackFocused(index, track.guid.value) },
                        onClick = { onTrack(index) },
                    )
                }
                if (hasNext) {
                    item {
                        Button(
                            enabled = !loading,
                            onClick = onLoadMore,
                            modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 5.dp),
                        ) {
                            Text(if (loading) "正在加载" else "加载更多")
                        }
                    }
                }
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) { alternateContent() }
        }
    }
}

@Composable
private fun CollectionArtwork(
    container: AuthenticatedAppDependencies,
    title: String,
    coverId: String?,
    fallback: CollectionArtworkFallback,
    modifier: Modifier,
    shape: Shape,
) {
    val resolvedShape = if (fallback == CollectionArtworkFallback.Artist) CircleShape else shape
    if (coverId != null) {
        RemoteArtwork(
            container = container,
            coverId = coverId,
            variant = CoverVariant.Grid,
            modifier = modifier,
            shape = resolvedShape,
            contentScale = ContentScale.Crop,
            placeholderContent = {
                CollectionArtworkFallbackContent(title, fallback, Modifier.fillMaxSize(), resolvedShape)
            },
        )
    } else {
        CollectionArtworkFallbackContent(title, fallback, modifier, resolvedShape)
    }
}

@Composable
private fun CollectionArtworkFallbackContent(
    title: String,
    fallback: CollectionArtworkFallback,
    modifier: Modifier,
    shape: Shape,
) {
    when (fallback) {
        CollectionArtworkFallback.Artist -> ArtistAvatarPlaceholder(
            title = title,
            modifier = modifier,
            fontSize = 58.sp,
        )
        CollectionArtworkFallback.Favorites -> Box(modifier.clip(shape)) {
            HomeFeatureArtwork(HomeArtworkKind.Favorites, Modifier.fillMaxSize())
        }
        CollectionArtworkFallback.Collection -> Box(modifier.clip(shape)) {
            HomeFeatureArtwork(HomeArtworkKind.Collection, Modifier.fillMaxSize())
        }
        CollectionArtworkFallback.Initial -> InitialArtworkPlaceholder(
            text = title,
            accent = FnColors.Coral,
            modifier = modifier,
            shape = shape,
        )
    }
}

@Composable
private fun DetailBackButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = CircleShape
    Button(
        onClick = onClick,
        modifier = modifier.size(46.dp).semantics { contentDescription = "返回" },
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = FnColors.Surface,
            contentColor = FnColors.Text,
            focusedContainerColor = FnColors.FocusFill,
            focusedContentColor = FnColors.Text,
            pressedContainerColor = FnColors.FocusFill,
            pressedContentColor = FnColors.Text,
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text("‹", fontSize = 36.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DetailTabButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FnRadius.md)
    Button(
        onClick = onClick,
        modifier = modifier.height(42.dp).semantics { this.selected = selected },
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) FnColors.Text else FnColors.Muted,
            focusedContainerColor = FnColors.FocusFill,
            focusedContentColor = FnColors.Text,
            pressedContainerColor = FnColors.FocusFill,
            pressedContentColor = FnColors.Text,
        ),
        contentPadding = PaddingValues(horizontal = 19.dp, vertical = 0.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Box(Modifier.width(5.dp).height(5.dp).background(FnGradients.Primary, CircleShape))
                Spacer(Modifier.width(7.dp))
            }
            Text(label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        }
    }
}

@Composable
private fun DetailTrackColumnHeader() {
    // 与 DetailTrackRow 共用同一套列宽与内边距（56 / 自适应 / 240 / 80，左右 20dp），
    // 否则表头与数据行会各走一套边界，看起来"列是歪的"。
    Row(
        Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#", color = FnColors.Muted, fontSize = FnType.Caption, modifier = Modifier.width(TrackColIndex))
        Text("歌曲", color = FnColors.Muted, fontSize = FnType.Caption, modifier = Modifier.weight(1f))
        Text("歌手", color = FnColors.Muted, fontSize = FnType.Caption, modifier = Modifier.width(TrackColArtist))
        Text(
            "时长",
            color = FnColors.Muted,
            fontSize = FnType.Caption,
            textAlign = TextAlign.End,
            modifier = Modifier.width(TrackColDuration),
        )
    }
}

/** 歌曲列表列宽：表头与数据行共用这三个值，任何改动只需改这里。 */
private val TrackColIndex = 56.dp
private val TrackColArtist = 240.dp
private val TrackColDuration = 80.dp
private val TrackRowHorizontalPadding = 20.dp

/**
 * 歌曲行：**单行 56dp**。
 *
 * 旧实现把「标题 + 专辑」当一个两行的块整体居中，而序号、歌手、时长各自居中，
 * 于是一行里出现两条文字中线，标题看起来比框的中心高约 9dp——就是"字不在框中央"。
 *
 * 现在整行只有一行文字：序号 / 标题 / 歌手 / 时长，全部垂直居中，
 * 结构上就不可能出现"某列比别的列高一点"。专辑名不再单独占一行（歌手列里用 · 接在后面），
 * 行高因此从 66dp 收到 56dp，一屏能多显示一行。
 */
@Composable
private fun DetailTrackRow(
    index: Int,
    track: Track,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(FnRadius.md)
    Button(
        enabled = enabled,
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .semantics {
                contentDescription = "${index + 1}. ${track.title} ${track.artistName.orEmpty()}"
            },
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = FnColors.Text,
            focusedContainerColor = FnColors.FocusFill,
            focusedContentColor = FnColors.Text,
            pressedContainerColor = FnColors.FocusFill,
            pressedContentColor = FnColors.Text,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = FnColors.Muted.copy(alpha = 0.5f),
        ),
        contentPadding = PaddingValues(horizontal = TrackRowHorizontalPadding, vertical = 0.dp),
    ) {
        val contentColor = LocalContentColor.current
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (index + 1).toString().padStart(2, '0'),
                color = contentColor.copy(alpha = 0.66f),
                fontSize = FnType.Label,
                maxLines = 1,
                modifier = Modifier.width(TrackColIndex),
            )
            Text(
                track.title,
                color = contentColor,
                fontSize = FnType.CardTitle,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                track.artistName.orEmpty(),
                color = contentColor.copy(alpha = 0.72f),
                fontSize = FnType.Row,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(TrackColArtist),
            )
            Text(
                if (track.isCue) "需兼容" else formatDuration(track.durationMs ?: 0),
                color = contentColor.copy(alpha = 0.72f),
                fontSize = FnType.Row,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(TrackColDuration),
            )
        }
    }
}

internal data class ExactTrackQueueWindow(
    val items: List<Track>,
    val segments: List<QueuePageSegment>,
)

internal fun exactTrackQueueWindow(
    pages: List<Page<Track>>,
    selectedIndex: Int,
    maxSize: Int = MAX_ACTIVE_QUEUE_ITEMS,
): ExactTrackQueueWindow? {
    val first = pages.firstOrNull() ?: return null
    if (first.pageSize <= 0 || maxSize < first.pageSize) return null
    val rawWindowSize = maxSize / first.pageSize * first.pageSize
    if (pages.withIndex().any { (index, page) ->
            page.page != first.page + index ||
                page.pageSize != first.pageSize ||
                page.total != first.total ||
                page.sort != first.sort ||
                page.items.size > page.pageSize
        }
    ) return null

    val allItems = pages.flatMap(Page<Track>::items)
    val selected = allItems.getOrNull(selectedIndex)?.takeIf(::isTrackPlayable) ?: return null
    val bounded = boundedQueueWindow(
        items = allItems,
        selectedIndex = selectedIndex,
        maxSize = rawWindowSize,
        pageSize = first.pageSize,
    )
    val windowStart = bounded.startIndex
    val windowEnd = windowStart + bounded.items.size
    var loadedOffset = 0
    val retainedPages = buildList {
        pages.forEach { page ->
            val pageStart = loadedOffset
            val pageEnd = pageStart + page.items.size
            loadedOffset = pageEnd
            if (pageEnd <= windowStart || pageStart >= windowEnd) return@forEach
            if (pageStart < windowStart || pageEnd > windowEnd) return null
            add(page)
        }
    }
    if (retainedPages.flatMap(Page<Track>::items) != bounded.items) return null
    val playableIds = bounded.items.filter(::isTrackPlayable).map { it.guid.value }
    if (playableIds.distinct().size != playableIds.size) return null

    val segments = retainedPages.map { page ->
        val sourceStartIndex = (page.page - 1) * page.pageSize
        QueuePageSegment(
            page = page.page,
            rawRowCount = page.items.size,
            playableItems = page.items.mapIndexedNotNull { index, track ->
                track.takeIf(::isTrackPlayable)?.let {
                    QueuePageItem(it.guid.value, sourceStartIndex + index)
                }
            },
            sort = page.sort,
            knownTotal = page.total,
            pageSize = page.pageSize,
            sourceStartIndex = sourceStartIndex,
        )
    }
    if (segments.flatMap(QueuePageSegment::mediaIds) != playableIds) return null
    if (selected.guid.value !in playableIds) return null
    return ExactTrackQueueWindow(items = bounded.items, segments = segments)
}

private fun isTrackPlayable(track: Track): Boolean =
    !track.isCue && (track.accessStatus == null || track.accessStatus == 0)

@Composable
internal fun PlaylistTile(
    title: String,
    subtitle: String,
    coverId: String?,
    accent: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    featureArtwork: HomeArtworkKind? = null,
    onClick: () -> Unit,
) {
    val cardShape = RoundedCornerShape(FnRadius.md)
    Button(
        enabled = enabled,
        onClick = onClick,
        modifier = modifier.size(width = 193.dp, height = 142.dp),
        shape = ButtonDefaults.shape(
            shape = cardShape,
            focusedShape = cardShape,
            pressedShape = cardShape,
            disabledShape = cardShape,
            focusedDisabledShape = cardShape,
        ),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = Color(0xFF1B201F),
            contentColor = FnColors.Text,
            focusedContainerColor = Color(0xFF303634),
            focusedContentColor = FnColors.Text,
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            PlaylistTileArtwork(
                title = title,
                coverId = coverId,
                accent = accent,
                modifier = Modifier.fillMaxWidth().height(108.dp),
                featureArtwork = featureArtwork,
            )
            Row(
                Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 9.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    maxLines = if (subtitle.isBlank()) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (subtitle.isNotBlank()) {
                    Text(subtitle, color = FnColors.Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun PlaylistTileArtwork(
    title: String,
    coverId: String?,
    accent: Color,
    modifier: Modifier = Modifier,
    featureArtwork: HomeArtworkKind? = null,
) {
    val container = LocalAuthenticatedDependencies.current
    val shape = RectangleShape
    if (featureArtwork != null) {
        HomeFeatureArtwork(featureArtwork, modifier)
    } else if (coverId != null) {
        RemoteArtwork(
            container = container,
            coverId = coverId,
            variant = CoverVariant.Grid,
            modifier = modifier,
            shape = shape,
            contentScale = ContentScale.Crop,
            placeholderContent = {
                InitialArtworkPlaceholder(title, accent, Modifier.fillMaxSize(), shape)
            },
        )
    } else {
        InitialArtworkPlaceholder(title, accent, modifier, shape)
    }
}

internal enum class HomeArtworkKind { Roam, Favorites, Collection, PlaylistGrid }

@Composable
private fun HomeFeatureArtwork(kind: HomeArtworkKind, modifier: Modifier) {
    Canvas(modifier) {
        val shortEdge = minOf(size.width, size.height)
        fun point(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(size.width * x, size.height * y)
        fun drawVinyl(center: androidx.compose.ui.geometry.Offset, radius: Float, label: Color) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF38393D),
                        Color(0xFF0B0C0E),
                        Color(0xFF27282C),
                        Color(0xFF08090A),
                    ),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
            listOf(0.58f, 0.72f, 0.86f).forEach { scale ->
                drawCircle(
                    Color.White.copy(alpha = 0.08f),
                    radius * scale,
                    center,
                    style = Stroke(shortEdge * 0.006f),
                )
            }
            drawCircle(label, radius * 0.24f, center)
            drawCircle(Color(0xFFF8F2E7), radius * 0.055f, center)
        }

        when (kind) {
            HomeArtworkKind.Roam -> {
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFFEAE6DF), Color(0xFFC5CDCB)),
                        start = point(0.08f, 0f),
                        end = point(0.94f, 1f),
                    ),
                )
                drawVinyl(point(0.69f, 0.51f), shortEdge * 0.43f, FnColors.Warning)

                val sleeveEdge = size.height * 0.84f
                val sleeveTopLeft = point(0.12f, 0.08f)
                val sleeveSize = androidx.compose.ui.geometry.Size(sleeveEdge, sleeveEdge)
                val sleeveCorner = androidx.compose.ui.geometry.CornerRadius(shortEdge * 0.065f)
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.20f),
                    topLeft = sleeveTopLeft + androidx.compose.ui.geometry.Offset(shortEdge * 0.035f, shortEdge * 0.045f),
                    size = sleeveSize,
                    cornerRadius = sleeveCorner,
                )
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFFF4938),
                            FnColors.Coral,
                            Color(0xFFEC477E),
                            Color(0xFF7569D8),
                        ),
                        start = sleeveTopLeft,
                        end = sleeveTopLeft + androidx.compose.ui.geometry.Offset(sleeveEdge, sleeveEdge),
                    ),
                    topLeft = sleeveTopLeft,
                    size = sleeveSize,
                    cornerRadius = sleeveCorner,
                )
                val orangeWave = Path().apply {
                    moveTo(sleeveTopLeft.x, sleeveTopLeft.y + sleeveEdge * 0.68f)
                    cubicTo(
                        sleeveTopLeft.x + sleeveEdge * 0.18f, sleeveTopLeft.y + sleeveEdge * 0.48f,
                        sleeveTopLeft.x + sleeveEdge * 0.32f, sleeveTopLeft.y + sleeveEdge * 0.56f,
                        sleeveTopLeft.x + sleeveEdge * 0.48f, sleeveTopLeft.y + sleeveEdge * 0.42f,
                    )
                    cubicTo(
                        sleeveTopLeft.x + sleeveEdge * 0.66f, sleeveTopLeft.y + sleeveEdge * 0.24f,
                        sleeveTopLeft.x + sleeveEdge * 0.78f, sleeveTopLeft.y + sleeveEdge * 0.24f,
                        sleeveTopLeft.x + sleeveEdge, sleeveTopLeft.y + sleeveEdge * 0.05f,
                    )
                    lineTo(sleeveTopLeft.x + sleeveEdge, sleeveTopLeft.y + sleeveEdge)
                    lineTo(sleeveTopLeft.x, sleeveTopLeft.y + sleeveEdge)
                    close()
                }
                drawPath(orangeWave, Color(0xFFFFA538).copy(alpha = 0.84f))
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.White.copy(alpha = 0.18f), Color.Transparent),
                        start = sleeveTopLeft,
                        end = sleeveTopLeft + androidx.compose.ui.geometry.Offset(sleeveEdge * 0.72f, sleeveEdge),
                    ),
                    topLeft = sleeveTopLeft,
                    size = sleeveSize,
                    cornerRadius = sleeveCorner,
                )
            }

            HomeArtworkKind.Favorites -> {
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFFF304D),
                            Color(0xFFFF563F),
                            Color(0xFFFF9D37),
                            Color(0xFFFFD45A),
                        ),
                        start = point(0.08f, 0.06f),
                        end = point(0.94f, 0.92f),
                    ),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFD91442).copy(alpha = 0.72f), Color.Transparent),
                        center = point(0.20f, 0.20f),
                        radius = shortEdge * 0.78f,
                    ),
                    radius = shortEdge * 0.78f,
                    center = point(0.20f, 0.20f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFFFDA70).copy(alpha = 0.65f), Color.Transparent),
                        center = point(0.89f, 0.89f),
                        radius = shortEdge * 0.62f,
                    ),
                    radius = shortEdge * 0.62f,
                    center = point(0.89f, 0.89f),
                )
                val heartSize = androidx.compose.ui.geometry.Size(shortEdge * 0.56f, shortEdge * 0.56f)
                val heartOrigin = androidx.compose.ui.geometry.Offset(
                    (size.width - heartSize.width) / 2f,
                    (size.height - heartSize.height) / 2f - shortEdge * 0.015f,
                )
                withTransform({ translate(heartOrigin.x + shortEdge * 0.035f, heartOrigin.y + shortEdge * 0.055f) }) {
                    drawPath(heartPath(heartSize), Color(0xFF9E1731).copy(alpha = 0.18f))
                }
                withTransform({ translate(heartOrigin.x, heartOrigin.y) }) {
                    drawPath(heartPath(heartSize), Color(0xFFFFDDD8).copy(alpha = 0.78f))
                }
            }

            HomeArtworkKind.Collection -> {
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF124A3A), Color(0xFF267A60), FnColors.Teal),
                        start = point(0f, 0.10f),
                        end = point(1f, 0.90f),
                    ),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFB4ECD7).copy(alpha = 0.30f), Color.Transparent),
                        center = point(0.88f, 0.12f),
                        radius = shortEdge * 0.72f,
                    ),
                    radius = shortEdge * 0.72f,
                    center = point(0.88f, 0.12f),
                )
                val discCenter = point(0.70f, 0.50f)
                drawCircle(Color.Black.copy(alpha = 0.18f), shortEdge * 0.36f, discCenter + point(0.015f, 0.035f))
                drawVinyl(discCenter, shortEdge * 0.35f, FnColors.Coral)

                val coverEdge = size.height * 0.70f
                val corner = androidx.compose.ui.geometry.CornerRadius(shortEdge * 0.045f)
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.18f),
                    topLeft = point(0.16f, 0.20f),
                    size = androidx.compose.ui.geometry.Size(coverEdge, coverEdge),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = Color(0xFFE7C95D),
                    topLeft = point(0.12f, 0.13f),
                    size = androidx.compose.ui.geometry.Size(coverEdge, coverEdge),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFFF4EFE5), Color(0xFFD5E0DB)),
                        start = point(0.19f, 0.17f),
                        end = point(0.53f, 0.84f),
                    ),
                    topLeft = point(0.19f, 0.17f),
                    size = androidx.compose.ui.geometry.Size(coverEdge, coverEdge),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = FnColors.Coral,
                    topLeft = point(0.24f, 0.24f),
                    size = androidx.compose.ui.geometry.Size(coverEdge * 0.23f, coverEdge * 0.62f),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = Color(0xFF202528),
                    topLeft = point(0.35f, 0.24f),
                    size = androidx.compose.ui.geometry.Size(coverEdge * 0.14f, coverEdge * 0.62f),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = FnColors.Teal,
                    topLeft = point(0.43f, 0.24f),
                    size = androidx.compose.ui.geometry.Size(coverEdge * 0.09f, coverEdge * 0.62f),
                    cornerRadius = corner,
                )
            }

            HomeArtworkKind.PlaylistGrid -> {
                drawRect(Color(0xFF171C1F))
                val borderInset = shortEdge * 0.012f
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.13f),
                    topLeft = androidx.compose.ui.geometry.Offset(borderInset, borderInset),
                    size = androidx.compose.ui.geometry.Size(
                        size.width - borderInset * 2f,
                        size.height - borderInset * 2f,
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(shortEdge * 0.07f),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                            floatArrayOf(4.dp.toPx(), 4.dp.toPx()),
                        ),
                    ),
                )
                val cellSize = shortEdge * 0.15f
                val gap = shortEdge * 0.055f
                val gridWidth = cellSize * 2f + gap
                val gridHeight = cellSize * 2f + gap
                val gridOrigin = androidx.compose.ui.geometry.Offset(
                    (size.width - gridWidth) / 2f,
                    (size.height - gridHeight) / 2f,
                )
                repeat(2) { row ->
                    repeat(2) { column ->
                        drawRoundRect(
                            color = Color(0xFF858B8E),
                            topLeft = androidx.compose.ui.geometry.Offset(
                                gridOrigin.x + column * (cellSize + gap),
                                gridOrigin.y + row * (cellSize + gap),
                            ),
                            size = androidx.compose.ui.geometry.Size(cellSize, cellSize),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(shortEdge * 0.035f),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistLockup(
    title: String,
    subtitle: String,
    coverId: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    borderless: Boolean = false,
    onClick: () -> Unit,
) {
    val container = LocalAuthenticatedDependencies.current
    var focused by remember { mutableStateOf(false) }
    // 歌手是圆形：头像为圆，焦点白描边也必须画成圆（形状跟随控件本身，绝不给圆头像套一个方框）
    val avatarSize = 124.dp
    Button(
        enabled = enabled,
        onClick = onClick,
        modifier = modifier
            .size(width = avatarSize + 28.dp, height = avatarSize + 56.dp)
            .onFocusChanged { state ->
                focused = state.isFocused
                if (state.isFocused && coverId != null) {
                    container.artworkBitmapCache.prefetch(coverId, CoverVariant.Grid)
                }
            },
        shape = ButtonDefaults.shape(CircleShape, CircleShape, CircleShape, CircleShape, CircleShape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = lockupButtonColors(),
        // 外层不再画方框：焦点由头像自身的圆形描边表达
        border = ButtonDefaults.border(
            border = Border(BorderStroke(0.dp, Color.Transparent), shape = CircleShape),
            focusedBorder = Border(BorderStroke(0.dp, Color.Transparent), shape = CircleShape),
            pressedBorder = Border(BorderStroke(0.dp, Color.Transparent), shape = CircleShape),
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(avatarSize)
                    .fnFocusRing(focused = focused, shape = CircleShape, cornerRadius = avatarSize / 2)
                    .clip(CircleShape),
            ) {
                if (coverId != null) {
                    RemoteArtwork(
                        container = container,
                        coverId = coverId,
                        variant = CoverVariant.Compact,
                        modifier = Modifier.fillMaxSize(),
                        shape = CircleShape,
                        contentScale = ContentScale.Crop,
                        placeholderContent = { ArtistAvatarPlaceholder(title, Modifier.fillMaxSize()) },
                    )
                } else {
                    ArtistAvatarPlaceholder(title, Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                title,
                fontSize = FnType.Row,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                subtitle,
                color = FnColors.Muted,
                fontSize = FnType.Caption,
                lineHeight = 15.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ArtistAvatarPlaceholder(
    title: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 32.sp,
) {
    Box(
        modifier.background(Color(0xFF2D4A46), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title.trim().take(1).ifBlank { "音" }.uppercase(),
            color = FnColors.Teal,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 海报卡封面与文字之间的上间距。 */
private val AlbumPosterTextTop = 8.dp

/** 海报卡文字块下间距。 */
private val AlbumPosterTextBottom = 4.dp

/**
 * 专辑名字号与行高。
 *
 * 21sp / 16sp = 1.31em —— 和歌单卡（19sp/24sp = 1.26em）同一套比例。
 * 原来是 20sp/16sp = 1.25em，且文字块写死 40dp，**正好等于 2×20sp**，一点余量都没有：
 * 系统字体只要放大一点点（不少电视默认 1.15~1.3×），第二行就会被裁掉。
 */
private val AlbumPosterTitleSize = 16.sp
private val AlbumPosterTitleLineHeight = 21.sp
private const val AlbumPosterTitleLines = 2

/** 艺人名字号与行高。19sp / 13sp = 1.46em，与歌单卡副标题（12sp/18sp = 1.5em）同量级。 */
private val AlbumPosterArtistSize = 13.sp
private val AlbumPosterArtistLineHeight = 19.sp

/**
 * 海报卡的文字块尺寸。
 *
 * 与歌单卡（[PlaylistMetrics]）同一套规则：**文字块高度由字号换算（sp → dp），不写死 dp**。
 * sp 转 dp 时会带上系统字体缩放，所以电视把字体放大到 1.17 倍时文字块自动变高，
 * 第二行专辑名和艺人名都不会被固定高度裁掉；字体 1.0 倍的设备上算出来仍是原来的 40 / 17dp。
 *
 * 卡片高度 = 列宽（封面 1:1） + [textBlock]，网格「整行吸附」按它算行高。
 */
private data class AlbumPosterMetrics(val titleBlock: Dp, val artistBlock: Dp, val textBlock: Dp)

@Composable
private fun rememberAlbumPosterMetrics(): AlbumPosterMetrics {
    val density = LocalDensity.current
    return remember(density) {
        with(density) {
            val titleBlock = (AlbumPosterTitleLineHeight.value * AlbumPosterTitleLines).sp.toDp()
            val artistBlock = AlbumPosterArtistLineHeight.toDp()
            AlbumPosterMetrics(
                titleBlock = titleBlock,
                artistBlock = artistBlock,
                // 上下间距是固定 dp，只有两段文字随字号变化
                textBlock = AlbumPosterTextTop + titleBlock + artistBlock + AlbumPosterTextBottom,
            )
        }
    }
}

/**
 * 网格专辑卡（海报式）：封面在上、专辑名与艺人在下。
 *
 * 旧实现在网格里复用横向的 [AlbumLockup]，而它把尺寸写死成 `232×132`。
 * 「全部专辑」5 列时每格只有 160dp，Compose 按父约束压缩后，
 * 扣掉内边距 20 + 封面 100 + 间距 14，留给专辑名的只有 **26dp —— 一个汉字都放不下**，
 * 所以卡片右侧永远只剩一两个字加省略号。
 *
 * 这里改成宽度由网格决定（`fillMaxWidth`），封面按 1:1 撑满列宽（131dp），
 * 文字移到封面下方、占满整列宽度（同样 131dp），长专辑名能排两行。
 * 卡片不再有固定尺寸，列数由 `FnGrid.columnsFor(..., PosterCardWidth)` 决定，两边同一个来源。
 *
 * 焦点框只包封面：Button 自己的描边（哪怕 borderless）在聚焦时会把整张卡——含文字——
 * 一起框住，视觉上像给「封面 + 两行字」套了个信封。所以这里让 Button 永远画透明描边，
 * 焦点框改由封面这一层自己画，文字因此可以完全贴齐封面左边。
 */
@Composable
private fun AlbumPosterCard(
    title: String,
    subtitle: String,
    coverId: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val container = LocalAuthenticatedDependencies.current
    val metrics = rememberAlbumPosterMetrics()
    val shape = RoundedCornerShape(FnRadius.md)
    val artworkShape = RoundedCornerShape(FnRadius.md)
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                focused = state.isFocused
                if (state.isFocused && coverId != null) {
                    container.artworkBitmapCache.prefetch(coverId, CoverVariant.Grid)
                }
            },
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = lockupButtonColors(),
        // Button 自己一律不描边：静止态干净、聚焦态也不画（焦点框交给封面，见上）
        border = invisibleButtonBorder(shape),
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                if (coverId != null) {
                    RemoteArtwork(
                        container = container,
                        coverId = coverId,
                        variant = CoverVariant.Grid,
                        modifier = Modifier.fillMaxSize(),
                        shape = artworkShape,
                        contentScale = ContentScale.Crop,
                        placeholderContent = {
                            InitialArtworkPlaceholder(title, FnColors.Coral, Modifier.fillMaxSize(), artworkShape)
                        },
                    )
                } else {
                    InitialArtworkPlaceholder(
                        title,
                        FnColors.Coral,
                        Modifier.fillMaxSize(),
                        artworkShape,
                    )
                }
                // 焦点框只包封面：宽度、圆角与封面完全一致，聚焦时正好压在封面边缘上
                if (focused) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .border(FnFocus.ringWidth, FnFocus.ringColor, artworkShape),
                    )
                }
            }
            // 文字区不再需要横向内边距——焦点框已经不覆盖文字，直接与封面左边缘对齐
            Column(Modifier.fillMaxWidth().padding(top = AlbumPosterTextTop, bottom = AlbumPosterTextBottom)) {
                Text(
                    title,
                    fontSize = AlbumPosterTitleSize,
                    lineHeight = AlbumPosterTitleLineHeight,
                    maxLines = AlbumPosterTitleLines,
                    overflow = TextOverflow.Ellipsis,
                    // 固定两行高度：只占一行时同一行的卡片会高矮不一。
                    // 高度按 sp 换算，字体被放大时跟着长，第二行才不会被裁掉。
                    modifier = Modifier.fillMaxWidth().height(metrics.titleBlock),
                )
                Text(
                    subtitle.ifBlank { " " },
                    color = FnColors.Muted,
                    fontSize = AlbumPosterArtistSize,
                    lineHeight = AlbumPosterArtistLineHeight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().height(metrics.artistBlock),
                )
            }
        }
    }
}

@Composable
private fun AlbumLockup(
    title: String,
    subtitle: String,
    coverId: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    borderless: Boolean = false,
    onClick: () -> Unit,
) {
    val container = LocalAuthenticatedDependencies.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(FnRadius.md)
    val artworkShape = RoundedCornerShape(FnRadius.sm)
    Button(
        enabled = enabled,
        onClick = onClick,
        modifier = modifier
            .size(width = 232.dp, height = 132.dp)
            .focusGlow(focused = focused, cornerRadius = 10.dp)
            .onFocusChanged { state ->
                focused = state.isFocused
                if (state.isFocused && coverId != null) {
                    container.artworkBitmapCache.prefetch(coverId, CoverVariant.Grid)
                }
            },
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = lockupButtonColors(),
        border = lockupButtonBorder(shape, borderless),
        contentPadding = PaddingValues(10.dp),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (coverId != null) {
                RemoteArtwork(
                    container = container,
                    coverId = coverId,
                    variant = CoverVariant.Compact,
                    modifier = Modifier.size(100.dp),
                    shape = artworkShape,
                    contentScale = ContentScale.Crop,
                    placeholderContent = {
                        InitialArtworkPlaceholder(title, FnColors.Coral, Modifier.fillMaxSize(), artworkShape)
                    },
                )
            } else {
                InitialArtworkPlaceholder(title, FnColors.Coral, Modifier.size(100.dp), artworkShape)
            }
            Spacer(Modifier.width(14.dp))
            LockupLabels(title, subtitle, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LockupLabels(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(title, fontSize = FnType.Row, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(subtitle, color = FnColors.Muted, fontSize = FnType.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun lockupButtonColors() = ButtonDefaults.colors(
    containerColor = Color.Transparent,
    contentColor = FnColors.Text,
    // 聚焦不再填充任何底色（原来是 #303634 的灰蒙层）
    focusedContainerColor = Color.Transparent,
    focusedContentColor = FnColors.Text,
    pressedContainerColor = Color.Transparent,
    pressedContentColor = FnColors.Text,
    disabledContainerColor = Color.Transparent,
)

@Composable
private fun lockupButtonBorder(shape: Shape, borderless: Boolean = false) = ButtonDefaults.border(
    // borderless 只影响「静止态」——静止时无边框更干净；
    // 但聚焦/按下必须始终给出白色描边，否则这些卡片（我的页的专辑/歌单/音乐库等）聚焦时完全没有反馈。
    border = if (borderless) Border(BorderStroke(0.dp, Color.Transparent), shape = shape)
             else Border(BorderStroke(FnFocus.hairlineWidth, FnFocus.hairlineColor), shape = shape),
    focusedBorder = Border(BorderStroke(FnFocus.ringWidth, FnFocus.ringColor), shape = shape),
    pressedBorder = Border(BorderStroke(FnFocus.ringWidth, FnFocus.ringColor), shape = shape),
)

/**
 * 三态全透明的描边：Button 完全不参与焦点表达。
 *
 * 给 [AlbumPosterCard] 用——它的焦点框只包封面（文字不参与），
 * 而 Button 的描边永远画在控件外框上，所以只能让 Button 全透明、由封面自己画框。
 */
@Composable
private fun invisibleButtonBorder(shape: Shape) = ButtonDefaults.border(
    border = Border(BorderStroke(0.dp, Color.Transparent), shape = shape),
    focusedBorder = Border(BorderStroke(0.dp, Color.Transparent), shape = shape),
    pressedBorder = Border(BorderStroke(0.dp, Color.Transparent), shape = shape),
)

@Composable
private fun CatalogRetryButton(
    loading: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(FnRadius.md)
    Button(
        enabled = !loading,
        onClick = onClick,
        modifier = modifier.size(width = 232.dp, height = 132.dp),
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = lockupButtonColors(),
        border = lockupButtonBorder(shape),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Text(if (loading) "正在加载" else "重试", fontSize = 12.sp)
            Text("重新加载列表", color = FnColors.Muted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun RemoteArtwork(
    container: AuthenticatedAppDependencies,
    coverId: String,
    variant: CoverVariant,
    modifier: Modifier = Modifier,
    fallbackVariant: CoverVariant? = null,
    shape: Shape = RoundedCornerShape(FnRadius.md),
    contentScale: ContentScale = ContentScale.Fit,
    placeholderContent: (@Composable () -> Unit)? = null,
) {
    val bitmap = rememberRemoteArtworkBitmap(container, coverId, variant, fallbackVariant)
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), null, modifier.clip(shape), contentScale = contentScale)
    } else {
        Box(modifier.background(FnColors.Surface, shape), contentAlignment = Alignment.Center) {
            if (placeholderContent != null) {
                placeholderContent()
            } else {
                InitialArtworkPlaceholder(
                    text = "",
                    accent = FnColors.Teal,
                    modifier = Modifier.fillMaxSize(),
                    shape = shape,
                )
            }
        }
    }
}

@Composable
private fun rememberRemoteArtworkBitmap(
    container: AuthenticatedAppDependencies,
    coverId: String?,
    variant: CoverVariant,
    fallbackVariant: CoverVariant? = null,
): Bitmap? {
    val initialBitmap = remember(container, coverId, variant, fallbackVariant) {
        coverId?.let { id ->
            container.artworkBitmapCache.peek(id, variant)
                ?: fallbackVariant?.let { container.artworkBitmapCache.peek(id, it) }
        }
    }
    val bitmap by produceState(initialBitmap, container, coverId, variant, fallbackVariant) {
        value = coverId?.let { id ->
            container.artworkBitmapCache.getProgressively(
                coverId = id,
                variant = variant,
                fallbackVariant = fallbackVariant,
                onIntermediate = { value = it },
            )
        }
    }
    return if (coverId == null) null else bitmap
}

@Composable
private fun InlineError(error: AppError) {
    Text(appErrorMessage(error), color = FnColors.Coral, fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp))
}

internal fun appErrorMessage(error: AppError): String = when (error) {
    AppError.NetworkUnavailable -> "NAS 暂时不可用"
    AppError.Empty -> "暂无内容"
    AppError.UnavailableTrack -> "歌曲不可访问"
    AppError.TranscodeUnavailable -> "兼容播放参数尚未确认"
    AppError.CollectionChanged -> "列表已更新，请返回后重新载入"
    else -> "加载失败，请重试"
}

internal fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1_000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** 单次搜索的极端兜底超时：正常搜索（索引已就绪）毫秒级，首搜等待完整索引构建也远快于此值；
 *  这里只用于防止网络异常导致「永远停在正在搜索」的极端情况，放得足够大（120s）以免截断正常的大曲库构建。 */
private const val SEARCH_TIMEOUT_MS = 120_000L

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchScreen(
    container: AuthenticatedAppDependencies,
    onPlayTrack: (Track) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onOpenArtist: (Artist) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<AppError?>(null) }
    var searchDiag by remember { mutableStateOf<String?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    // 请求代号：每次发起搜索 +1。只有"最后一次"请求才允许回写界面状态，
    // 否则被取消的旧请求回来时可能把 searching 重置成 false/true，造成状态错乱。
    var searchToken by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val inputFocus = remember { FocusRequester() }

    fun runSearch(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        searchJob?.cancel()
        val token = ++searchToken
        searching = true
        searchError = null
        searchJob = scope.launch {
            try {
                val r = withTimeout(SEARCH_TIMEOUT_MS) {
                    container.musicRepository.search(trimmed)
                }
                // 已有更新的请求接管了界面：直接丢弃本次结果，不要碰任何状态
                if (token != searchToken) return@launch
                results = r
                if (r.isEmpty) {
                    val diag = container.musicRepository.lastSearchDiagnostics().orEmpty()
                    searchDiag = diag.takeIf { it.isNotBlank() }
                    CrashLogStore.appendEntry(context, diag)
                } else {
                    searchDiag = null
                }
            } catch (e: TimeoutCancellationException) {
                if (token != searchToken) return@launch
                searchError = AppError.Unknown("搜索超时，请稍后重试")
                // 失败时也带上诊断：之前只有「无结果」才显示索引规模，
                // 超时/异常这条路什么都看不到，用户无从判断是索引没建起来还是曲库里没有。
                val diag = container.musicRepository.lastSearchDiagnostics().orEmpty()
                searchDiag = diag.takeIf { it.isNotBlank() }
                if (diag.isNotBlank()) CrashLogStore.appendEntry(context, diag)
            } catch (e: CancellationException) {
                // 被更新的请求 cancel 掉：正常流程，直接抛弃，不碰任何状态
                return@launch
            } catch (e: Throwable) {
                if (token != searchToken) return@launch
                searchError = (e as? AppException)?.error
                    ?: AppError.Unknown(e.message ?: "搜索失败，请重试")
                val diag = container.musicRepository.lastSearchDiagnostics().orEmpty()
                searchDiag = diag.takeIf { it.isNotBlank() }
                if (diag.isNotBlank()) CrashLogStore.appendEntry(context, diag)
            } finally {
                // 无论成功/失败/超时，只要是“当前这次”请求，都必须复位 searching，
                // 否则慢服务器下界面会永远停在“正在搜索…”。
                if (token == searchToken) searching = false
            }
        }
    }

    BackHandler { onBack() }

    // 进搜索页先确保索引就绪：实时搜索下第一次按键就会触发搜索，
    // 若此时才开始全量拉取目录，会让首字符搜索明显卡顿。已构建过时几乎是空操作。
    LaunchedEffect(Unit) {
        runCatching { container.musicRepository.warmSearchIndex() }
    }

    // 输入即搜：停止输入约 180ms 后自动发起搜索，无需再按确定键。
    // 关键收益：电视输入法在组词过程中，输入框里始终是用户刚打出的原始字母，
    // 实时搜索让拼音首字母索引在“组词完成前”就能命中，全程不用切换中/英文模式。
    // query 变化会取消上一次未执行的 delay，天然形成防抖；runSearch 内部再取消旧请求。
    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            // 清空输入：立即作废在途请求并回到初始提示态。
            // 必须同时推进 token，否则刚被 cancel 的请求回来时还会把 searching 写成 true。
            searchToken++
            searchJob?.cancel()
            results = null
            searching = false
            searchError = null
            return@LaunchedEffect
        }
        delay(180L)
        runSearch(trimmed)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("搜索", fontSize = FnType.PageTitle, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
            // 搜索中状态挂在标题行右侧，不占用内容区高度，避免结果列表反复跳动
            if (searching) {
                Spacer(Modifier.width(14.dp))
                Text(
                    // 首搜/大曲库要等索引：明确告诉用户在等什么，避免看起来像卡死
                    if (results == null) "正在搜索…（首次需建立曲库索引）" else "正在搜索…",
                    color = FnColors.Muted,
                    fontSize = FnType.Label,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        SearchField(
            value = query,
            onValueChange = { query = it },
            onSubmit = { runSearch(query) },
            focusRequester = inputFocus,
            onFocusStateChange = {},
        )
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                // 搜索中不抢占内容区：保留上一次结果，避免实时搜索时结果反复闪断
                searchError != null -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    InlineError(searchError!!)
                    // 失败原因/索引状态：超时与异常也会带上，便于判断「索引没建起来」还是「曲库没有」
                    searchDiag?.let { diag ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            diag.take(180),
                            color = FnColors.Muted.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            maxLines = 3,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    TvMaterialButton(onClick = { runSearch(query) }) {
                        Text("重试", fontSize = FnType.Label)
                    }
                }
                results != null && results!!.isEmpty -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "没有找到与“${query.trim()}”相关的内容",
                        color = FnColors.Text,
                        fontSize = FnType.Section,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("试试拼音首字母，例如 zjl、z j l", color = FnColors.Muted, fontSize = FnType.Body)
                    searchDiag?.let { diag ->
                        Spacer(Modifier.height(12.dp))
                        // 诊断信息：索引规模/失败原因。平时看不到，只在搜不到时出现
                        Text(diag.take(180), color = FnColors.Muted.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 3)
                    }
                }
                results != null -> SearchResultsContent(
                    results = results!!,
                    onPlayTrack = onPlayTrack,
                    onOpenPlaylist = onOpenPlaylist,
                    onOpenArtist = onOpenArtist,
                    onOpenAlbum = onOpenAlbum,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "搜索歌曲、歌手、歌单、专辑",
                        color = FnColors.Text,
                        fontSize = FnType.Section,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("支持中文、拼音全拼与拼音首字母", color = FnColors.Muted, fontSize = FnType.Body)
                    Spacer(Modifier.height(4.dp))
                    Text("例如：周杰伦 / zhoujielun / z j l", color = FnColors.Muted, fontSize = FnType.Sub)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { inputFocus.requestFocus() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester,
    onFocusStateChange: (Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val shape = RoundedCornerShape(FnRadius.lg)

    // 进入搜索页即弹出键盘，焦点由 SearchScreen 的 LaunchedEffect 请求到输入框上，
    // 用户无需再按一次确定键即可直接输入。
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { keyboard?.show() }
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                onFocusStateChange(it.isFocused)
            }
            .onPreviewKeyEvent { event ->
                val key = event.key
                if (key == Key.Enter || key == Key.DirectionCenter) {
                    if (event.type == KeyEventType.KeyUp) {
                        if (imeVisible) {
                            // 键盘可见：按确定/回车 = 提交搜索并收起键盘
                            keyboard?.hide()
                            onSubmit()
                        } else {
                            // 键盘已收起但输入框仍持有焦点：按确定 = 重新弹出键盘继续编辑
                            runCatching { keyboard?.show() }
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                false
            }
            .background(if (focused) FnColors.FocusFill else FnColors.SurfaceAlt, shape)
            .border(
                if (focused) FnFocus.ringWidth else FnFocus.hairlineWidth,
                if (focused) FnColors.Coral else FnFocus.hairlineColor,
                shape,
            )
            .padding(horizontal = 18.dp, vertical = 12.dp),
        textStyle = TextStyle(fontSize = 19.sp, color = FnColors.Text),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            keyboard?.hide()
            onSubmit()
        }),
        cursorBrush = SolidColor(FnColors.Coral),
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text("输入关键词或拼音首字母，如 zjl，松开遥控器即自动搜索", color = FnColors.Muted, fontSize = FnType.Label)
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun SearchResultsContent(
    results: SearchResults,
    onPlayTrack: (Track) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onOpenArtist: (Artist) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = rememberLazyListState(),
        contentPadding = PaddingValues(bottom = 32.dp),
        modifier = modifier,
    ) {
        if (results.tracks.isNotEmpty()) {
            item { SearchSectionTitle("歌曲") }
            itemsIndexed(results.tracks.take(30), key = { _, track -> track.guid.value }) { index, track ->
                DetailTrackRow(
                    index = index,
                    track = track,
                    enabled = isTrackPlayable(track),
                    modifier = Modifier.padding(vertical = 2.dp),
                    onClick = { onPlayTrack(track) },
                )
            }
        }
        if (results.artists.isNotEmpty()) {
            item {
                SearchSectionTitle("歌手")
                Spacer(Modifier.height(6.dp))
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(2.dp)) {
                    items(results.artists.take(20), key = { it.guid.value }) { artist ->
                        ArtistLockup(artist.name, "${artist.trackCount ?: 0} 首", artist.coverId) {
                            onOpenArtist(artist)
                        }
                    }
                }
            }
        }
        if (results.playlists.isNotEmpty()) {
            item {
                SearchSectionTitle("歌单")
                Spacer(Modifier.height(6.dp))
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(2.dp)) {
                    items(results.playlists.take(20), key = { it.guid.value }) { playlist ->
                        PlaylistBandLockup(playlist.name, "${playlist.trackCount ?: 0} 首", playlist.coverId) {
                            onOpenPlaylist(playlist)
                        }
                    }
                }
            }
        }
        if (results.albums.isNotEmpty()) {
            item {
                SearchSectionTitle("专辑")
                Spacer(Modifier.height(6.dp))
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(2.dp)) {
                    items(results.albums.take(20), key = { it.guid.value }) { album ->
                        AlbumLockup(album.name, album.artistName.orEmpty(), album.coverId) {
                            onOpenAlbum(album)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSectionTitle(title: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(5.dp).height(5.dp).background(FnGradients.Primary, CircleShape))
        Spacer(Modifier.width(9.dp))
        Text(title, fontSize = FnType.Section, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.height(1.dp).width(32.dp).background(FnColors.Muted.copy(alpha = 0.4f), CircleShape))
    }
}
