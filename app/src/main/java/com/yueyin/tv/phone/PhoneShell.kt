package com.yueyin.tv.phone





import android.os.SystemClock


import android.widget.Toast


import androidx.activity.compose.BackHandler


import androidx.compose.foundation.background


import androidx.compose.foundation.border


import androidx.compose.foundation.clickable


import androidx.compose.foundation.interaction.MutableInteractionSource


import androidx.compose.foundation.interaction.collectIsPressedAsState


import androidx.compose.foundation.layout.Arrangement


import androidx.compose.foundation.layout.Box


import androidx.compose.foundation.layout.Column


import androidx.compose.foundation.layout.Row


import androidx.compose.foundation.layout.Spacer


import androidx.compose.foundation.layout.WindowInsets


import androidx.compose.foundation.layout.WindowInsetsSides


import androidx.compose.foundation.layout.fillMaxHeight


import androidx.compose.foundation.layout.fillMaxSize


import androidx.compose.foundation.layout.fillMaxWidth


import androidx.compose.foundation.layout.height


import androidx.compose.foundation.layout.navigationBarsPadding


import androidx.compose.foundation.layout.only


import androidx.compose.foundation.layout.padding


import androidx.compose.foundation.layout.safeDrawing


import androidx.compose.foundation.layout.size


import androidx.compose.foundation.layout.width


import androidx.compose.foundation.layout.widthIn


import androidx.compose.foundation.layout.windowInsetsPadding


import androidx.compose.foundation.rememberScrollState


import androidx.compose.foundation.shape.CircleShape


import androidx.compose.foundation.shape.RoundedCornerShape


import androidx.compose.foundation.verticalScroll


import androidx.compose.runtime.Composable


import androidx.compose.runtime.CompositionLocalProvider


import androidx.compose.runtime.LaunchedEffect


import androidx.compose.runtime.getValue


import androidx.compose.runtime.key


import androidx.compose.runtime.mutableStateOf


import androidx.compose.runtime.remember


import androidx.compose.runtime.rememberCoroutineScope


import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue


import androidx.compose.ui.Alignment


import androidx.compose.ui.Modifier


import androidx.compose.ui.graphics.Color


import androidx.compose.ui.platform.LocalContext


import androidx.compose.ui.semantics.contentDescription


import androidx.compose.ui.semantics.semantics


import androidx.compose.ui.text.font.FontWeight


import androidx.compose.ui.text.style.TextOverflow


import androidx.compose.ui.unit.dp


import androidx.lifecycle.compose.collectAsStateWithLifecycle


import androidx.tv.material3.Text


import com.yueyin.tv.AppUiDependencies


import com.yueyin.tv.core.data.repository.SessionState


import com.yueyin.tv.core.model.Track


import com.yueyin.tv.core.model.TrackGuid


import com.yueyin.tv.core.playback.PlaybackUiState


import com.yueyin.tv.ui.FnColors


import com.yueyin.tv.ui.LibraryRetainedStateStore


import com.yueyin.tv.ui.LibraryRoute


import com.yueyin.tv.ui.connect.ConnectRuntime


import com.yueyin.tv.ui.isHomeBackConfirmed
import com.yueyin.tv.ui.storageKey


import kotlinx.coroutines.launch





/**


 * 手机版应用壳。


 *


 * 与电视壳（`AuthenticatedApp`）的关系：**并列，不继承**。


 * 两边共用同一套 `LibraryRoute` 路由语义与同一批 core 数据层，界面表现完全独立。


 * 电视端那 5900 行布局一行都不用动，手机适配出任何问题都不会传染到电视。


 *


 * 职责只有三件：**路由栈、底部导航、迷你播放条**。


 * 页面内容在 [PhoneHomePage] / [PhoneLibraryPage] / [PhoneDetailPage] / [PhonePlayerPage] 里，


 * 壳不关心它们各自怎么取数。


 */


@Composable


internal fun PhoneApp(


    container: AppUiDependencies,


    session: SessionState.SignedIn,


    playback: PlaybackUiState,


    onExitApplication: () -> Unit,


) {


    val context = LocalContext.current


    val scope = rememberCoroutineScope()


    // 路由栈语义与电视壳保持一致：root 换栈底（切 Tab / 切分段），open 压栈（进二级页）。


    var stack by remember(session.user.guid) {


        mutableStateOf(listOf<LibraryRoute>(LibraryRoute.Home))


    }


    var lastHomeBackAt by remember(session.user.guid) { mutableStateOf(0L) }


    // 首次启动引导：装好第一次打开时盖一层三页说明（音乐从哪来 / 播放页怎么点）。


    // 跳过或看完都写本机标记，之后不再打扰。与账号无关，所以不按 user.guid 重建。


    var guideVisible by remember { mutableStateOf(!phoneGuideSeen(context)) }


    val route = stack.last()





    // 手机独立一份保留状态：换账号即重建，避免看到上个账号的曲库。


    val store = remember(session.user.guid) { LibraryRetainedStateStore(scope) }


    // 搜索会话（关键词 / 结果 / 滚动位置）跨路由保留：点搜索结果的专辑、歌手、歌单会压一层新路由，

    // 搜索页会整个离开组合；挂在壳上，返回时才能接回原来那一次搜索，而不是变成一个空搜索页。

    val searchRetention = remember(session.user.guid) { PhoneSearchRetention() }


    // 搜索会话什么时候作废：只看这次离开搜索页是「往下钻」还是「往后退」。
    //   往下钻（点专辑 / 歌手 / 歌单 / 播放页）→ 栈变长，返回时要接回原来那次搜索 → 保留；
    //   往后退（按返回退出搜索页）→ 栈变短或换了栈底，下次进来必须是全新的搜索 → 清空。
    // 判定方向用「旧栈是不是新栈的前缀」：压栈时旧栈一定是新栈的前缀，弹栈时相反。
    var previousStack by remember(session.user.guid) { mutableStateOf(stack) }
    LaunchedEffect(stack) {
        val previous = previousStack
        previousStack = stack
        if (previous.lastOrNull() != LibraryRoute.Search) return@LaunchedEffect
        val pushed = stack.size > previous.size && stack.subList(0, previous.size) == previous
        if (!pushed) searchRetention.reset()
    }





    // 设置页的入口面板。「我的」页的五个入口要直达对应面板，而 Settings 是 data object


    // 带不了参数，只好把「进哪一面板」放在壳里传下去；从底部导航进设置时复位到根页。


    var settingsEntry by remember(session.user.guid) { mutableStateOf(PhoneSettingsPane.Player) }





    // 切平台必须停掉当前播放并清空各页保留快照：否则上一平台的歌还在播、列表还是旧平台的，


    // 用户会以为「切了没反应」。与电视壳同一套处理，只是这里用 boundPlatformId 记住初值——


    // 不带初值的话，冷启动第一帧就会把「上次退出时正在播的歌」直接停掉。


    val connectState by ConnectRuntime.store.state.collectAsStateWithLifecycle()


    // 页面级状态保存槽：手机端只渲染栈顶路由，页面一离开组合，`rememberSaveable`
    // 里的东西（列表滚动位置、搜索词、设置页选中项）就会被丢弃，返回时从零开始——
    // 表现为「从曲库点进歌单，返回后列表永远回到第一行」。
    // 电视端 AuthenticatedApp 早就在用同一个做法（stateHolder + route.storageKey()），
    // 手机端当时只给搜索做了外置（PhoneSearchRetention），曲库列表漏了。
    val pageStateHolder = rememberSaveableStateHolder()

    var boundPlatformId by remember(session.user.guid) { mutableStateOf(connectState.activeId) }


    LaunchedEffect(connectState.activeId) {


        if (boundPlatformId != connectState.activeId) {


            boundPlatformId = connectState.activeId


            runCatching { container.playbackController.stopAndClearQueue() }


            store.clearAll()


            // 搜索结果是上一个数据源搜出来的，切完平台还留着就成了「点了播不了」的陷阱。


            searchRetention.reset()


        }


    }





    val root: (LibraryRoute) -> Unit = { stack = listOf(it) }


    val open: (LibraryRoute) -> Unit = { stack = stack + it }


    val openPlayer: () -> Unit = { open(LibraryRoute.Player(null)) }


    // 设置不再是底部 tab，而是「我的」页下的二级页：压栈进入，返回键自然回到「我的」。


    val openSettings: (PhoneSettingsPane) -> Unit = { pane ->


        settingsEntry = pane


        open(LibraryRoute.Settings)


    }





    // 起播：直接调数据层，不经过页面。成功与否都保证回到当前页面（不自动跳播放页，


    // 手机端「点了继续浏览」比「强制全屏播放」更符合使用习惯，迷你条已经足够反馈）。


    // 随机播放：起点随机 + 起播成功后把播放器切成随机模式。
    // 必须走 PhonePlayback.start 这一层：起播是异步的（要 prepareQueue、取直链），
    // 切模式只能在队列真的提交给播放器之后做，否则会被内核判过期回滚。
    // 与电视端「随机播放」语义完全一致（电视端在 TrackCollection.play(shuffleMode = true)）。
    val shuffleTracks: (List<Track>) -> Unit = { list ->


        scope.launch {


            val ok = PhonePlayback.start(container, list, 0, store.scope, shuffle = true)


            if (!ok) Toast.makeText(context, "这些歌暂时都播不了", Toast.LENGTH_SHORT).show()


        }


    }


    val playTracks: (List<Track>, Int) -> Unit = { tracks, index ->


        scope.launch {


            val ok = PhonePlayback.start(container, tracks, index, store.scope)


            if (!ok) Toast.makeText(context, "这首暂时播不了", Toast.LENGTH_SHORT).show()


        }


    }





    // 最近播放：切歌时把当前这首记下来。用 mediaId 变化判定，不依赖起播入口，


    // 随心听、点歌单、切歌都走同一条路径，不会漏记。


    LaunchedEffect(playback.mediaId) {


        if (playback.mediaId.isBlank()) return@LaunchedEffect


        val snapshot = Track(


            guid = TrackGuid(playback.mediaId),


            title = playback.title,


            artistName = playback.artist.takeIf { it.isNotBlank() },


            albumName = null,


            coverId = playback.coverId,


            durationMs = playback.durationMs.takeIf { it > 0L },


            isCue = false,


            isFavorite = false,


        )


        PhoneRecentlyPlayed.record(context, snapshot, phonePlatformKey(session))


    }





    // 返回键：二级页回退（保留所在 Tab）→ 任意 Tab 根页「再按一次退出」。


    // 关键修正：以前是「非首页 Tab 按返回直接跳回首頁」，于是曲库/我的按返回会被弹回首頁，


    // 用户觉得导航错乱。现在任一 Tab 根页都走和首页一致的「再按一次退出」，返回键不再偷偷换页，


    // 想回哪个 Tab 用底部/侧边导航自己点。子页（歌单/详情/设置等）仍是正常回退到上一级。


    // 与电视端保持同一套「再按一次退出」文案，换设备不用重新学。


    BackHandler {


        if (stack.size > 1) {


            stack = stack.dropLast(1)


        } else {


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





    ProvidePhoneWindow { window ->


        CompositionLocalProvider(


            LocalPhoneDeps provides container,


            LocalPhoneStore provides store,


        ) {


            // 必须放在 LocalPhoneDeps 的 provider 内部：
            // 放到外面会在启动瞬间读未提供的 CompositionLocal 而抛异常（闪退）。
            PhoneCastFollow(playback)

            // 导航形态二选一：竖屏用底部导航，宽屏（≥600dp）换左侧图标栏。


            // 两者互斥而不是叠加——同一组入口在一屏里出现两次，用户会不知道该点哪个。


            // 强任务态二级页（搜索/表单/配对/播放）两种形态都不显示，理由见 hidesBottomBar。


            val withRail = window.usesNavRail && !route.hidesBottomBar()


            val selectTab: (LibraryRoute) -> Unit = { target -> root(target) }


            // 同一个渲染入口服务三种场景：单栏、双栏左栏（列表）、双栏右栏（详情）。


            // 写成局部 lambda 是为了不让 PhoneRouteHost 那十个参数在分支里抄两遍。


            val renderRoute: @Composable (LibraryRoute, Boolean) -> Unit = { target, embedded ->


                // 每个路由一个保存槽（playlists / albums / artists / playlist:<guid> …）：
                // 页面被换出时 Compose 把它的 rememberSaveable 存进槽里，返回时原样取回，
                // 曲库列表的滚动位置就是这么保住的。
                //
                // 键里带平台维度：换平台后列表内容完全不同，沿用上一个平台的位置会让用户
                // 落到一个不该在的地方；加前缀后新平台天然从顶部开始，旧槽不再被引用。
                pageStateHolder.SaveableStateProvider(
                    "${connectState.activeId ?: "fn"}|${target.storageKey()}",
                ) {

                    PhoneReadingFrame(enabled = target.needsReadingWidth()) {


                    PhoneRouteHost(


                        route = target,


                        session = session,


                        playback = playback,


                        searchRetention = searchRetention,


                        onRoot = root,


                        onOpen = open,


                        onOpenPlayer = openPlayer,


                        onPlayTracks = playTracks,


                        onShuffleTracks = shuffleTracks,


                        onOpenSettings = openSettings,


                        settingsEntry = settingsEntry,


                        onBack = { stack = stack.dropLast(1) },


                        embeddedDetail = embedded,


                    )


                }

                }


            }


            Box(Modifier.fillMaxSize()) {


                // 切平台时把整棵内容树按平台 id 重建一次。


                // 只清保留状态（store.clearAll）是不够的：各页的 `LaunchedEffect(stateKey)` 键没变，


                // 不会重新取数，用户会停在「已切平台但列表还是空的/还是旧的」上。


                // 用 key() 换掉整棵子树，所有 remember 与 effect 一起重来，语义最干净。


                key(connectState.activeId ?: "fn") {


                Row(Modifier.fillMaxSize().background(FnColors.Background)) {


                    if (withRail) {


                        PhoneNavRail(


                            route = route,


                            onSelect = selectTab,


                            // 图标栏自己避开安全区：它是一条通高的竖条，


                            // 若跟着内容区一起躲状态栏，顶部会空出一条与内容不对齐的缝。


                            modifier = Modifier


                                .fillMaxHeight()


                                .then(

                                    if (!window.isLandscape) {

                                        Modifier.windowInsetsPadding(

                                            WindowInsets.safeDrawing.only(

                                                WindowInsetsSides.Top + WindowInsetsSides.Start,

                                            ),

                                        )

                                    } else {

                                        Modifier

                                    },

                                )


                                .navigationBarsPadding(),


                        )


                    }


                    Column(Modifier.weight(1f).fillMaxHeight()) {


                    Box(


                        Modifier


                            .fillMaxWidth()


                            .weight(1f)


                            // 边到边绘制：除播放页外，状态栏区域用背景色铺满、内容从状态栏下方开始。


                            //


                            // 用 safeDrawing 的「上 + 左右」而不是纯 statusBars：横屏时状态栏被隐藏


                            // （见 MainActivity.applySystemBars），statusBars 内边距会归零，此时真正


                            // 需要避开的是挖孔/刘海——它只在 safeDrawing 里。只取上、左右两侧，


                            // 不取底部，键盘弹起时不会把内容顶上去。


                            .then(


                                if (route !is LibraryRoute.Player && !window.isLandscape) {


                                    Modifier.windowInsetsPadding(


                                        WindowInsets.safeDrawing.only(


                                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,


                                        ),


                                    )


                                } else {


                                    Modifier


                                },


                            ),


                    ) {


                            // 平板双栏：左侧列表与右侧详情同时在场，点左栏任一项只换右栏内容，


                            // 左栏不消失——这是平板相对于手机唯一的实质增益，不然只是「放大的手机」。


                            //


                            // 只有详情类路由（专辑 / 歌单 / 歌手）铺双栏。播放页、设置、搜索、


                            // 平台管理都占满整宽：它们没有「左边还该看什么」的对应关系，


                            // 硬拆两栏只会两边都变窄，反而更难看。


                            val splitLeft = when {


                                window.usesSplitPane && stack.size >= 2 && route.isDetailRoute() ->


                                    stack[stack.size - 2]


                                else -> null


                            }


                            if (splitLeft != null) {


                                Row(Modifier.fillMaxSize()) {


                                    Box(Modifier.width(SPLIT_LIST_WIDTH).fillMaxHeight()) {


                                        // 左栏必须按自己的 380dp 重新测一次窗口：直接沿用整块画布的


                                        // 1280dp 的话，网格会排成 4 列，每张卡只剩 80dp 宽、标题全被省略号吃掉。


                                        ProvidePhoneWindow { renderRoute(splitLeft, false) }


                                    }


                                    Box(


                                        Modifier


                                            .width(1.dp)


                                            .fillMaxHeight()


                                            .background(Color.White.copy(alpha = 0.08f)),


                                    )


                                    Box(Modifier.weight(1f).fillMaxHeight()) {


                                        ProvidePhoneWindow { renderRoute(route, true) }


                                    }


                                }


                            } else {


                                renderRoute(route, false)


                            }


                        }


                        // 播放页不显示迷你条：同一个进度与暂停键在一屏里出现两次，用户会犹豫该点哪个。


                        // 搜索页保留迷你条——在搜索结果里点了歌，如果没有任何可见反馈，用户会以为没点上；


                        // 键盘弹起时它正好落在键盘上沿，看得见也不挡输入。


                        if (playback.hasMedia && route !is LibraryRoute.Player) {


                            MiniPlayerBar(


                                playback = playback,


                                onOpenPlayer = openPlayer,


                                onTogglePlay = { container.playbackController.playPause() },


                                onNext = { container.playbackController.next() },


                            )


                        }


                        // 底栏只在「强任务态」二级页收起来（搜索、平台表单、音源配对、播放页），


                        // 判定与理由见 hidesBottomBar。内容区是 weight(1f)，收起与展开都只是重新分配高度。


                        if (withRail) {


                            // 侧栏模式下没有底栏兜底，迷你条会直接贴到手势条上，这里补一条等高的占位。


                            Spacer(Modifier.navigationBarsPadding())


                        } else if (!route.hidesBottomBar()) {


                            PhoneBottomBar(route = route, onSelect = selectTab)


                        }


                    }


                }


                }


                if (guideVisible) {


                    PhoneOnboarding(


                        onFinish = {


                            phoneSetGuideSeen(context)


                            guideVisible = false


                        },


                        onOpenPlatforms = {


                            phoneSetGuideSeen(context)


                            guideVisible = false


                            root(LibraryRoute.My)


                            open(LibraryRoute.PlatformManage)


                        },


                    )


                }


            }


        }


    }


}





/**


 * 宽屏左侧图标栏。


 *


 * 为什么不用 Material 的 `NavigationRail`：手机壳整体没套 material3 主题（`Text` 用的是


 * tv-material3），拉一个 M3 组件进来会带上一整套 M3 默认色与字号，在这套深蓝黑 + 珊瑚金的


 * 配色里显得突兀。四个图标 + 标签手写一遍只有几十行，样式与底部导航完全同源。


 */


@Composable


private fun PhoneNavRail(


    route: LibraryRoute,


    onSelect: (LibraryRoute) -> Unit,


    modifier: Modifier = Modifier,


) {


    val selectedRoot = route.tabRootOrNull()


    Column(


        modifier


            .width(LocalCarProfile.current.navRailWidth)


            .background(FnColors.Surface),


        horizontalAlignment = Alignment.CenterHorizontally,


    ) {


        Spacer(Modifier.height(PhoneSpace.sm))


        PhoneTabs.forEach { tab ->


            PhoneNavRailItem(


                tab = tab,


                selected = tab.route == selectedRoot,


                onClick = { onSelect(tab.route) },


                modifier = Modifier.fillMaxWidth(),


            )


        }


    }


}





@Composable


private fun PhoneNavRailItem(


    tab: PhoneTab,


    selected: Boolean,


    onClick: () -> Unit,


    modifier: Modifier = Modifier,


) {


    val interaction = remember { MutableInteractionSource() }


    val pressed by interaction.collectIsPressedAsState()


    val tint = when {


        selected -> FnColors.Coral


        pressed -> FnColors.Text


        else -> FnColors.Muted


    }


    Box(


        modifier


            .height(LocalCarProfile.current.navRailItem)


            .clickable(interactionSource = interaction, indication = null, onClick = onClick),


        contentAlignment = Alignment.Center,


    ) {


        // 选中指示条贴左边缘：底栏是靠「图标+文字变珊瑚」表达选中，竖条上没有横向空间，


        // 补一条竖条让当前所在页在余光里也看得出来。


        if (selected) {


            Box(


                Modifier


                    .align(Alignment.CenterStart)


                    .width(3.dp)


                    .height(26.dp)


                    .background(


                        FnColors.Coral,


                        RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp),


                    ),


            )


        }


        Column(


            horizontalAlignment = Alignment.CenterHorizontally,


            verticalArrangement = Arrangement.Center,


        ) {


            PhoneIcon(glyph = tab.glyph, tint = tint, modifier = Modifier.size(22.dp))


            Spacer(Modifier.height(3.dp))


            Text(


                tab.label,


                color = tint,


                fontSize = PhoneType.Micro,


                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,


                maxLines = 1,


            )


        }


    }


}





/**


 * 手机端路由落点。


 *


 * 五个曲库路由（专辑/歌手/歌单/歌曲/收藏）都落到同一个 [PhoneLibraryPage]，由分段选中态区分；


 * 详情页共用 [PhoneTrackCollectionPage]，曲目来源由各路由自己的 loader 决定。


 */


@Composable


private fun PhoneRouteHost(


    route: LibraryRoute,


    session: SessionState.SignedIn,


    playback: PlaybackUiState,


    onRoot: (LibraryRoute) -> Unit,


    onOpen: (LibraryRoute) -> Unit,


    onOpenPlayer: () -> Unit,


    onPlayTracks: (List<Track>, Int) -> Unit,


    /** 随机播放：起点随机 + 切播放器随机模式，与电视端同语义。 */
    onShuffleTracks: (List<Track>) -> Unit,


    onOpenSettings: (PhoneSettingsPane) -> Unit,


    settingsEntry: PhoneSettingsPane,


    onBack: () -> Unit,


    // 搜索会话状态由外壳持有、这里只借用：进详情页时搜索页会被整个换出组合，
    // 状态放在被换掉的那一层，就等于每次返回都从零开始（详见 PhoneSearchRetention）。
    searchRetention: PhoneSearchRetention,


    embeddedDetail: Boolean = false,


) {


    val deps = LocalPhoneDeps.current


    val context = LocalContext.current


    val playingId = playback.mediaId.takeIf { it.isNotBlank() }


    val scope = rememberCoroutineScope()


    val favoriteLibrary by deps.musicRepository.favoriteState.collectAsStateWithLifecycle()


    // 收藏切换集中在这里：三个详情页 + 搜索页都用同一份逻辑，


    // 各页面自己写一遍的话，「点了心形但列表没变」会变成要修三次的问题。


    val toggleFavorite: (Track, Boolean) -> Unit = { track, current ->


        // 把曲目本身一起传下去：没有服务端收藏的平台要把它写进本机收藏表，


        // 只传 guid 的话数据层只能反查，在线音源上常常查不到，收藏页就成了「未知曲目」。


        scope.launch { deps.musicRepository.toggleFavorite(track.guid.value, current, track) }


    }





    val segment = route.librarySegmentOrNull()


    if (segment != null) {


        PhoneLibraryPage(


            segment = segment,


            playingTrackId = playingId,


            onSelectSegment = { onRoot(it.rootRoute()) },


            onOpenAlbum = { onOpen(LibraryRoute.AlbumDetail(it)) },


            onOpenArtist = { onOpen(LibraryRoute.ArtistDetail(it)) },


            onOpenPlaylist = { onOpen(LibraryRoute.PlaylistDetail(it)) },


            onPlayTracks = onPlayTracks,


        )


        return


    }





    when (route) {


        LibraryRoute.Home -> PhoneHomePage(


            session = session,


            playback = playback,


            onOpenSearch = { onOpen(LibraryRoute.Search) },


            onOpenAlbums = { onRoot(LibraryRoute.Albums) },


            onOpenAllPlaylists = { onRoot(LibraryRoute.AllPlaylists) },


            onOpenFavorites = { onRoot(LibraryRoute.Favorites) },


            onOpenAlbum = { onOpen(LibraryRoute.AlbumDetail(it)) },


            onOpenPlaylist = { onOpen(LibraryRoute.PlaylistDetail(it)) },


        )





        is LibraryRoute.AlbumDetail -> {


            val album = route.album


            PhoneTrackCollectionPage(


                stateKey = "phone:album:${album.guid.value}:tracks",


                kindLabel = "专辑",


                title = album.name,


                coverId = album.coverId,


                subtitle = listOfNotNull(


                    album.artistName?.takeIf { it.isNotBlank() },


                    trackCountLabel(album.trackCount),


                ).joinToString(" · "),


                onBack = onBack,


                showBack = !embeddedDetail,


                loader = { page -> deps.musicRepository.albumTracks(album.guid.value, page) },


                emptyTitle = "这张专辑没有曲目",


                emptyCaption = null,


                playingTrackId = playingId,


                onPlayTracks = onPlayTracks,


                onShuffleTracks = onShuffleTracks,


                favoriteStatuses = favoriteLibrary.statuses,


                onToggleFavorite = toggleFavorite,


            )


        }





        is LibraryRoute.PlaylistDetail -> {


            val playlist = route.playlist


            PhoneTrackCollectionPage(


                stateKey = "phone:playlist:${playlist.guid.value}:tracks",


                kindLabel = "歌单",


                title = playlist.name,


                coverId = playlist.coverId,


                subtitle = trackCountLabel(playlist.trackCount).orEmpty(),


                onBack = onBack,


                showBack = !embeddedDetail,


                loader = { page -> deps.musicRepository.playlistTracks(playlist.guid.value, page) },


                emptyTitle = "这个歌单还没有歌曲",


                emptyCaption = "在音乐平台上给它添几首",


                playingTrackId = playingId,


                onPlayTracks = onPlayTracks,


                onShuffleTracks = onShuffleTracks,


                favoriteStatuses = favoriteLibrary.statuses,


                onToggleFavorite = toggleFavorite,


            )


        }





        is LibraryRoute.ArtistDetail -> {


            val artist = route.artist


            PhoneTrackCollectionPage(


                stateKey = "phone:artist:${artist.guid.value}:tracks",


                kindLabel = "歌手",


                title = artist.name,


                coverId = artist.coverId,


                subtitle = listOfNotNull(


                    trackCountLabel(artist.trackCount),


                    artist.albumCount?.let { "${phoneCount(it)} 张专辑" },


                ).joinToString(" · "),


                onBack = onBack,


                showBack = !embeddedDetail,


                loader = { page -> deps.musicRepository.artistTracks(artist.guid.value, page) },


                emptyTitle = "这位歌手没有曲目",


                emptyCaption = null,


                playingTrackId = playingId,


                onPlayTracks = onPlayTracks,


                onShuffleTracks = onShuffleTracks,


                coverShape = CircleShape,


                favoriteStatuses = favoriteLibrary.statuses,


                onToggleFavorite = toggleFavorite,


                // 歌手页底部的专辑带：电视端这块是独立的「专辑」Tab，竖屏改成一条横滑影带，


                // 既保留了「这个歌手有哪些专辑」，又不用多一次 Tab 切换。


                relatedAlbums = PhoneRelatedAlbums(


                    title = "专辑",


                    loader = { page -> deps.musicRepository.artistAlbums(artist.guid.value, page) },


                ),


                onOpenAlbum = { onOpen(LibraryRoute.AlbumDetail(it)) },


            )


        }





        is LibraryRoute.Player -> PhonePlayerPage(


            playback = playback,


            onBack = onBack,


        )





        LibraryRoute.My -> PhoneMyPage(


            session = session,


            onOpenSegment = { onRoot(it.rootRoute()) },


            // 平台与音源是「我的」下的二级页：压栈进入，返回键回到「我的」。


            // 之前用 onRoot 会清掉栈底的「我的」，返回键直接落到首页。


            onOpenPlatforms = { onOpen(LibraryRoute.PlatformManage) },


            onOpenPane = onOpenSettings,


            onOpenSearch = { onOpen(LibraryRoute.Search) },


        )





        LibraryRoute.Search -> PhoneSearchPage(


            retention = searchRetention,


            playingTrackId = playingId,


            onBack = onBack,


            onPlayTracks = onPlayTracks,


            onOpenAlbum = { onOpen(LibraryRoute.AlbumDetail(it)) },


            onOpenArtist = { onOpen(LibraryRoute.ArtistDetail(it)) },


            onOpenPlaylist = { onOpen(LibraryRoute.PlaylistDetail(it)) },


        )





        LibraryRoute.Settings -> PhoneSettingsPage(


            entryPane = settingsEntry,


            onBack = onBack,


        )





        LibraryRoute.PlatformManage -> PhonePlatformManagePage(


            // 飞牛账号是登录账号本身（server guid 不是 local 就说明登录的是真飞牛），


            // 它不是一条 ConnectProfile，所以单独作为一行出现。


            fnSessionActive = session.server.guid.value != "local",


            onBack = onBack,


            onAdd = { onOpen(LibraryRoute.PlatformPicker) },

            onOpenSources = { onOpen(LibraryRoute.MusicSources) },
            onRelogin = { onOpen(LibraryRoute.PlatformFnLogin) },


        )





        LibraryRoute.PlatformPicker -> PhonePlatformPickerPage(


            onBack = onBack,


            onPick = { type ->


                when (type) {


                    // 在线音乐没有账号表单：点即建条目并返回列表，条目就出现在刚才那一屏里。


                    // 它只是一个「壳」，多个音源脚本都挂在它下面（音源脚本页可反复添加），


                    // 所以再点一次只会切换过去，不会多出第二条「在线音乐」。


                    com.yueyin.tv.core.model.connect.ConnectType.ONLINE -> {


                        val existed = onlineMusicAdded()


                        // 加完「在线音乐」直接落到下一步该做的事上：没有脚本就进「添加音源脚本」，


                        // 已经有脚本就进脚本列表。以前是回列表让人自己再找入口，等于白加一次。


                        val hasSources = com.yueyin.tv.ui.source.MusicSourceRuntime


                            .state.value.sources.isNotEmpty()


                        activateOnlineMusic()


                        Toast.makeText(


                            context,


                            when {


                                existed -> "「在线音乐」已经在列表里，已切换过去"


                                hasSources -> "已添加「在线音乐」"


                                else -> "已添加「在线音乐」，接下来导入音源脚本"


                            },


                            Toast.LENGTH_SHORT,


                        ).show()


                        // 先把「新增平台」这页从栈里换掉，再压入脚本页：


                        // 这样从脚本页返回落回平台管理（能看到新加的那一行），而不是又回到选平台的列表。


                        onBack()


                        onOpen(


                            if (hasSources) LibraryRoute.MusicSources else LibraryRoute.MusicSourceReceive,


                        )


                    }





                    // 飞牛音乐 = 登录账号本身。有会话就直接切过去；没有就进应用内的登录页


                    // （普通路由，返回键可回平台列表）。之前这里只弹一句 Toast 就结束，


                    // 用户既没登录入口也不知道该去哪，等于把飞牛挡在门外。


                    com.yueyin.tv.core.model.connect.ConnectType.FEINIU -> {


                        if (session.server.guid.value != "local") {


                            ConnectRuntime.activate(null)


                            Toast.makeText(context, "已切换到「飞牛音乐」", Toast.LENGTH_SHORT).show()


                            onBack()


                        } else {


                            onOpen(LibraryRoute.PlatformFnLogin)


                        }


                    }





                    else -> onOpen(LibraryRoute.PlatformForm(type))


                }


            },


        )





        LibraryRoute.PlatformFnLogin -> PhoneFnLoginPage(


            onBack = onBack,


            onLoggedIn = {


                // 登录成功后会话换成真飞牛，壳本身会按新账号重建（回到首页）；


                // 这里把栈也收回到首页，避免极端情况下停在登录页上。


                onRoot(LibraryRoute.Home)


            },


        )





        is LibraryRoute.PlatformForm -> {


            val profiles by ConnectRuntime.store.state.collectAsStateWithLifecycle()


            PhonePlatformFormPage(


                type = route.type,


                existing = route.editProfileId?.let { id -> profiles.profiles.firstOrNull { it.id == id } },


                onBack = onBack,


                onSaved = onBack,


            )


        }





        LibraryRoute.MusicSources -> PhoneMusicSourcePage(


            onBack = onBack,


            onAdd = { onOpen(LibraryRoute.MusicSourceReceive) },


        )





        LibraryRoute.MusicSourceReceive -> PhoneSourceImportPage(


            onBack = onBack,


            onDone = onBack,


        )





        LibraryRoute.LocalMusic -> PhoneLocalMusicPage(


            onBack = onBack,


            playingTrackId = playingId,


        )


        else -> PhonePlaceholderPage(


            title = "即将接入",


            subtitle = route::class.simpleName ?: "页面",


            stage = "后续分期",


            stageIndex = 3,


            modules = listOf("该页面已在路由表中登记，界面待接入"),


        )


    }


}





/** 分段 → 路由。分段条点一下等于把栈底换成对应路由，返回键语义因此与电视端完全一致。 */


private fun PhoneLibrarySegment.rootRoute(): LibraryRoute = when (this) {


    PhoneLibrarySegment.Albums -> LibraryRoute.Albums


    PhoneLibrarySegment.Artists -> LibraryRoute.Artists


    PhoneLibrarySegment.Playlists -> LibraryRoute.AllPlaylists


    PhoneLibrarySegment.Tracks -> LibraryRoute.AllTracks


    PhoneLibrarySegment.Favorites -> LibraryRoute.Favorites


}





// ---------------------------------------------------------------------------


// 底部导航


// ---------------------------------------------------------------------------





/** 底部导航项。route 是该 Tab 的**根路由**，点它等于 root(route)。 */


private data class PhoneTab(


    val route: LibraryRoute,


    val label: String,


    val glyph: PhoneGlyph,


)





private val PhoneTabs = listOf(


    PhoneTab(LibraryRoute.Home, "首页", PhoneGlyph.Home),


    PhoneTab(LibraryRoute.Albums, "曲库", PhoneGlyph.Library),


    PhoneTab(LibraryRoute.My, "我的", PhoneGlyph.My),


)





/**


 * 当前路由归属哪个 Tab 根。二级页（详情 / 播放 / 搜索）返回 null —— 不高亮任何 Tab，


 * 避免用户停在专辑详情页时看到「曲库」被点亮却点不回去的困惑。


 */


private fun LibraryRoute.tabRootOrNull(): LibraryRoute? = when (this) {


    LibraryRoute.Home -> LibraryRoute.Home


    LibraryRoute.Albums,


    LibraryRoute.Artists,


    LibraryRoute.AllTracks,


    LibraryRoute.Favorites,


    LibraryRoute.AllPlaylists,


    LibraryRoute.LocalMusic,


    -> LibraryRoute.Albums





    LibraryRoute.My -> LibraryRoute.My


    else -> null


}





/**


 * 哪些二级页不挂底栏。


 *


 * 判定标准只有一条：**这一页是否处在「输入/配对中」的强任务态**——这种页面中途点底栏跳走，


 * 已经敲进去的地址、账号、已选脚本都会丢，所以把出口收敛成只有「返回」一个。


 * 按此标准隐藏的是：搜索（键盘在场）、平台表单（键盘 + 待提交）、音源局域网接收（配对进行中）、


 * 以及播放页（全屏沉浸，不属于任何导航层级）。


 *


 * 反过来，**浏览型**的二级页保留底栏：平台管理、平台类型列表、在线音源清单，以及设置里的


 * 五个面板。它们与设置面板同属「设置」这一层，若一个有一个没有，用户从设置点进去底栏会突然


 * 消失、返回又出现，看起来像跳出了应用外壳。


 */


private fun LibraryRoute.hidesBottomBar(): Boolean = when (this) {


    is LibraryRoute.Player,


    LibraryRoute.Search,


    LibraryRoute.MusicSourceReceive,


    is LibraryRoute.PlatformForm,


    -> true





    else -> false


}





/** 详情类路由：平板双栏里放在右栏的那一类。 */


private fun LibraryRoute.isDetailRoute(): Boolean = when (this) {


    is LibraryRoute.AlbumDetail,


    is LibraryRoute.PlaylistDetail,


    is LibraryRoute.ArtistDetail,


    -> true





    else -> false


}





/**


 * 双栏左栏宽度。


 *


 * 380dp 在 900dp（双栏门槛）下给右栏留 520dp，够放详情页的曲目行；


 * 再窄则左栏两列专辑卡每张不足 170dp，封面会明显小于手机版。


 */


private val SPLIT_LIST_WIDTH = 380.dp





/**


 * 单列信息页的最大阅读宽度。


 *


 * 平板横屏 1280dp 时，设置页的「标题 —————— 值」会隔出大半个屏幕，眼睛要来回扫。


 * 720dp 大约是一屏能同时看清左右两端的上限，再宽就得靠移动视线了。


 * 只对单列信息页生效：曲库网格、首页影带、播放页都需要吃满宽度，限宽反而浪费屏幕。


 */


private val PHONE_READING_WIDTH = 720.dp





/** 单列信息型路由：平板下需要限宽居中，理由见 [PHONE_READING_WIDTH]。 */


private fun LibraryRoute.needsReadingWidth(): Boolean = when (this) {


    LibraryRoute.Settings,


    LibraryRoute.My,


    LibraryRoute.PlatformManage,


    LibraryRoute.PlatformPicker,


    LibraryRoute.MusicSources,


    LibraryRoute.MusicSourceReceive,


    is LibraryRoute.PlatformForm,


    -> true





    else -> false


}





/**


 * 限宽容器。


 *


 * [enabled] 为假时直接透传内容，不多包一层布局——多一层 Box 会让内部 `fillMaxSize` 的页面


 * 多一次测量，且 `weight` 之类的约束偶尔会因此对不齐。只在真的需要限宽时才加壳。


 */


@Composable


private fun PhoneReadingFrame(enabled: Boolean, content: @Composable () -> Unit) {


    if (!enabled) {


        content()


        return


    }


    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {


        Box(Modifier.fillMaxHeight().widthIn(max = PHONE_READING_WIDTH).fillMaxWidth()) {


            content()


        }


    }


}





@Composable


private fun PhoneBottomBar(route: LibraryRoute, onSelect: (LibraryRoute) -> Unit) {


    val selectedRoot = route.tabRootOrNull()


    // 背景先画满（含手势条区域），再用 navigationBarsPadding 把内容顶上去，


    // 这样底栏色块延伸到屏幕最底部，不会在导航条位置露出一条背景色。


    Column(


        Modifier


            .fillMaxWidth()


            .background(FnColors.Surface)


            .navigationBarsPadding(),


    ) {


        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.06f)))


        Row(Modifier.fillMaxWidth().height(PhoneSize.BottomBar)) {


            PhoneTabs.forEach { tab ->


                PhoneBottomBarItem(


                    tab = tab,


                    selected = tab.route == selectedRoot,


                    onClick = { onSelect(tab.route) },


                    modifier = Modifier.weight(1f).fillMaxHeight(),


                )


            }


        }


    }


}





@Composable


private fun PhoneBottomBarItem(


    tab: PhoneTab,


    selected: Boolean,


    onClick: () -> Unit,


    modifier: Modifier = Modifier,


) {


    // 不用 Material 的涟漪：手机壳没有套 material3 的 Theme，


    // LocalIndication 取到的会和整体风格不一致。这里用透明度变化做按下反馈，轻且统一。


    val interaction = remember { MutableInteractionSource() }


    val pressed by interaction.collectIsPressedAsState()


    val tint = when {


        selected -> FnColors.Coral


        pressed -> FnColors.Text


        else -> FnColors.Muted


    }


    Column(


        modifier


            .clickable(


                interactionSource = interaction,


                indication = null,


                onClick = onClick,


            ),


        horizontalAlignment = Alignment.CenterHorizontally,


        verticalArrangement = Arrangement.Center,


    ) {


        PhoneIcon(glyph = tab.glyph, tint = tint, modifier = Modifier.size(22.dp))


        Spacer(Modifier.height(3.dp))


        Text(


            tab.label,


            color = tint,


            fontSize = PhoneType.Micro,


            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,


            maxLines = 1,


        )


    }


}





// ---------------------------------------------------------------------------


// 迷你播放条


// ---------------------------------------------------------------------------





@Composable


private fun MiniPlayerBar(


    playback: PlaybackUiState,


    onOpenPlayer: () -> Unit,


    onTogglePlay: () -> Unit,


    onNext: () -> Unit,


) {


    Column(Modifier.fillMaxWidth().background(FnColors.Surface)) {


        // 顶部细进度线：2dp 高，不占交互面积，但能一眼看出播到哪了。


        val progress = if (playback.durationMs > 0L) {


            (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f)


        } else {


            0f


        }


        Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.07f))) {


            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(FnColors.Coral))


        }


        Row(


            Modifier


                .fillMaxWidth()


                .height(PhoneSize.MiniPlayer)


                .clickable(onClick = onOpenPlayer)


                .padding(horizontal = PhoneSpace.md),


            verticalAlignment = Alignment.CenterVertically,


        ) {


            PhoneArtwork(


                coverId = playback.coverId,


                title = playback.title,


                modifier = Modifier.size(PhoneSize.RowArt),


                shape = RoundedCornerShape(PhoneRadius.sm),


            )


            Spacer(Modifier.width(PhoneSpace.md))


            Column(Modifier.weight(1f)) {


                Text(


                    playback.title.ifBlank { "未在播放" },


                    color = FnColors.Text,


                    fontSize = PhoneType.Body,


                    fontWeight = FontWeight.Medium,


                    maxLines = 1,


                    overflow = TextOverflow.Ellipsis,


                )


                Spacer(Modifier.height(2.dp))


                Text(


                    playback.artist.ifBlank { "—" },


                    color = FnColors.Muted,


                    fontSize = PhoneType.Caption,


                    maxLines = 1,


                    overflow = TextOverflow.Ellipsis,


                )


            }


            Spacer(Modifier.width(PhoneSpace.sm))


            MiniBarButton(glyph = PhoneGlyph.Next, onClick = onNext, label = "下一首")


            MiniBarButton(


                glyph = if (playback.isPlaying) PhoneGlyph.Pause else PhoneGlyph.Play,


                onClick = onTogglePlay,


                label = if (playback.isPlaying) "暂停" else "播放",


                size = 30.dp,


            )


        }


    }


}





@Composable


private fun MiniBarButton(


    glyph: PhoneGlyph,


    onClick: () -> Unit,


    label: String,


    size: androidx.compose.ui.unit.Dp? = null,


) {


    val interaction = remember { MutableInteractionSource() }


    val pressed by interaction.collectIsPressedAsState()


    Box(


        Modifier


            .size(size ?: LocalCarProfile.current.touchTarget)


            .clickable(interactionSource = interaction, indication = null, onClick = onClick)


            .semantics { contentDescription = label },


        contentAlignment = Alignment.Center,


    ) {


        PhoneIcon(


            glyph = glyph,


            tint = if (pressed) FnColors.Coral else FnColors.Text,


            modifier = Modifier.size(22.dp),


        )


    }


}





// ---------------------------------------------------------------------------


// 占位页


// ---------------------------------------------------------------------------





/**


 * 分期占位页。


 *


 * 刻意做成「说明卡」而不是骨架屏：骨架屏会让人以为一直在加载，


 * 而这里确实还没有内容。写清楚第几期接入、将包含哪些模块，比转圈更有用。


 */


@Composable


private fun PhonePlaceholderPage(


    title: String,


    subtitle: String,


    stage: String,


    stageIndex: Int,


    modules: List<String>,


) {


    Column(


        Modifier


            .fillMaxSize()


            .verticalScroll(rememberScrollState())


            .padding(horizontal = PhoneSpace.screenHorizontal),


    ) {


        Spacer(Modifier.height(PhoneSpace.xl))


        Text(


            title,


            color = FnColors.Text,


            fontSize = PhoneType.Display,


            fontWeight = FontWeight.Bold,


        )


        Spacer(Modifier.height(6.dp))


        Text(subtitle, color = FnColors.Muted, fontSize = PhoneType.Row)


        Spacer(Modifier.height(PhoneSpace.xl))





        Column(


            Modifier


                .fillMaxWidth()


                .background(FnColors.Surface, RoundedCornerShape(PhoneRadius.lg))


                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(PhoneRadius.lg))


                .padding(PhoneSpace.lg),


        ) {


            Row(verticalAlignment = Alignment.CenterVertically) {


                Box(Modifier.size(6.dp).background(FnColors.Coral, CircleShape))


                Spacer(Modifier.width(PhoneSpace.sm))


                Text(


                    stage,


                    color = FnColors.Coral,


                    fontSize = PhoneType.Label,


                    fontWeight = FontWeight.SemiBold,


                )


            }


            Spacer(Modifier.height(PhoneSpace.md))


            modules.forEach { module ->


                Row(


                    Modifier.padding(vertical = 5.dp),


                    verticalAlignment = Alignment.CenterVertically,


                ) {


                    Box(


                        Modifier


                            .size(4.dp)


                            .background(FnColors.Muted.copy(alpha = 0.5f), CircleShape),


                    )


                    Spacer(Modifier.width(10.dp))


                    Text(module, color = FnColors.Text.copy(alpha = 0.86f), fontSize = PhoneType.Body)


                }


            }


        }





        Spacer(Modifier.height(PhoneSpace.lg))


        StageProgress(currentIndex = stageIndex)


        Spacer(Modifier.height(PhoneSpace.lg))


        Text(


            "当前已完成第 1 期：首页、曲库、详情与简版播放页可用。",


            color = FnColors.Muted.copy(alpha = 0.7f),


            fontSize = PhoneType.Caption,


        )


        Spacer(Modifier.height(PhoneSpace.xxl))


    }


}





/**


 * 分期进度指示。


 *


 * 加它是为了解决占位页「只有一张卡、下面一大片空」的观感问题——


 * 骨架期内容本来就少，与其留白，不如把整体进度画出来，用户一眼知道还剩几期、自己现在在哪。


 */


@Composable


private fun StageProgress(currentIndex: Int) {


    val stages = listOf("地基", "首页", "详情", "播放", "设置")


    Row(Modifier.fillMaxWidth()) {


        stages.forEachIndexed { index, label ->


            Column(


                modifier = Modifier.weight(1f),


                horizontalAlignment = Alignment.CenterHorizontally,


            ) {


                Box(


                    Modifier


                        .size(if (index == currentIndex) 9.dp else 7.dp)


                        .background(


                            color = when {


                                index < currentIndex -> FnColors.Coral.copy(alpha = 0.45f)


                                index == currentIndex -> FnColors.Coral


                                else -> Color.White.copy(alpha = 0.16f)


                            },


                            shape = CircleShape,


                        ),


                )


                Spacer(Modifier.height(6.dp))


                Text(


                    label,


                    color = if (index == currentIndex) {


                        FnColors.Coral


                    } else {


                        FnColors.Muted.copy(alpha = 0.7f)


                    },


                    fontSize = PhoneType.Micro,


                    fontWeight = if (index == currentIndex) FontWeight.SemiBold else FontWeight.Normal,


                    maxLines = 1,


                )


            }


        }


    }


}


