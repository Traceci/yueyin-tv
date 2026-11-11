package com.yueyin.tv.phone

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.PlaybackException
import androidx.tv.material3.Text
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.yueyin.tv.NowPlayingResourceState
import com.yueyin.tv.core.audiofx.FxSettings
import com.yueyin.tv.core.cast.CastSource
import com.yueyin.tv.core.lyrics.lyricText
import com.yueyin.tv.core.model.CoverVariant
import com.yueyin.tv.core.model.Track
import com.yueyin.tv.core.model.TrackGuid
import com.yueyin.tv.core.model.playback.PlayMode
import com.yueyin.tv.core.playback.AudioFxController
import com.yueyin.tv.core.playback.PlaybackFailure
import com.yueyin.tv.core.playback.PlaybackUiState
import com.yueyin.tv.ui.FnColors
import com.yueyin.tv.ui.formatDuration
import com.yueyin.tv.ui.playModeLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 手机播放页。
 *
 * 两种样式（封面 / 唱片）共用同一条控制链，只换中间那块内容区——
 * 不是因为好看，而是两种样式用户要的本来就是「同一首歌的不同看法」：
 * 进度、切歌、收藏、队列、音效一个都不能少，所以它们**全都在控制层里，只做一次**。
 *
 * 与电视端 `ImmersivePlayer` 的关系：并列，不继承。电视端那份是按「遥控器 + 10 英尺」写的
 * （焦点组、方向键、side action 按钮），搬过来只会得到一堆点不到的东西。
 * 数据全部来自 core（`NowPlayingPresenter` / `PlaybackController` / `AudioFxController`），两边一致。
 *
 * 手势约定（与系统手势共存）：
 * - 点击空白：切换控制层显隐；
 * - 封面区横滑：切歌，但左右各留 24dp 不接手势——那是系统「返回」的滑动起点；
 * - 进度条按下即定位、拖动跟手、松手落点。
 */
@Composable
internal fun PhonePlayerPage(
    playback: PlaybackUiState,
    onBack: () -> Unit,
) {
    val deps = LocalPhoneDeps.current
    val scope = rememberCoroutineScope()
    val prefs by deps.appPreferences.state.collectAsStateWithLifecycle()
    val presentation by deps.nowPlayingPresenter.state.collectAsStateWithLifecycle()
    val progress by deps.playbackController.progress.collectAsStateWithLifecycle()
    val favoriteState by deps.musicRepository.favoriteState.collectAsStateWithLifecycle()
    val fxSettings by AudioFxController.state.collectAsStateWithLifecycle()

    val metadata = (presentation?.metadata as? NowPlayingResourceState.Ready)?.value
    val lyricsResource = presentation?.lyrics
    val currentLyrics = (lyricsResource as? NowPlayingResourceState.Ready)?.value
    // 有同步时间轴就用同步歌词；只有纯文本时才退化成静态歌词（电视端同一套判定）。
    val syncedLyrics = currentLyrics?.syncedLyrics
    val staticLyric = currentLyrics?.document?.content?.takeIf { syncedLyrics == null }

    // 播放进度优先用 progress 流：它按播放器回调更新，比 PlaybackUiState 里的快照更跟手。
    // 但刚起播或缓冲时 progress 还是 0，这时回退到快照，避免进度条突然跳回开头。
    val positionMs = progress.positionMs.takeIf { it > 0L } ?: playback.positionMs
    val durationMs = progress.durationMs.takeIf { it > 0L } ?: playback.durationMs

    val isFavorite = favoriteState.statuses[playback.mediaId] ?: metadata?.isFavorite ?: false
    val favoritePending = playback.mediaId in favoriteState.pending

    // 封面区的呈现方式。只有「封面 / 唱片」两档（歌词不再是样式，海报样式已下线），
    // 轻点封面在两者之间切换；选择存在手机本地偏好里，下次进播放页还是这一档。
    val context = LocalContext.current
    var viewMode by remember { mutableStateOf(phoneViewMode(context)) }
    val cycleViewMode = {
        val nextMode = viewMode.next()
        viewMode = nextMode
        phoneSetViewMode(context, nextMode)
    }
    var fullLyrics by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    var fxOpen by remember { mutableStateOf(false) }
    var castOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    val castState by deps.castController.state.collectAsStateWithLifecycle()
    // 定时状态活在 PhoneSleepTimer 里（进程级），这里只是订阅显示。
    val sleepState by PhoneSleepTimer.state.collectAsStateWithLifecycle()

    // 投播当前这一首：把播放器里真正的 URI 交给投播层（本地是 content://，
    // 协议平台是 127.0.0.1 代理，在线是直链），统一由局域网中转服务转给电视。
    val startCast: (com.yueyin.tv.core.cast.DlnaDevice) -> Unit = { device ->
        val current = deps.playbackController.currentCastSource()
        if (current == null) {
            Toast.makeText(context, "当前没有可投播的曲目", Toast.LENGTH_SHORT).show()
        } else {
            val (track, uri, position) = current
            scope.launch {
                val result = deps.castController.castTo(
                    device = device,
                    source = CastSource(
                        title = track.title,
                        artist = track.artistName,
                        album = track.albumName,
                        mimeType = castMimeOf(track.audioFormat),
                        uri = uri,
                        durationMs = track.durationMs,
                    ),
                    positionMs = position,
                )
                if (result.isSuccess) {
                    // 电视接管后本机必须静音，否则两边同时出声。
                    deps.playbackController.pauseForCast()
                    castOpen = false
                } else {
                    Toast.makeText(context, result.exceptionOrNull()?.message ?: "投播失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val landscape = LocalPhoneWindow.current.isLandscape
    // 模糊背景（封面取色）开关：默认开。开了就在两种样式下共用同一张放大+模糊的封面背景。
    val blurredBackdrop = prefs.blurredBackdrop

    val togglePlay = { deps.playbackController.playPause() }
    val next = { deps.playbackController.next() }
    val previous = { deps.playbackController.previous() }

    // 控制面板竖屏直接作为页面最后一段、横屏挂在右侧常驻栏，两处显示同一套内容。
    // 写成 lambda 而不抽私有函数：它的入参几乎全是本作用域里的局部量（进度、收藏态、四个回调），
    // 抽出去要新增十几个参数，等于把页面的内部状态摊到签名上，还没少写一行。
    //
    // 参数 sidePanel = true 表示「挂在横屏右栏」：那种情况下外层已经铺好了统一背景与导航条内边距，
    // 控制面板自己再压一层渐变就会变成一块孤零零的深色卡片（横屏截图里那个方块就是这么来的）。
    val controlPanel: @Composable (Boolean) -> Unit = { sidePanel ->
        ControlPanel(
            playback = playback,
            positionMs = positionMs,
            durationMs = durationMs,
            bufferedMs = progress.bufferedMs,
            isFavorite = isFavorite,
            favoritePending = favoritePending,
            fxLabel = if (fxSettings.enabled) fxSettings.effectLabel else null,
            onTogglePlay = togglePlay,
            onNext = next,
            onPrevious = previous,
            onSeek = { deps.playbackController.seekTo(it) },
            onToggleFavorite = {
                // 元数据还在加载时用播放状态自己拼一个快照，而不是直接跳过。
                // 早先的写法是「没有 metadata 就不收藏」，结果是点了心形毫无反应——
                // 用户分不清是自己没点中还是功能坏了。播放状态里 title/artist/cover
                // 都是现成的，拼出来的条目在收藏页照样能正常显示。
                val snapshot = metadata ?: Track(
                    guid = TrackGuid(playback.mediaId),
                    title = playback.title,
                    artistName = playback.artist.takeIf { it.isNotBlank() },
                    albumName = null,
                    coverId = playback.coverId,
                    durationMs = durationMs.takeIf { it > 0L },
                    isCue = false,
                    isFavorite = isFavorite,
                )
                scope.launch {
                    deps.musicRepository.toggleFavorite(
                        trackGuid = playback.mediaId,
                        fallbackFavorite = isFavorite,
                        snapshot = snapshot,
                    )
                }
            },
            onCyclePlayMode = { deps.playbackController.cyclePlayMode() },
            onOpenQueue = { queueOpen = true },
            onOpenFx = { fxOpen = true },
            sleepMinutesLeft = sleepState.leftMinutes.takeIf { sleepState.running },
            onOpenSleepTimer = { sleepOpen = true },
            // 控制层不再自己压渐变：可读性遮罩已经是整屏一张，
            // 再给面板套一层就会在面板顶边留下一条硬分界线（黑胶态那道缝就是这么来的）。
            backdrop = false,
            navInset = !sidePanel,
        )
    }

    // 两个弹层在「播放页」和「全歌词页」都要能用：全歌词页的控制栏是照搬过来的，
    // 队列 / 音效按钮若只在播放页组合，点上去只会把状态置 true 却什么也不弹。
    // 收成一个 lambda，两处调用同一份定义，避免以后改一处漏一处。
    val overlays: @Composable () -> Unit = {
        PhoneQueueSheet(
            visible = queueOpen,
            items = playback.queueItems,
            loadedCount = playback.loadedPlayableCount,
            queueError = playback.queueError,
            canRetry = playback.canRetryQueue,
            onRetry = { deps.playbackController.retryQueuePage() },
            onSelect = { index ->
                deps.playbackController.selectQueueItem(index)
                queueOpen = false
            },
            onRemove = { index -> deps.playbackController.removeQueueItem(index) },
            onDismiss = { queueOpen = false },
        )

        PhoneFxSheet(
            visible = fxOpen,
            current = fxSettings,
            onApply = { next: FxSettings -> AudioFxController.update(next) },
            onDismiss = { fxOpen = false },
        )

        PhoneCastSheet(
            visible = castOpen,
            state = castState,
            onScan = { deps.castController.startScan() },
            onStop = {
                scope.launch { deps.castController.stopCast() }
            },
            onPick = startCast,
            onDismiss = {
                castOpen = false
                deps.castController.stopScan()
            },
        )

        PhoneSleepSheet(
            visible = sleepOpen,
            state = sleepState,
            onStart = { minutes ->
                // 到点只暂停：不动队列、不改进度，醒来还能接着播。
                PhoneSleepTimer.start(minutes) {
                    deps.playbackController.pause()
                    Toast.makeText(context, "定时结束，已停止播放", Toast.LENGTH_SHORT).show()
                }
                sleepOpen = false
            },
            onCancel = { PhoneSleepTimer.cancel() },
            onDismiss = { sleepOpen = false },
        )
    }

    // 状态栏只在横屏隐藏：竖屏保留系统状态栏，用户能随时看到时间/电量/通知。
    // 横屏继续沉浸，退出播放页时按离开瞬间的方向恢复。
    PlayerImmersiveBars(hide = landscape)

    // 全歌词页盖在最上，其余布局照常：歌词页自己带返回，进出自如，
    // 播放不中断、控制层也不会因为切页而重排。
    if (fullLyrics) {
        Box(Modifier.fillMaxSize()) {
            FullLyricsPage(
                title = playback.title,
                artist = playback.artist,
                coverId = playback.coverId,
                blurredBackdrop = blurredBackdrop,
                syncedLyrics = syncedLyrics,
                staticLyric = staticLyric,
                loading = lyricsResource is NowPlayingResourceState.Loading,
                failed = lyricsResource is NowPlayingResourceState.RetryableFailure,
                positionMs = positionMs,
                karaokeFill = prefs.karaokeFill,
                onSeekLine = { deps.playbackController.seekTo(it) },
                onBack = { fullLyrics = false },
                // 全歌词页照搬播放页那套常驻控制栏；失败提示条是错误态才出现的条件元素，
                // 不属于控制栏，也不带进全歌词页。
                controlPanel = controlPanel,
            )
            overlays()
        }
        return
    }

    // 整屏分三层，从下往上：模糊封面 → 可读性遮罩 → 前景（顶栏/封面/歌词/控制）。
    //
    // 为什么是「分层」而不是像原来那样「几个盒子拼版」：封面被塞在顶栏与控制栏之间那个
    // 盒子里，于是顶上必然空出一条状态栏、下边在控制栏处硬截断；控制面板又各自压了一层
    // 从透明到深底的渐变，面板边界就成了黑胶态里那道「分界线」。分层之后背景铺满整屏、
    // 遮罩只有一张整屏渐变，任何样式都不会再有接缝。
    Box(Modifier.fillMaxSize()) {
        // ① 底：模糊封面铺满整屏（含状态栏与导航条区域），两种样式共用。
        if (blurredBackdrop) {
            PlayerBackdrop(playback.coverId, playback.title)
        } else {
            Box(Modifier.fillMaxSize().background(FnColors.Background))
        }

        // ② 可读性遮罩：只此一张，整屏渐变。顶部压一点保住顶栏图标，中部留出封面取色，
        // 底部压到近黑给曲名、歌词、进度条当底。它没有边界，所以不存在「从哪儿开始变深」的接缝。
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Black.copy(alpha = 0.22f),
                        0.26f to Color.Transparent,
                        0.50f to Color.Black.copy(alpha = 0.20f),
                        0.72f to Color.Black.copy(alpha = 0.62f),
                        1.00f to Color.Black.copy(alpha = 0.90f),
                    ),
                ),
        )

        if (landscape) {
            // 横屏：左右各半。左栏「封面/唱片 + 曲名」，右栏「歌词 + 控制」。
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .navigationBarsPadding()
                        .padding(horizontal = PhoneSpace.screenHorizontal),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        StageArt(
                            mode = viewMode,
                            coverId = playback.coverId,
                            title = playback.title,
                            onTap = cycleViewMode,
                            onSwipeNext = next,
                            onSwipePrevious = previous,
                        )
                    }
                    StageTitle(title = playback.title, artist = playback.artist)
                    Spacer(Modifier.height(PhoneSpace.sm))
                }
                // 右：歌词居中 + 控制贴底。两栏各 weight(1f) 对半分，不再写死 340dp——
                // 死宽在 900dp 级的横屏上只占三分之一，控制层被压成一小条，看着像张悬浮卡片。
                // 曲名/歌手已在左侧展示，这里不再重复。
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .navigationBarsPadding()
                        .padding(start = PhoneSpace.md, end = PhoneSpace.screenHorizontal),
                ) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        LandscapeLyrics(
                            syncedLyrics = syncedLyrics,
                            staticLyric = staticLyric,
                            loading = lyricsResource is NowPlayingResourceState.Loading,
                            failed = lyricsResource is NowPlayingResourceState.RetryableFailure,
                            positionMs = positionMs,
                            onClick = { fullLyrics = true },
                        )
                    }
                    // 失败提示排在歌词与控制之间，占版面里的一段；不再浮在歌词上面。
                    playback.error?.let { failure ->
                        PlaybackErrorBar(
                            failure = failure,
                            onRetry = { deps.playbackController.retryCurrentPlayback() },
                            modifier = Modifier.padding(top = PhoneSpace.sm),
                        )
                    }
                    controlPanel(true)
                }
            }
            // 顶栏脱离排版、压在左上角：封面才能顶到屏幕最上沿，不被一条 52dp 的栏切掉。
            Box(Modifier.align(Alignment.TopCenter)) {
                TopBar(
                    onBack = onBack,
                    castState = castState,
                    onOpenCast = {
                        castOpen = true
                        deps.castController.startScan()
                    },
                    onStopCast = { scope.launch { deps.castController.stopCast() } },
                )
            }
        } else {
            // 竖屏：顶栏 → 曲名/演唱者 → 封面/唱片 → 三行歌词 → 控制栏。
            //
            // 三段固定高度 + 整组 Center 居中。以前用 SpaceEvenly 有两个坑：
            // ① StageTitle 没有自己的 Column，歌名/Spacer/歌手会各自成为外层
            //    Column 的子项，5 个子项被均分 6 道缝，歌名与歌手之间凭空多出一大截；
            // ② 歌词条在「无歌词约 30dp / 有歌词约 96dp」之间变化，剩余空间一变，
            //    所有缝重新均分，封面跟着上移约 33dp。固定高度后封面位置与歌词有无解耦。
            Column(Modifier.fillMaxSize()) {
                TopBar(
                    onBack = onBack,
                    castState = castState,
                    onOpenCast = {
                        castOpen = true
                        deps.castController.startScan()
                    },
                    onStopCast = { scope.launch { deps.castController.stopCast() } },
                )
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    // StageArt 自己会再扣掉两侧 screenHorizontal 与 SWIPE_EDGE_GUARD，
                    // 这里先算出同一条边长，盒子高度与封面一致。
                    val naturalSide = (
                        maxWidth - PhoneSpace.screenHorizontal * 2 - SWIPE_EDGE_GUARD * 2
                        ).coerceAtLeast(120.dp)
                    val titleBlock = 56.dp
                    val lyricsBlock = 96.dp
                    val stageGap = 12.dp
                    val side = minOf(
                        naturalSide,
                        (maxHeight - titleBlock - lyricsBlock - stageGap * 2).coerceAtLeast(120.dp),
                    )
                    // 曲名 / 演唱者单独提到舞台顶部（紧贴返回栏）：用户指出顶上那一大片空白，
                    // 选的是「只把歌名/歌手上移」，封面必须原地不动。所以这里留一块与
                    // titleBlock 等高的占位顶住原来的居中组，封面的绝对位置分毫不变；
                    // 真正的文字块改成 TopCenter 叠在舞台上方。
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.fillMaxWidth().height(titleBlock))
                        Spacer(Modifier.height(stageGap))
                        // ② 封面 / 唱片：高度恒定，位置只由三段总高与居中决定。
                        Box(
                            Modifier.fillMaxWidth().height(side),
                            contentAlignment = Alignment.Center,
                        ) {
                            StageArt(
                                mode = viewMode,
                                coverId = playback.coverId,
                                title = playback.title,
                                onTap = cycleViewMode,
                                onSwipeNext = next,
                                onSwipePrevious = previous,
                            )
                        }
                        Spacer(Modifier.height(stageGap))
                        // ③ 三行歌词：固定高；无歌词时提示也占同样高度，封面不会动。
                        Box(
                            Modifier.fillMaxWidth().height(lyricsBlock),
                            contentAlignment = Alignment.Center,
                        ) {
                            MiniLyricsStrip(
                                syncedLyrics = syncedLyrics,
                                staticLyric = staticLyric,
                                loading = lyricsResource is NowPlayingResourceState.Loading,
                                failed = lyricsResource is NowPlayingResourceState.RetryableFailure,
                                positionMs = positionMs,
                                onClick = { fullLyrics = true },
                            )
                        }
                    }
                    // ① 曲名 + 演唱者：上移到舞台顶部，吃掉原来顶部那片留白。
                    // 用等高占位替代它在居中组里的位置，封面 / 歌词一律不动。
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(titleBlock)
                            .align(Alignment.TopCenter)
                            .padding(top = PhoneSpace.xs),
                        contentAlignment = Alignment.Center,
                    ) {
                        StageTitle(title = playback.title, artist = playback.artist, maxLines = 1)
                    }
                }
                // 失败提示排在歌词与控制之间：之前是浮层，横屏和竖屏都会正好压在歌词那一行上。
                playback.error?.let { failure ->
                    PlaybackErrorBar(
                        failure = failure,
                        onRetry = { deps.playbackController.retryCurrentPlayback() },
                        modifier = Modifier.padding(top = PhoneSpace.sm),
                    )
                }
                controlPanel(false)
            }
        }

        // 播放失败提示不在这里画：它改成信息层里正常的一段（见 PlaybackErrorBar），
        // 之前用 align(BottomCenter) + 固定偏移浮上去，位置只能靠猜，结果横屏/竖屏都会压住歌词。

        overlays()
    }
}

/** 轻点封面在「封面 ⇄ 唱片」之间切换（[PhoneViewMode] 与本地偏好见 PhoneState.kt）。 */
private fun PhoneViewMode.next(): PhoneViewMode = when (this) {
    PhoneViewMode.Cover -> PhoneViewMode.Vinyl
    PhoneViewMode.Vinyl -> PhoneViewMode.Cover
}

/**
 * 播放页状态栏：**只在横屏隐藏**，竖屏保留系统状态栏（时间/电量/通知照常可见）。
 *
 * 只动状态栏、不动导航条——三键导航的返回键长在导航条上，藏掉等于把返回入口一起藏了。
 * 竖屏显示状态栏后 `statusBarsPadding()` 会自动把内容让开，不需要手工补偿像素。
 *
 * 退出时「恢复」的目标不是「显示」而是**按方向该有的状态**：横屏下整个 App 都是沉浸的
 * （见 `PhoneSystemBars`），若无脑 `show()`，从播放页返回会在列表页顶上冒出一条状态栏。
 */
@Composable
private fun PlayerImmersiveBars(hide: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, hide) {
        val window = view.context.findActivityWindow()
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowInsetsControllerCompat(window, view)
            val previousBehavior = controller.systemBarsBehavior
            if (hide) {
                // 横屏：下滑唤出后自动收起，不让状态栏长期占着播放页顶上那条。
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.statusBars())
            } else {
                controller.show(WindowInsetsCompat.Type.statusBars())
            }
            onDispose {
                val landscape = view.resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                if (landscape) {
                    controller.hide(WindowInsetsCompat.Type.statusBars())
                } else {
                    controller.show(WindowInsetsCompat.Type.statusBars())
                }
                controller.systemBarsBehavior = previousBehavior
            }
        }
    }
}

/** 从 Compose 拿到的 Context 往上找宿主 Activity 的 Window；中途常套着几层 ContextThemeWrapper。 */
private fun Context.findActivityWindow(): Window? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current.window
        current = current.baseContext
    }
    return null
}

// ---------------------------------------------------------------------------
// 顶部栏
// ---------------------------------------------------------------------------

@Composable
private fun TopBar(
    onBack: () -> Unit,
    castState: com.yueyin.tv.core.cast.CastUiState,
    onOpenCast: () -> Unit,
    onStopCast: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            // 状态栏内边距放在高度之外：系统栏隐藏时它是 0（真正的沉浸），
            // 从顶部下滑临时唤出系统栏时它会自己让出位置，返回键不会被盖住。
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(PhoneSize.TopBar)
                .padding(horizontal = PhoneSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(LocalCarProfile.current.touchTarget).phoneTap(onBack),
                contentAlignment = Alignment.Center,
            ) {
                PhoneIcon(PhoneGlyph.ChevronDown, FnColors.Text, Modifier.size(22.dp))
            }
            Spacer(Modifier.weight(1f))
            // 投播入口放右上角（AirPlay 习惯位）：不占操作行的五个槽位，
            // 也不会被「收藏 / 队列 / 音效」的语义挤在一起。
            Box(
                Modifier.size(LocalCarProfile.current.touchTarget).phoneTap(onOpenCast),
                contentAlignment = Alignment.Center,
            ) {
                PhoneIcon(
                    PhoneGlyph.Cast,
                    if (castState.hasActive) FnColors.Coral else FnColors.Text,
                    Modifier.size(22.dp),
                )
            }
        }
        if (castState.hasActive) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PhoneSpace.md)
                    .padding(bottom = PhoneSpace.xs)
                    .background(FnColors.Surface.copy(alpha = 0.78f), RoundedCornerShape(12.dp))
                    .padding(horizontal = PhoneSpace.sm, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "正在 ${castState.active?.name.orEmpty()} 播放",
                    color = FnColors.Text,
                    fontSize = PhoneType.Micro,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "停止投播",
                    color = FnColors.Coral,
                    fontSize = PhoneType.Micro,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.phoneTap(onStopCast),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 两种样式的中间区
// ---------------------------------------------------------------------------

/**
 * 封面 / 唱片两种样式的主体：只画中间那块图，曲名与歌词交给页面统一摆放。
 *
 * 之所以不在这里塞标题：标题若长在这里，两种样式要么各复制一份，
 * 要么另写一套版位。抽出来之后两种样式共用同一段信息层，顺序和间距只有一处可改。
 *
 * 左右留 24dp 不接手势，那是系统返回的滑动起点。
 */
@Composable
private fun StageArt(
    mode: PhoneViewMode,
    coverId: String?,
    title: String,
    onTap: () -> Unit,
    onSwipeNext: () -> Unit,
    onSwipePrevious: () -> Unit,
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // 触发阈值取屏宽 18%：约 70dp，既不会被轻微抖动误触，也不用甩很远。
    val threshold = screenWidthDp * 0.18f

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(horizontal = PhoneSpace.screenHorizontal),
        contentAlignment = Alignment.Center,
    ) {
        // 外层已经用 weight 让出了顶栏、曲名、歌词和控制栏，这里拿到的 maxHeight
        // 就是「真正还能用多高」，正方形直接取宽高较小值，不用再猜系数。
        // 宽度这边还要先扣掉两侧 SWIPE_EDGE_GUARD（各 24dp）：那圈留白是给系统返回手势的，
        // 必须算在尺寸之外，否则「先 size 再 padding」会把正方形封面横向压掉 48dp，变成竖长方形。
        val side = minOf(
            (maxWidth - SWIPE_EDGE_GUARD * 2).coerceAtLeast(120.dp),
            maxHeight.coerceAtLeast(120.dp),
        )
        Box(
            Modifier
                .padding(horizontal = SWIPE_EDGE_GUARD)
                .size(side)
                // 轻点封面切换样式、横滑切歌，二者共用一个手势判断：横向位移超过阈值算滑，
                // 否则松手算点。拆成 clickable + 横滑两个 modifier 会互相抢事件。
                .pointerInput(threshold) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var dragX = 0f
                        val touchSlop = viewConfiguration.touchSlop
                        var exceeded = false
                        val drag = try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                dragX += change.position.x - change.previousPosition.x
                                if (kotlin.math.abs(dragX) > touchSlop) exceeded = true
                                change.consume()
                            }
                            dragX
                        } catch (_: CancellationException) {
                            return@awaitEachGesture
                        }
                        if (!exceeded) {
                            onTap()
                        } else {
                            when {
                                drag <= -threshold -> onSwipeNext()
                                drag >= threshold -> onSwipePrevious()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            when (mode) {
                PhoneViewMode.Cover -> PhoneArtwork(
                    coverId = coverId,
                    title = title,
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(PhoneRadius.xl),
                    // 封面要铺到接近屏宽，用 800px 档；没下完时先出 400px 的 Grid。
                    variant = CoverVariant.Player,
                    fallback = CoverVariant.Grid,
                )

                PhoneViewMode.Vinyl -> VinylDisc(coverId = coverId, title = title)
            }
        }
    }
}

/**
 * 黑胶唱片：一圈圈同心纹 + 中心小封面 + 慢速旋转。
 *
 * 旋转用无限动画，只有在这个 composable 进入组合时启动，切走即停。
 */
@Composable
private fun VinylDisc(coverId: String?, title: String) {
    val rotation = rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20_000, easing = LinearEasing),
        ),
        label = "vinyl-rotation",
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // 柔光：电视端是随播放呼吸的一圈光，手机竖屏保留静态版就够——
        // 它不只是装饰，也是盘体 84% 之外那 16% 的用途，没有它圆盘会显得"贴"在背景上。
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        FnColors.Coral.copy(alpha = 0.16f),
                        FnColors.Coral.copy(alpha = 0.05f),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
        // 盘体直径取外框 84%，与电视端 `recordSize = size * 0.84f` 对齐：
        // 之前手机版是 fillMaxSize 撑满，圆盘顶到方框边缘，没有留白也就没有"唱片"的轮廓。
        Box(
            Modifier
                .fillMaxSize(VINYL_RECORD_FRACTION)
                .graphicsLayer { rotationZ = rotation.value },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f
                drawCircle(Color(0xFF101313), radius)
                drawCircle(Color(0xFF242928), radius * 0.96f, style = Stroke(3.dp.toPx()))
                // 14 圈刻纹，每三圈提亮一档——电视端同一组参数。原来手机只有 4 圈且对比极低，
                // 远看就是一块纯黑圆饼。
                for (ring in 1..14) {
                    val ringRadius = radius * (0.52f + ring * 0.029f)
                    drawCircle(
                        Color.White.copy(alpha = if (ring % 3 == 0) 0.09f else 0.045f),
                        ringRadius,
                        style = Stroke(1.dp.toPx()),
                    )
                }
                // 高光：用一道斜向渐变当「光落在盘面上」，而不是画一段粗描边弧。
                // 之前那圈 0.16r 宽的弧带在盘面左侧糊出一块深色圆弧，看着像污渍——
                // 描边弧没有渐隐，圆头端点还会在盘上留下两个色块。
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.10f),
                            Color.White.copy(alpha = 0.02f),
                            Color.Transparent,
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(size.width * 0.85f, size.height * 0.85f),
                    ),
                    radius = radius,
                )
                drawArc(
                    color = FnColors.Coral.copy(alpha = 0.42f),
                    startAngle = -18f,
                    sweepAngle = 36f,
                    useCenter = false,
                    topLeft = Offset(radius * 0.18f, radius * 0.18f),
                    size = Size(radius * 1.64f, radius * 1.64f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            // 中心 label 取盘体 52%：电视端 labelSize=0.44×外框、recordSize=0.84×外框，
            // 折到盘体上正好 0.52。原来写死 92dp，在 340dp 的盘上只占 27%，像贴了张小方图。
            Box(
                Modifier
                    .fillMaxSize(VINYL_LABEL_FRACTION)
                    .clip(CircleShape),
            ) {
                PhoneArtwork(
                    coverId = coverId,
                    title = title,
                    modifier = Modifier.fillMaxSize(),
                    shape = CircleShape,
                )
            }
            // 中心轴孔：电视端是 14dp 的浅灰点，这里按比例给，避免大屏上变成一个针尖。
            Box(
                Modifier
                    .fillMaxSize(VINYL_SPINDLE_FRACTION)
                    .background(Color(0xFF0B0F0E), CircleShape),
            )
        }
    }
}

/** 沉浸式背景：两种样式共用。放大 + 模糊后的封面铺满全屏，只盖一层很轻的渐变——
 * 顶部压一点黑保住状态栏文字可读，中部保住封面取色，只有底部才加深到近不透明，
 * 给歌词与控制栏留可读底。绝不用重黑把整屏吃成黑的。 */
@Composable
private fun PlayerBackdrop(coverId: String?, title: String) {
    Box(Modifier.fillMaxSize()) {
        // 先放大再模糊：blur 会把画面边缘往外晕开，放大 1.3 倍让那圈毛边落到屏幕之外。
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.3f
                    scaleY = 1.3f
                },
        ) {
            PhoneArtwork(
                coverId = coverId,
                title = title,
                modifier = Modifier.fillMaxSize().blur(60.dp),
                shape = RoundedCornerShape(0.dp),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    // 只压到 0.62 而不是近黑：封面上下留白那一段全靠这层模糊背景接住，
                    // 压太狠又会回到「下面一大片死黑」的老问题。
                    // 真正需要压暗的是控制栏，那层由页面级的整屏遮罩负责。
                    0.0f to Color.Black.copy(alpha = 0.30f),
                    0.12f to Color.Black.copy(alpha = 0.14f),
                    0.35f to Color.Black.copy(alpha = 0.16f),
                    0.65f to Color.Black.copy(alpha = 0.28f),
                    1.0f to FnColors.Background.copy(alpha = 0.64f),
                ),
            ),
        )
    }
}

/**
 * 封面下方的三行歌词条：上一句 / 当前句 / 下一句。
 *
 * 当前句珊瑚高亮并放大到 17sp，上下句 15sp 淡显；三行高度恒定，
 * 首句或末句缺行时用空白占位，避免歌词滚动时整块上下跳。
 * 没有歌词时退化成一行说明，整条仍可点进全歌词页（那里有更完整的说明）。
 */
@Composable
private fun MiniLyricsStrip(
    syncedLyrics: SyncedLyrics?,
    staticLyric: String?,
    loading: Boolean,
    failed: Boolean,
    positionMs: Long,
    onClick: () -> Unit,
) {
    val lines = remember(syncedLyrics) {
        syncedLyrics?.lines?.mapNotNull { line ->
            val text = line.lyricText().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            text to line.start.toLong()
        }.orEmpty()
    }
    val currentIndex = remember(lines, positionMs) {
        if (lines.isEmpty()) -1 else lines.indexOfLast { it.second <= positionMs }.coerceAtLeast(0)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PhoneSpace.screenHorizontal)
            .phoneTap(onClick)
            .padding(vertical = PhoneSpace.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (lines.isEmpty()) {
            when {
                loading -> MiniLyricHint("歌词加载中…")
                failed -> MiniLyricHint("歌词加载失败")
                staticLyric != null -> MiniLyricHint("点击查看歌词")
                else -> MiniLyricHint("纯音乐，请欣赏")
            }
            Spacer(Modifier.height(PhoneSpace.sm))
        } else {
            for (offset in -1..1) {
                val text = lines.getOrNull(currentIndex + offset)?.first.orEmpty()
                val isCurrent = offset == 0
                Text(
                    text.ifBlank { " " },
                    color = if (isCurrent) FnColors.Coral else Color.White.copy(alpha = 0.42f),
                    fontSize = if (isCurrent) PhoneType.Section else PhoneType.Body,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = if (isCurrent) PhoneSpace.xs else 2.dp),
                )
            }
        }
    }
}

@Composable
private fun MiniLyricHint(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.40f),
        fontSize = PhoneType.Caption,
        maxLines = 1,
        textAlign = TextAlign.Center,
    )
}

/**
 * 横屏常驻歌词：当前句高亮居中，上下各两句淡出。
 *
 * 竖屏那条 [MiniLyricsStrip] 只有「上一句 + 当前句」两行，挂在封面下方刚好；
 * 但横屏右栏是一整块二百来 dp 高的空白，两行贴顶会显得又空又偏。这里铺 5 行、整块垂直居中，
 * 右栏才立得住，也不会再出现「歌词飘在右上角」的观感。
 */
@Composable
private fun LandscapeLyrics(
    syncedLyrics: SyncedLyrics?,
    staticLyric: String?,
    loading: Boolean,
    failed: Boolean,
    positionMs: Long,
    onClick: () -> Unit,
) {
    val lines = remember(syncedLyrics) {
        syncedLyrics?.lines?.mapNotNull { line ->
            val text = line.lyricText().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            text to line.start.toLong()
        }.orEmpty()
    }
    val currentIndex = remember(lines, positionMs) {
        if (lines.isEmpty()) -1 else lines.indexOfLast { it.second <= positionMs }.coerceAtLeast(0)
    }

    if (lines.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().phoneTap(onClick).padding(vertical = PhoneSpace.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                loading -> MiniLyricHint("歌词加载中…")
                failed -> MiniLyricHint("歌词加载失败")
                staticLyric != null -> MiniLyricHint("点击查看歌词")
                else -> MiniLyricHint("纯音乐，请欣赏")
            }
        }
        return
    }

    Column(
        Modifier
            .fillMaxWidth()
            .phoneTap(onClick)
            .padding(vertical = PhoneSpace.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (offset in -2..2) {
            val text = lines.getOrNull(currentIndex + offset)?.first ?: continue
            val isCurrent = offset == 0
            Text(
                text,
                color = when {
                    isCurrent -> FnColors.Coral
                    // 越远越淡：上下各一句半亮，再往外只留一点轮廓，形成「正在唱」的纵深感。
                    kotlin.math.abs(offset) == 1 -> Color.White.copy(alpha = 0.44f)
                    else -> Color.White.copy(alpha = 0.22f)
                },
                fontSize = if (isCurrent) PhoneType.Section else PhoneType.Row,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = if (isCurrent) PhoneSpace.sm else PhoneSpace.xs),
            )
        }
    }
}

/**
 * 全歌词页：覆盖在播放页之上，可滚动、当前句居中高亮、点某句跳转。
 *
 * 版面按定稿图分三块：顶部返回 + 歌名、中部整块全给歌词、底部常驻控制栏。
 * 控制栏直接复用播放页的 [controlPanel]（进度条 / 时间 / 切歌 / 收藏 / 循环 / 队列 / 音效），
 * 一个不少；失败提示条是错误态才出现的条件元素，不属于控制栏，也不带进这一页。
 */
@Composable
private fun FullLyricsPage(
    title: String,
    artist: String,
    coverId: String?,
    blurredBackdrop: Boolean,
    syncedLyrics: SyncedLyrics?,
    staticLyric: String?,
    loading: Boolean,
    failed: Boolean,
    positionMs: Long,
    karaokeFill: Boolean,
    onSeekLine: (Long) -> Unit,
    onBack: () -> Unit,
    controlPanel: @Composable (Boolean) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // 与播放页同构的沉浸式背景：进全歌词页也不打断沉浸感。
        if (blurredBackdrop) {
            PlayerBackdrop(coverId, title)
        }
        Column(Modifier.fillMaxSize()) {
            // 头部：返回 + 曲名，与播放页顶栏同构。
            // statusBarsPadding 必须在高度之外：状态栏隐藏时它是 0，临时唤出时折叠按钮会自己让开，
            // 否则按钮就被系统栏压住、点不到（横屏全歌词页就是这个毛病）。
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(PhoneSize.TopBar)
                    .padding(horizontal = PhoneSpace.sm)
                    // 整行都是返回热区：原来只有左上角那个 56dp 箭头能点，回播放页很难按到。
                    .phoneTap(onBack),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(LocalCarProfile.current.touchTarget).phoneTap(onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    PhoneIcon(PhoneGlyph.ChevronDown, FnColors.Text, Modifier.size(22.dp))
                }
                Spacer(Modifier.width(PhoneSpace.sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        title.ifBlank { "未在播放" },
                        color = FnColors.Text,
                        fontSize = PhoneType.Body,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (artist.isNotBlank()) {
                        Text(
                            artist,
                            color = FnColors.Muted,
                            fontSize = PhoneType.Micro,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // 中部：整块全部给歌词，当前句由 PhoneLyrics 落在歌词区 42% 处。
            // 这里**不再叠透明返回热区**：原来上/下各 120dp 的 clickable 盖在 LazyColumn 上，
            // 吃掉按下事件，约三分之一歌词区起手拖不动，点一下还会直接退出全歌词页。
            // 返回统一交给顶栏整行（本来就可点）与系统返回键；点歌词行仍是跳转播放进度。
            Box(Modifier.weight(1f)) {
                PhoneLyrics(
                    syncedLyrics = syncedLyrics,
                    staticLyric = staticLyric,
                    loading = loading,
                    failed = failed,
                    positionMs = positionMs,
                    onSeekLine = onSeekLine,
                    karaokeFill = karaokeFill,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 底部：照搬播放页的常驻控制栏。控制层自己不压渐变（backdrop=false），
            // 这里补一层很轻的底渐隐，保证白色进度条在模糊封面上仍然看得清。
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to FnColors.Background.copy(alpha = 0.88f),
                        ),
                    ),
            ) {
                controlPanel(false)
            }
        }
    }
}

@Composable
private fun StageTitle(title: String, artist: String, maxLines: Int = 2) {
    // 必须自己收成一个布局：否则两个 Text + Spacer 会成为调用方 Column 的独立子项，
    // 被 Arrangement 当成三段分别拆开，歌名与歌手之间凭空多出一道大缝。
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            title.ifBlank { "未在播放" },
            color = FnColors.Text,
            fontSize = PhoneType.Headline,
            fontWeight = FontWeight.SemiBold,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(PhoneSpace.sm))
        Text(
            artist.ifBlank { "—" },
            color = FnColors.Muted,
            fontSize = PhoneType.Row,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------
// 控制层
// ---------------------------------------------------------------------------

/**
 * 播放失败提示条。
 *
 * 作为信息层里正常的一段参与排版，而不是浮层：失败提示的高度会随文案换行变化，
 * 用 align + 固定偏移去猜位置，必定会在某个尺寸下压住歌词或进度条。
 */
@Composable
private fun PlaybackErrorBar(
    failure: PlaybackFailure,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = PhoneSpace.screenHorizontal)
            .background(FnColors.Coral.copy(alpha = 0.16f), RoundedCornerShape(PhoneRadius.md))
            .padding(horizontal = PhoneSpace.md, vertical = PhoneSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "播放失败：${failure.userHint()}",
            color = FnColors.Coral,
            fontSize = PhoneType.Caption,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(PhoneSpace.md))
        Text(
            "重试",
            color = FnColors.Text,
            fontSize = PhoneType.Caption,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier
                .phoneTap(onRetry)
                .padding(PhoneSpace.xs),
        )
    }
}

@Composable
private fun ControlPanel(
    playback: PlaybackUiState,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    isFavorite: Boolean,
    favoritePending: Boolean,
    fxLabel: String?,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleFavorite: () -> Unit,
    onCyclePlayMode: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenFx: () -> Unit,
    /** 定时剩余分钟；null 表示没开定时。 */
    sleepMinutesLeft: Int?,
    onOpenSleepTimer: () -> Unit,
    /** 是否压一层「透明→深底」渐变。竖屏浮在模糊封面上需要它；横屏右栏已有统一背景，再压就成了深色方块。 */
    backdrop: Boolean = true,
    /** 是否自己吃系统导航条内边距。横屏右栏的外层已经吃过，这里不重复。 */
    navInset: Boolean = true,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (backdrop) {
                    // 控制层压一层从透明到深底的渐变：背景是模糊封面，
                    // 没有这层的话白色进度条会直接糊在亮色图上看不清。
                    Modifier.background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                FnColors.Background.copy(alpha = 0.86f),
                                FnColors.Background,
                            ),
                        ),
                    )
                } else {
                    Modifier
                },
            )
            .then(if (navInset) Modifier.navigationBarsPadding() else Modifier)
            .padding(horizontal = PhoneSpace.screenHorizontal),
    ) {
        SeekBar(
            positionMs = positionMs,
            durationMs = durationMs,
            bufferedMs = bufferedMs,
            enabled = durationMs > 0L,
            onSeek = onSeek,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatDuration(positionMs), color = FnColors.Muted, fontSize = PhoneType.Micro, modifier = Modifier.weight(1f))
            Text(formatDuration(durationMs), color = FnColors.Muted, fontSize = PhoneType.Micro)
        }

        Row(
            Modifier.fillMaxWidth().padding(top = PhoneSpace.md),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportKey(glyph = PhoneGlyph.Previous, onClick = onPrevious)
            Spacer(Modifier.width(PhoneSpace.xl))
            Box(
                Modifier
                    .size(64.dp)
                    .background(FnColors.Coral, CircleShape)
                    .phoneTap(onTogglePlay),
                contentAlignment = Alignment.Center,
            ) {
                PhoneIcon(
                    glyph = if (playback.isPlaying) PhoneGlyph.Pause else PhoneGlyph.Play,
                    tint = FnColors.Background,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.width(PhoneSpace.xl))
            TransportKey(glyph = PhoneGlyph.Next, onClick = onNext)
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = PhoneSpace.sm, bottom = PhoneSpace.md),
            // 五个键等分铺满：原来 4 个固定 64dp 的键交给 SpaceEvenly 摊空隙，
            // 411dp 上每键之间空出一大截，看着松散。
            horizontalArrangement = Arrangement.spacedBy(PhoneSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionKey(
                glyph = PhoneGlyph.Heart,
                label = "收藏",
                active = isFavorite,
                enabled = !favoritePending && playback.mediaId.isNotBlank(),
                filled = isFavorite,
                onClick = onToggleFavorite,
            )
            ActionKey(
                glyph = PhoneGlyph.Timer,
                label = sleepMinutesLeft?.let { "$it 分" } ?: "定时",
                active = sleepMinutesLeft != null,
                onClick = onOpenSleepTimer,
            )
            ActionKey(
                glyph = playback.playMode.glyph(),
                label = playModeLabel(playback.playMode),
                active = false,
                onClick = onCyclePlayMode,
            )
            ActionKey(
                glyph = PhoneGlyph.Fx,
                label = fxLabel ?: "音效",
                active = fxLabel != null,
                onClick = onOpenFx,
            )
            ActionKey(
                glyph = PhoneGlyph.Queue,
                label = "队列",
                active = false,
                onClick = onOpenQueue,
            )
        }
    }
}

@Composable
private fun TransportKey(glyph: PhoneGlyph, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).phoneTap(onClick),
        contentAlignment = Alignment.Center,
    ) {
        PhoneIcon(glyph, FnColors.Text, Modifier.size(24.dp))
    }
}

/**
 * 底部五个操作键。
 *
 * 文字在图标下面（而不是只要图标）：手机上没有悬停提示，光看一个「均衡器竖条」
 * 猜不出那是音效。标签也顺便把当前状态说清楚了——「单曲循环」「已收藏」「12 分」。
 *
 * 用 `weight(1f)` 而不是固定 64dp：5 个键要在一行里均匀铺满，固定宽 + SpaceEvenly
 * 会随着键数变化露出不均匀的空隙。
 */
@Composable
private fun RowScope.ActionKey(
    glyph: PhoneGlyph,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    filled: Boolean = false,
) {
    val tint = when {
        !enabled -> FnColors.Muted.copy(alpha = 0.36f)
        active -> FnColors.Coral
        else -> FnColors.Text.copy(alpha = 0.86f)
    }
    Column(
        Modifier
            .weight(1f)
            .phoneTap { if (enabled) onClick() }
            .padding(vertical = PhoneSpace.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PhoneIcon(glyph, tint, Modifier.size(21.dp), filled = filled)
        Spacer(Modifier.height(PhoneSpace.xs))
        Text(
            label,
            color = tint,
            fontSize = PhoneType.Micro,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 可拖动进度条。
 *
 * 不用 `Slider`：tv-material3 没提供，引 material3 又会被默认主题染色。
 * 这里按「按下即定位、拖动跟手、松手落点」一次手势做完三件事——
 * 如果拆成「点击 seek」+「拖动 seek」两套检测，两者会互相抢事件，表现为点了没反应。
 */
@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
) {
    var draggingFraction by remember { mutableFloatStateOf(-1f) }
    val playedFraction = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val bufferedFraction = if (durationMs > 0L) {
        (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val shownFraction = if (draggingFraction >= 0f) draggingFraction else playedFraction

    Box(
        Modifier
            .fillMaxWidth()
            // 触摸区 30dp、视觉轨道 4dp：轨道细才好看，但 4dp 高根本按不中。
            .height(30.dp)
            .pointerInput(enabled, durationMs) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var fraction = (down.position.x / size.width).coerceIn(0f, 1f)
                    draggingFraction = fraction
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        draggingFraction = fraction
                        change.consume()
                    }
                    onSeek((fraction * durationMs).toLong())
                    draggingFraction = -1f
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // 轨道单独套一层 CenterStart：外层的 contentAlignment 是 Center，
        // 直接放 fillMaxWidth(比例) 的子项会被**居中**，进度条就成了「从中间往两边长」。
        Box(
            Modifier.fillMaxWidth().height(4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().fillMaxHeight().clip(CircleShape).background(Color.White.copy(alpha = 0.12f)))
            Box(
                Modifier
                    .fillMaxWidth(bufferedFraction)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.20f)),
            )
            Box(
                Modifier
                    .fillMaxWidth(shownFraction)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(FnColors.Coral),
            )
        }
        // 拖动时出现的圆点：没有它用户不知道自己有没有按准
        if (draggingFraction >= 0f) {
            Box(
                Modifier
                    .fillMaxWidth(draggingFraction)
                    .height(4.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(Modifier.size(12.dp).background(Color.White, CircleShape))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 小工具
// ---------------------------------------------------------------------------

/** 系统返回手势的起手区宽度：封面横滑必须让开它，否则右滑切歌会直接退出应用。 */
private val SWIPE_EDGE_GUARD = 24.dp

/**
 * 黑胶的三个比例，全部对齐电视端 `PlayerScreen` 的唱片画法：
 * 盘体 0.84×外框（留 16% 放柔光）、label 0.44/0.84≈0.52×盘体、轴孔按盘体比例给。
 * 手机端之前是「盘撑满 + 4 圈暗纹 + 92dp 贴图」，所以看着像块黑饼而不是唱片。
 */
private const val VINYL_RECORD_FRACTION = 0.84f
private const val VINYL_LABEL_FRACTION = 0.52f
private const val VINYL_SPINDLE_FRACTION = 0.045f

private fun PlayMode.glyph(): PhoneGlyph = when (this) {
    PlayMode.ListRepeat -> PhoneGlyph.Repeat
    PlayMode.Shuffle -> PhoneGlyph.Shuffle
    PlayMode.SingleRepeat -> PhoneGlyph.RepeatOne
    PlayMode.Sequence -> PhoneGlyph.Sequence
}

/**
 * 失败原因的中文说法。
 *
 * `PlaybackFailure.displayName` 是 media3 的枚举名（`ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`
 * 这种），直接摆给用户看等于什么都没说。只翻译走查里真出现过的几类，
 * 其余照旧显示原名——总比硬编一句「播放失败」把所有情况抹平要好排查。
 */
private fun PlaybackFailure.userHint(): String = when (code) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    -> "网络连接不上音源"

    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "音源地址已失效"

    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "音源文件不存在"

    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    -> "这个格式播不了"

    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "解码器起不来"

    else -> displayName
}

/** 投播给渲染器的 MIME：库里存的是 FLAC / MP3 这种短名，电视只认标准 MIME。 */
private fun castMimeOf(audioFormat: String?): String = when (audioFormat?.trim()?.lowercase()) {
    "flac" -> "audio/flac"
    "wav", "wave" -> "audio/wav"
    "aac" -> "audio/aac"
    "m4a", "mp4", "alac" -> "audio/mp4"
    "ogg", "opus" -> "audio/ogg"
    "wma" -> "audio/x-ms-wma"
    "ape" -> "audio/x-ape"
    "dsf", "dff" -> "audio/x-dsd"
    else -> "audio/mpeg"
}
