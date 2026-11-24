package com.yueyin.tv.core.playback

import com.yueyin.tv.core.model.TrackOrigin

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.yueyin.tv.core.data.api.TrimMusicApi
import com.yueyin.tv.core.model.AppError
import com.yueyin.tv.core.model.AppException
import com.yueyin.tv.core.model.PlaybackCredentials
import com.yueyin.tv.core.model.PlaybackTrack
import com.yueyin.tv.core.model.RoamWindow
import com.yueyin.tv.core.model.Track
import com.yueyin.tv.core.model.TrackGuid
import com.yueyin.tv.core.model.playback.NowPlayingIdentity
import com.yueyin.tv.core.model.playback.PlayMode
import com.yueyin.tv.core.model.playback.PlaybackQueueItem
import com.yueyin.tv.core.model.playback.QueueKind
import com.yueyin.tv.core.model.playback.QueuePageItem
import com.yueyin.tv.core.model.playback.QueuePageSegment
import com.yueyin.tv.core.model.playback.QueueSource
import com.yueyin.tv.core.model.playback.RepeatBehavior
import com.yueyin.tv.core.model.playback.SlidingQueueReducer
import com.yueyin.tv.core.model.playback.SlidingQueueState
import com.yueyin.tv.core.model.playback.mapping
import com.yueyin.tv.core.model.playback.next
import com.yueyin.tv.core.model.stream.StreamUrlCache
import java.util.ArrayList
import java.util.Random
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class QueueRemovalPlan(
    val removeIndex: Int,
    val remainingCount: Int,
    val nextCurrentIndex: Int?,
)

internal fun queueRemovalPlan(
    itemCount: Int,
    currentIndex: Int,
    removeIndex: Int,
): QueueRemovalPlan? {
    if (itemCount <= 0 || removeIndex !in 0 until itemCount) return null
    val remainingCount = itemCount - 1
    val normalizedCurrent = currentIndex.takeIf { it in 0 until itemCount } ?: 0
    val nextCurrentIndex = when {
        remainingCount == 0 -> null
        removeIndex < normalizedCurrent -> normalizedCurrent - 1
        removeIndex == normalizedCurrent -> normalizedCurrent.coerceAtMost(remainingCount - 1)
        else -> normalizedCurrent
    }
    return QueueRemovalPlan(removeIndex, remainingCount, nextCurrentIndex)
}

class PlaybackController(
    private val context: Context,
    private val sessionStore: PlaybackSessionStore,
    private val contentSource: PlaybackContentSource,
) {
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()
    private val _progress = MutableStateFlow(PlaybackProgressState())
    val progress: StateFlow<PlaybackProgressState> = _progress.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val snapshotCommitTracker = PlaybackSnapshotCommitTracker(scope)
    val snapshotCommitState: StateFlow<PlaybackSnapshotCommitState> = snapshotCommitTracker.state
    private val snapshotWriter = PlaybackSnapshotWriter(
        scope = scope,
        writeSnapshot = sessionStore::save,
    )
    private val snapshotPersistence = PlaybackSnapshotPersistence(scope, snapshotWriter)
    private var controller: MediaController? = null
    private var ticker: Job? = null
    private var queuePageJob: Job? = null
    private var roamJob: Job? = null
    private var pendingCredentials: PlaybackCredentials? = null
    private var currentNamespace: String? = null
    private var restored = false
    private var generation = 0L
    private var snapshotRevision = 0L

    /** 起播分段计时：只记最后一次提交，STATE_READY 时打出提交→出声耗时。 */
    private var startRequestAt = 0L
    private var startRequestId: String? = null
    private var presentationRevision = 0L
    private var lastPresentationKey: PresentationKey? = null
    private var lastSnapshotAt = 0L
    private var queueKind = QueueKind.Normal
    private var playMode = PlayMode.ListRepeat
    private var shuffleOrderIds: List<String> = emptyList()
    private var pendingShuffle: PendingShuffleActivation? = null
    private var frozenQueueSnapshot: PlaybackSnapshot? = null
    private var queueSource: QueueSource? = null
    private var queueWindow: SlidingQueueState? = null
    private var failedDirection: QueueDirection? = null
    private var queueError: String? = null
    private var roamWindow: RoamWindow? = null
    private var currentRoamId: String? = null
    private var roamError: AppError? = null
    private var failedRoamDirection: RoamDirection? = null
    private val queueProjector = PlaybackQueueProjector(MAX_QUEUE_ITEMS)
    private var forceNextPresentationProjection = false
    private val autoAdvanceGate = RoamAutoAdvanceGate()

    /** 自动跳过坏链接用：连续失败计数 + 上一次失败的那首（防止同一个 item 反复触发）。 */
    private var consecutivePlaybackFailures = 0
    private var lastFailedMediaId: String? = null

    /** 最近一次提交的队列快照：播放失败要重试时，按 mediaId 找回 Track 重新取链。 */
    private var submittedTracks: List<PlaybackTrack> = emptyList()

    /**
     * 当前首还没拿到直链时的「等链开播」状态。
     *
     * 在线音源取链有失败率，[mediaItem] 会把空串塞进 `setUri`。ExoPlayer 拿到空 URI 会
     * 直接抛 `MalformedURLException: no protocol`，再被自动跳过逻辑带成一串连环失败。
     * 所以空链时先停在未 prepare 的状态，记下 id，由 [patchStreamUrl] 补到链后统一启动。
     */
    private var pendingStreamId: String? = null
    private var pendingStreamAutoPlay = false
    private var streamResolveJob: Job? = null

    /**
     * 用户点了「下一首 / 上一首 / 队列里某一首」，但目标曲目还没直链。
     *
     * 以前是直接切过去：ExoPlayer 拿空 uri 当文件路径，立刻报「音源文件不存在」；
     * 用户看到的就是「一按下一首就失败」。现在改成**先取链、取到再切**：
     * 这里记住目标下标，[patchStreamUrl] 拿到链后完成切换，
     * 取链期间界面保持当前这首继续播，不闪错。
     */
    private var pendingSwitchIndex: Int? = null
    private var pendingSwitchAutoPlay = false

    /**
     * 提前给「下一首」取链的任务。
     *
     * 注意它和当前首的重取共用同一个隐藏 WebView（串行），必须显式互斥：
     * 记下预取目标 id，当前首重取时若是同一首就取消预取，避免两条取链互相踩。
     */
    private var nextPrefetchJob: Job? = null
    private var nextPrefetchId: String? = null

    /**
     * 预取失败的冷却，防「秒失败 → 立刻重试 → 再秒失败」的空转。
     *
     * [prefetchNextIfNeeded] 由 ticker 每 250ms 调一次。某些曲目取链会**立刻**失败
     * （脚本站点没收录、参数不对），失败后 `nextPrefetchJob` 变回 null，
     * 250ms 后又被拉起，于是每 250ms 打一次日志、每 250ms 占一次唯一的 WebView，
     * 把当前首的重取和用户切歌都挤在后面（实测 6 次/1.3 秒）。
     * 这里记下每个失败目标与时刻，冷却期内不再重试；但**只挡这一首**——
     * 预取会继续往后找别的候选，坏曲目不能挡住整条预取管线。
     * 等用户真的切过去时由 [requestStreamUrl] 正常重取（那条路径不受此限制）。
     */
    private val prefetchFailures = LinkedHashMap<String, Long>()

    /** B（取链测速）：按源滑动窗口统计取链耗时与失败率，用于诊断与预取调度。 */
    private val sourceStats = StreamSourceStats()

    /**
     * 当前首是否因直链失效进入了 ERROR、正在等补链自愈。
     *
     * ExoPlayer 进入 ERROR 后不会自己恢复：即使补到新链并 replaceMediaItem，
     * 也必须主动 prepare + play 才会重新出声。此前只有 pendingStreamId 命中才做这件事，
     * 而快速切歌时结构转换会清掉 pendingStreamId，于是链补到了却停在 ERROR——
     * 用户点一下播放才恢复。这个标记独立于 pending，保证自愈不被结构转换弄丢。
     */
    private var awaitingRecoveryId: String? = null

    /**
     * 已经判定「这首真的取不到链」的曲目，别再为它发起取链。
     *
     * 取链失败后播放器还会补一次空 URI 的 ERROR 回调（切歌守卫正在取下一首，队列项还没换），
     * 那个回调会再调一次 [requestStreamUrl]，把刚给下一首起的取链**掐掉**重来——
     * 实测因此多花了 2 秒并让下一首的取链白跑一轮。记下 id 直接挡住。
     */
    private var abandonedStreamId: String? = null

    /** 一次卡顿里已经自动跳过了几首（防止超时后被一路带到队列另一头）。 */
    private var targetSkipStreak = 0

    /**
     * 上一轮连跳最后停下的位置（跳过游标）。
     *
     * 用户连按「下一首」时，守卫算出的目标永远是「当前播放项 + 1」——可那个位置已经确认
     * 取不到链，而且自动跳过失败时播放位置**根本没变**。于是不记住游标的话，每按一次都
     * 重新从同一首开始试，连跳上限一用满就彻底不动了（实测连点 10 次全停在原曲）。
     * 记住最后一次跳到的位置，下一次按键从它后面继续，才能一路推进到能播的那首。
     * 真正切换成功后清空；换队列时也清空。
     */
    private var skipFrontier: Int? = null

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            forceNextPresentationProjection = forceNextPresentationProjection ||
                shouldForcePresentationProjection(mediaItem != null, reason)
            // 已经换到别的曲目：上一首的「放弃取链」记录只对那一首有效，别带过来。
            val transitionedId = mediaItem?.mediaId
            if (!transitionedId.isNullOrBlank() && transitionedId != abandonedStreamId) {
                abandonedStreamId = null
            }
            // 自动续播（本曲放完）落到一首还没链的歌上：播放器自己不会等，
            // 会立刻拿空 uri 去加载并报错。这里抢先发起取链，报错由「等链自愈」接住，
            // 界面在取链期间也不显示错误（见 project）。
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                val id = mediaItem?.mediaId
                val uri = mediaItem?.localConfiguration?.uri?.toString().orEmpty()
                if (!id.isNullOrBlank() && uri.isBlank()) {
                    Log.i("YueyinPlayback", "自动续播到空链曲目 id=$id，立即取链")
                    pendingStreamId = id
                    pendingStreamAutoPlay = true
                    awaitingRecoveryId = id
                    requestStreamUrl(id)
                }
            }
            controller?.let { player ->
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    autoAdvanceGate.reset()
                    structuralSnapshot(player)
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val player = controller ?: return
            if (playbackState == Player.STATE_READY) {
                val id = player.currentMediaItem?.mediaId
                if (id != null && id == startRequestId && startRequestAt > 0L) {
                    Log.d(
                        "YueyinStart",
                        "submitted->ready=${SystemClock.elapsedRealtime() - startRequestAt}ms id=$id",
                    )
                    startRequestAt = 0L
                    startRequestId = null
                }
                consecutivePlaybackFailures = 0
                // 这一首已经正常出声了：清掉「已自动重取过」的标记，
                // 否则它下次（比如拖进度、循环回来）再失败就不会自愈了。
                lastFailedMediaId = null
            }
            if (playbackState == Player.STATE_ENDED && queueKind == QueueKind.Roam) {
                if (autoAdvanceGate.tryConsume(generation, player.currentMediaItem?.mediaId)) {
                    advanceRoam(RoamDirection.Next)
                }
            }
        }

        /**
         * 音源给的直链是有时效的，也经常出现某首歌的 CDN 就是连不上。
         * 以前这种情况会一直停在「播放失败：ERROR_CODE_IO_NETWORK_CONNECTION_FAILED」，
         * 用户得手动点下一首。这里改成自动跳过：
         * 连续失败 2 首以内直接跳下一首，到第 3 首才把错误留在界面上（说明是真断网了）。
         *
         * 但「空 URI」是另一回事：那是当前首还没补到链，不是音源连不上。
         * 自动跳过去只会撞上队列里下一首同样空的占位项，变成一首接一首的连环报错。
         */
        override fun onPlayerError(error: PlaybackException) {
            val player = controller ?: return
            val failingId = player.currentMediaItem?.mediaId
            val failedUri = player.currentMediaItem?.localConfiguration?.uri
            val uriText = failedUri?.toString().orEmpty()
            Log.w(
                "YueyinPlayback",
                "onPlayerError id=$failingId code=${error.errorCode} name=${error.errorCodeName} " +
                    "host=${failedUri?.host} scheme=${failedUri?.scheme} " +
                    "cause=${error.cause?.javaClass?.simpleName}",
            )
            // 音源直链带签名/时效：失败即作废缓存，否则换音源、点重试都会命中同一条死链。
            if (!failingId.isNullOrBlank()) StreamUrlCache.invalidate(failingId)
            if (failedUri == null || uriText.isBlank() || failedUri.scheme.isNullOrBlank()) {
                Log.w("YueyinPlayback", "onPlayerError id=$failingId 空 URI，等待重新取链")
                if (!failingId.isNullOrBlank()) {
                    // 这首已经判定取不到链（刚跳过它），这次 ERROR 是切歌过程中的迟到回调：
                    // 再发起取链会把给下一首起的任务掐掉，纯属白跑。
                    if (failingId == abandonedStreamId) {
                        Log.w("YueyinPlayback", "onPlayerError id=$failingId 已放弃这首，不再重取直链")
                        return
                    }
                    // 起播时已经发起过一次重取的就不再重复发起，只把「补到链要开播」标记补齐。
                    val alreadyResolving = pendingStreamId == failingId && streamResolveJob?.isActive == true
                    pendingStreamId = failingId
                    pendingStreamAutoPlay = true
                    if (!alreadyResolving) requestStreamUrl(failingId)
                }
                return
            }
            if (failingId == null) return
            // 直链过期是最常见的一类失败：脚本给的链只活几分钟，队列里那批是预取时一次性取的。
            // 以前这里直接跳下一首，而下一首的 uri 是同一批老链，于是「下一曲也失败、点重试却能播」。
            // 现在先替用户自动点一次「重试」：作废这条死链、现场重取、原地续播。
            if (failingId != lastFailedMediaId) {
                lastFailedMediaId = failingId
                Log.i("YueyinPlayback", "onPlayerError id=$failingId 直链可能过期，自动重取一次")
                awaitingRecoveryId = failingId
                pendingStreamId = failingId
                pendingStreamAutoPlay = true
                requestStreamUrl(failingId)
                return
            }
            // 已经为重取过一次仍然失败：这首歌确实播不了，退回「换下一首」的老策略。
            autoSkipAfterFailure(failingId)
        }

        override fun onEvents(player: Player, events: Player.Events) {
            val forcePresentation = forceNextPresentationProjection
            forceNextPresentationProjection = false
            project(
                player = player,
                forcePresentation = forcePresentation,
                rebuildQueue = shouldRebuildPlaybackQueue(
                    timelineChanged = events.contains(Player.EVENT_TIMELINE_CHANGED),
                    mediaItemTransition = events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION),
                    mediaMetadataChanged = events.contains(Player.EVENT_MEDIA_METADATA_CHANGED),
                ),
            )
        }
    }

    fun connect() {
        PlaybackTransportBridge.register { direction ->
            scope.launch {
                when (direction) {
                    PlaybackTransportDirection.Previous -> advanceRoam(RoamDirection.Previous)
                    PlaybackTransportDirection.Next -> advanceRoam(RoamDirection.Next)
                }
            }
        }
        PlaybackTransportBridge.setOwnership(PlaybackTransportOwnership.Restoring)
        // 媒体键 / 车机 / 通知栏的切歌直接打到播放内核，不经过本类的方法。
        // 统一在这里设一道「空直链守卫」：目标没链就不放行，由本类取链后再切。
        PlaybackTransportBridge.skipGuard = { targetIndex -> canSwitchTo(targetIndex) }
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                runCatching { future.get() }.onSuccess { mediaController ->
                    controller = mediaController
                    mediaController.addListener(listener)
                    pendingCredentials?.let(::configure)
                    project(mediaController)
                    startTicker()
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun configure(credentials: PlaybackCredentials) {
        pendingCredentials = credentials
        Log.i(
            "YueyinPlayback",
            "configure namespace=${credentials.cacheNamespace} current=$currentNamespace restored=$restored count=${controller?.mediaItemCount ?: -1}",
        )
        val binding = playbackNamespaceBinding(currentNamespace, credentials.cacheNamespace)
        if (binding != PlaybackNamespaceBinding.Same) {
            beginStructuralTransition()
            if (binding == PlaybackNamespaceBinding.Rebind) resetActiveSessionForRebind(controller)
            restored = false
            currentNamespace = credentials.cacheNamespace
            snapshotCommitTracker.reset(credentials.cacheNamespace)
            PlaybackTransportBridge.setOwnership(PlaybackTransportOwnership.Restoring)
        }
        val player = controller ?: return
        val configureGeneration = generation
        // 本地会话 / 协议平台（在线音源）没有 token：协议给的是绝对地址，不需要 Authorization 头。
        // 以前这种情况仍会发 ConfigureAuth，服务端按「参数非法」返回失败，于是整段恢复被跳过——
        // 结果就是队列快照从来不恢复、也从来不保存（currentNamespace 没被赋值就不写快照），
        // 用户看到的就是「退出再进等于从头开始 / 播过的歌播不了」。
        if (credentials.rawAuthorization.isBlank()) {
            Log.i("YueyinPlayback", "configure: 无 token（本地/协议会话），跳过鉴权直接恢复队列")
            TrimMusicApi.applyPlaybackTls(false, null, null)
            onAuthConfigured(player, credentials, configureGeneration, configured = true)
            return
        }
        val args = Bundle().apply {
            putString(PlaybackCommands.Token, credentials.rawAuthorization)
            putString(PlaybackCommands.CacheNamespace, credentials.cacheNamespace)
            putString(PlaybackCommands.ApiBase, credentials.apiBase)
            putString(PlaybackCommands.AccessCode, credentials.accessCodeHeader)
            putBoolean(PlaybackCommands.RelayMode, credentials.relayMode)
            putBoolean(PlaybackCommands.AllowSelfSigned, credentials.allowSelfSigned)
            putString(PlaybackCommands.CertFingerprint, credentials.certFingerprint)
        }
        val future = player.sendCustomCommand(PlaybackCommands.ConfigureAuthCommand, args)
        future.addListener(
            {
                val configured = runCatching { future.get().resultCode == SessionResult.RESULT_SUCCESS }
                    .getOrDefault(false)
                onAuthConfigured(player, credentials, configureGeneration, configured)
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    /** 鉴权（或免鉴权）就绪后的统一入口：队列已经在就直接投影，没有就按快照恢复。 */
    private fun onAuthConfigured(
        player: MediaController,
        credentials: PlaybackCredentials,
        configureGeneration: Long,
        configured: Boolean,
    ) {
        if (!configured || currentNamespace != credentials.cacheNamespace) {
            Log.w("YueyinPlayback", "configure: 鉴权未就绪 configured=$configured ns=$currentNamespace")
            return
        }
        if (hasConfiguredQueue(restored, player.mediaItemCount)) {
            restored = true
            PlaybackTransportBridge.setOwnership(ownershipFor(queueKind))
            project(player)
            return
        }
        scope.launch {
            val account = sessionStore.read(credentials.cacheNamespace)
            Log.i(
                "YueyinPlayback",
                "restore 尝试 namespace=${credentials.cacheNamespace} " +
                    "queueJson=${account?.queueJson?.length ?: -1} 现有队列=${player.mediaItemCount}",
            )
            if (
                generation != configureGeneration ||
                currentNamespace != credentials.cacheNamespace ||
                player.mediaItemCount > 0
            ) {
                Log.i("YueyinPlayback", "restore 跳过：会话已变化或队列非空")
                return@launch
            }
            restored = true
            restoreQueue(player, account?.queueJson, account?.frozenQueueJson)
            PlaybackTransportBridge.setOwnership(ownershipFor(queueKind))
            project(player)
        }
    }

    fun playQueue(
        tracks: List<PlaybackTrack>,
        startIndex: Int = 0,
        autoPlay: Boolean = true,
        source: QueueSource? = null,
        windowStart: Int = 0,
        firstPage: Int = 1,
        lastPage: Int = firstPage + ((tracks.size - 1).coerceAtLeast(0) / PAGE_SIZE),
        knownTotal: Int? = null,
        segments: List<QueuePageSegment>? = null,
    ): PlaybackTransition? {
        val player = controller ?: return null
        if (tracks.isEmpty()) return null
        submittedTracks = tracks
        beginStructuralTransition()
        val selected = startIndex.coerceIn(tracks.indices)
        val activeIds = tracks.map { track -> track.track.guid.value }
        val previousShuffleOrder = shuffleOrderIds
        val restoreShuffle = playMode == PlayMode.Shuffle
        if (restoreShuffle) {
            playMode = PlayMode.ListRepeat
            shuffleOrderIds = emptyList()
        }
        queueKind = QueueKind.Normal
        roamWindow = null
        currentRoamId = null
        roamError = null
        failedRoamDirection = null
        frozenQueueSnapshot = null
        queueSource = source
        queueWindow = source?.let { queueSource ->
            segments?.takeIf(List<QueuePageSegment>::isNotEmpty)?.let { pages ->
                SlidingQueueState.fromSegments(pages, selected).also { window ->
                    require(window.guids == activeIds) { "Queue segments must exactly match playable tracks" }
                    require(window.sort == queueSource.sort) { "Queue segments must use the source sort order" }
                    require(window.windowStart == windowStart) { "Queue window start does not match its segments" }
                    require(window.firstPage == firstPage && window.lastPage == lastPage) {
                        "Queue page bounds do not match their segments"
                    }
                    require(window.knownTotal == knownTotal) { "Queue total does not match its segments" }
                }
            }
        }
        failedDirection = null
        queueError = null
        val selectedReady = tracks[selected].streamUrl.isNotBlank()
        if (selectedReady && autoPlay) {
            startRequestAt = SystemClock.elapsedRealtime()
            startRequestId = tracks[selected].track.guid.value
        }
        // 空 URI 绝不能进加载路径：ExoPlayer 会把 "" 当 URL 去发请求，
        // 抛 MalformedURLException: no protocol。
        // 只 pause 不够：播放器已经处于 prepared 状态（例如启动时恢复了上次队列）时，
        // setMediaItems 仍会立刻加载当前项。先 clearMediaItems 强制回到 IDLE 再装新队列，
        // 此时不 prepare 就不会去碰空 URI；等补链成功后由 patchStreamUrl 统一启动。
        if (!selectedReady) {
            player.pause()
            player.clearMediaItems()
        }
        player.setMediaItems(tracks.map(::mediaItem), selected, 0L)
        applyModeLocally(player, playMode)
        pendingStreamId = if (selectedReady) null else tracks[selected].track.guid.value
        pendingStreamAutoPlay = !selectedReady && autoPlay
        if (selectedReady) {
            player.prepare()
            if (autoPlay) player.playIfCastAllows() else player.pause()
        } else {
            // 当前首取链失败：立刻就地重取（早于后台补链那几十首，避免被串行取链排队堵住），
            // 拿到链后由 patchStreamUrl 统一 prepare/play。
            requestStreamUrl(tracks[selected].track.guid.value)
        }
        PlaybackTransportBridge.setOwnership(PlaybackTransportOwnership.Normal)
        project(player)
        val transition = structuralSnapshot(player)
        if (restoreShuffle) {
            val retained = previousShuffleOrder.filter(activeIds::contains)
            val added = activeIds.filterNot(retained::contains)
            requestShuffleActivation(
                player = player,
                order = retained + stableShuffle(added, snapshotRevision + 1L),
                activationRevision = snapshotRevision + 1L,
                fallbackMode = PlayMode.ListRepeat,
                persistOnAccept = true,
                persistFallbackOnReject = false,
            )
        }
        return transition
    }

    suspend fun startRoam(): Boolean {
        if (queueKind != QueueKind.Normal || roamJob?.isActive == true || _state.value.roamBusy) return false
        val player = controller ?: return false
        val expectedGeneration = generation
        val previousQueueError = queueError
        val previousFailedDirection = failedDirection
        updateRoamStatus(busy = true, error = null)
        val request = scope.async(start = CoroutineStart.LAZY) {
            var rollbackSnapshot: PlaybackSnapshot? = null
            var installedGeneration: Long? = null
            var installedRoamId: String? = null
            try {
                val initial = contentSource.startRoam() ?: throw AppException(AppError.Empty)
                val resolved = resolveRoam(initial, RoamDirection.Next, expectedGeneration, currentCursor = null)
                if (generation != expectedGeneration) throw CancellationException("Playback session changed")
                rollbackSnapshot = if (player.mediaItemCount > 0) {
                    captureSnapshot(player, revision = snapshotRevision, includeFrozen = false)
                } else {
                    null
                }
                frozenQueueSnapshot = rollbackSnapshot
                beginStructuralTransition(cancelRoam = false)
                installedGeneration = generation
                installedRoamId = resolved.window.current.roamId
                queueKind = QueueKind.Roam
                queueSource = null
                queueWindow = null
                roamWindow = resolved.window
                currentRoamId = installedRoamId
                failedDirection = null
                queueError = null
                installRoamTrack(player, resolved.track, autoPlay = true)
                PlaybackTransportBridge.setOwnership(PlaybackTransportOwnership.Roam)
                structuralSnapshot(player)?.awaitCommitted()
                updateRoamStatus(busy = false, error = null)
                true
            } catch (cause: CancellationException) {
                if (
                    installedGeneration == generation &&
                    queueKind == QueueKind.Roam &&
                    currentRoamId == installedRoamId
                ) {
                    restoreAfterFailedRoamMutation(player, rollbackSnapshot)
                    failedDirection = previousFailedDirection
                    queueError = previousQueueError
                }
                throw cause
            } catch (cause: Throwable) {
                if (
                    installedGeneration == generation &&
                    queueKind == QueueKind.Roam &&
                    currentRoamId == installedRoamId
                ) {
                    restoreAfterFailedRoamMutation(player, rollbackSnapshot)
                    failedDirection = previousFailedDirection
                    queueError = previousQueueError
                }
                updateRoamStatus(busy = false, error = cause.appError())
                false
            } finally {
                val running = currentCoroutineContext()[Job]
                if (roamJob === running) {
                    roamJob = null
                    updateRoamStatus(busy = false, error = roamError)
                }
                controller?.let(::project)
            }
        }
        roamJob = request
        request.start()
        return try {
            request.await()
        } catch (cause: CancellationException) {
            request.cancel(cause)
            throw cause
        }
    }

    fun enterRoam(track: PlaybackTrack): PlaybackTransition? {
        val player = controller ?: return null
        if (queueKind == QueueKind.Normal && player.mediaItemCount > 0) {
            frozenQueueSnapshot = captureSnapshot(player, revision = snapshotRevision, includeFrozen = false)
        }
        beginStructuralTransition()
        queueKind = QueueKind.Roam
        queueSource = null
        queueWindow = null
        currentRoamId = null
        installRoamTrack(player, track, autoPlay = true)
        PlaybackTransportBridge.setOwnership(PlaybackTransportOwnership.Roam)
        return structuralSnapshot(player)
    }

    fun replaceRoamTrack(track: PlaybackTrack): PlaybackTransition? {
        val player = controller ?: return null
        installRoamTrack(player, track, autoPlay = true)
        return structuralSnapshot(player)
    }

    fun exitRoam(): Boolean = exitRoamTransition().restoredQueue

    suspend fun exitRoamDurably(): Boolean {
        val exit = exitRoamTransition()
        exit.transition?.awaitCommitted()
        return exit.restoredQueue
    }

    private fun exitRoamTransition(): RoamExitTransition {
        val player = controller ?: return RoamExitTransition(restoredQueue = false, transition = null)
        beginStructuralTransition()
        val snapshot = frozenQueueSnapshot?.takeIf { it.items.isNotEmpty() }
        queueKind = QueueKind.Normal
        roamWindow = null
        currentRoamId = null
        roamError = null
        failedRoamDirection = null
        frozenQueueSnapshot = null
        if (snapshot == null) {
            player.stop()
            player.clearMediaItems()
            queueSource = null
            queueWindow = null
            PlaybackTransportBridge.setOwnership(PlaybackTransportOwnership.Normal)
            project(player)
            return RoamExitTransition(
                restoredQueue = false,
                transition = structuralSnapshot(player),
            )
        }
        val shuffleOrderToRestore = applySnapshot(player, snapshot, activatePersistedShuffle = false)
        player.pause()
        PlaybackTransportBridge.setOwnership(PlaybackTransportOwnership.Normal)
        project(player)
        val transition = structuralSnapshot(player)
        shuffleOrderToRestore?.let { order ->
            requestShuffleActivation(
                player = player,
                order = order,
                activationRevision = snapshotRevision + 1L,
                fallbackMode = PlayMode.ListRepeat,
                persistOnAccept = true,
                persistFallbackOnReject = false,
            )
        }
        return RoamExitTransition(
            restoredQueue = true,
            transition = transition,
        )
    }

    fun retryRoam() {
        failedRoamDirection?.let(::advanceRoam)
    }

    /**
     * 投播用：当前曲目 + 播放器里**真正的** URI + 当前位置。
     *
     * URI 以播放器里的 MediaItem 为准而不是 submittedTracks：在线曲目起播前是空串，
     * 补链成功后由 patchStreamUrl 替换播放器项，submittedTracks 里仍是旧值。
     * 返回值可能是 content://、127.0.0.1 代理地址或在线直链，投播层会统一中转。
     */
    fun currentCastSource(): Triple<Track, String, Long>? {
        val player = controller ?: return null
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        val track = submittedTracks.getOrNull(index)?.track ?: return null
        val uri = player.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
        return Triple(track, uri, player.currentPosition.coerceAtLeast(0L))
    }

    /**
     * 暂停（幂等）：正在播就暂停，本来就没播就什么都不做。
     *
     * 与 [playPause] 的区别是不做「播完重头播 / 空闲时起播」的兜底——定时停止播放要的是
     * 「停在那儿」，不能因为刚好播完就又从上一首开始。
     */
    fun pause() {
        controller?.let { player -> if (player.isPlaying) player.pause() }
    }

    /**
     * 投播中抑制本机起播。
     *
     * 投播时手机只是遥控器：从列表选歌会让播放器走「准备并播放」，它的 play() 往往
     * 晚于 UI 那次 pause 才执行，于是手机和音响同时出声。这里在播放器层直接拦住，
     * 不依赖调用方的时序。
     */
    @Volatile
    private var castSuppressPlay = false

    private fun Player.playIfCastAllows() {
        if (!castSuppressPlay) play()
    }

    /** 投播接管前：停下本机，并禁止后续任何本机起播。 */
    fun pauseForCast() {
        castSuppressPlay = true
        pause()
    }

    /** 退出投播：恢复本机起播能力。 */
    fun releaseCastSuppression() {
        castSuppressPlay = false
    }

    fun playPause() {
        controller?.let { player ->
            when {
                player.isPlaying -> player.pause()
                player.playbackState == Player.STATE_ENDED -> {
                    player.seekToDefaultPosition()
                    player.playIfCastAllows()
                }
                player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0 -> {
                    player.prepare()
                    player.playIfCastAllows()
                }
                else -> player.playIfCastAllows()
            }
        }
    }

    fun next() {
        if (queueKind == QueueKind.Roam) advanceRoam(RoamDirection.Next)
        else controller?.seekToNextMediaItem()
    }

    fun previous() {
        if (queueKind == QueueKind.Roam) {
            advanceRoam(RoamDirection.Previous)
            return
        }
        controller?.let { player ->
            if (player.currentPosition > PREVIOUS_RESTART_THRESHOLD_MS) player.seekTo(0L)
            else player.seekToPreviousMediaItem()
        }
    }

    /**
     * 强制切到上一首。
     *
     * 与 [previous] 不同，这里不做「播放超过 3 秒先回到本曲开头」的处理，
     * 用于遥控器连按 / 媒体键这类「我就是要上一首」的场景。
     */
    fun previousTrack() {
        if (queueKind == QueueKind.Roam) {
            advanceRoam(RoamDirection.Previous)
            return
        }
        controller?.seekToPreviousMediaItem()
    }

    /**
     * 当前曲目是否支持拖动/快进。
     *
     * 部分编码（如 DSD/APE 之类靠扩展解码器或服务端不返回 Accept-Ranges 的流）Media3 会判定为不可 seek，
     * 此时 seekTo 调用不会报错但位置不会变化——表现为"快进按了没反应"。用它给出明确提示。
     */
    val isCurrentSeekable: Boolean
        get() = controller?.isCurrentMediaItemSeekable ?: true

    fun seekBy(offsetMs: Long) = controller?.let { player ->
        val upperBound = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + offsetMs).coerceIn(0L, upperBound))
    }

    /**
     * 绝对定位。
     *
     * 手机播放页的进度条是「拖着走」的：拖动过程中界面显示手指位置、松手才真正 seek。
     * 如果松手时用 [seekBy] 传「目标 − 当时位置」，那是两个时间点的差值，播放器早已前进了一截，
     * 落点会偏。目标位置是用户亲眼选定的，必须按绝对值落。
     */
    fun seekTo(positionMs: Long) = controller?.let { player ->
        val upperBound = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo(positionMs.coerceIn(0L, upperBound))
    }

    fun selectQueueItem(queueIndex: Int): PlaybackTransition? {
        val player = controller ?: return null
        if (queueKind != QueueKind.Normal || queueIndex !in 0 until player.mediaItemCount) return null
        player.seekTo(queueIndex, 0L)
        player.playIfCastAllows()
        return structuralSnapshot(player)
    }

    fun removeQueueItem(queueIndex: Int): PlaybackTransition? {
        val player = controller ?: return null
        if (queueKind != QueueKind.Normal) return null
        val removal = queueRemovalPlan(
            itemCount = player.mediaItemCount,
            currentIndex = player.currentMediaItemIndex,
            removeIndex = queueIndex,
        ) ?: return null
        beginStructuralTransition()
        queueSource = null
        queueWindow = null
        failedDirection = null
        queueError = null
        val restoreShuffle = playMode == PlayMode.Shuffle
        player.removeMediaItem(queueIndex)
        queueProjector.clear()
        project(player)
        if (restoreShuffle && player.mediaItemCount > 0) {
            return reapplyShuffleAfterQueueMutation(player)
        }
        if (removal.nextCurrentIndex == null) {
            player.stop()
            playMode = PlayMode.ListRepeat
            shuffleOrderIds = emptyList()
            applyModeLocally(player, playMode)
            project(player)
        }
        return structuralSnapshot(player)
    }

    fun cyclePlayMode() = setPlayMode(playMode.next())

    fun setPlayMode(mode: PlayMode) {
        val player = controller ?: return
        if (queueKind != QueueKind.Normal || mode == playMode || pendingShuffle != null) return
        if (mode != PlayMode.Shuffle) {
            playMode = mode
            shuffleOrderIds = emptyList()
            applyModeLocally(player, mode)
            project(player)
            structuralSnapshot(player)
            return
        }
        val ids = List(player.mediaItemCount) { index -> player.getMediaItemAt(index).mediaId }
        if (ids.isEmpty() || ids.distinct().size != ids.size) return
        val proposedRevision = snapshotRevision + 1L
        requestShuffleActivation(
            player = player,
            order = stableShuffle(ids, proposedRevision),
            activationRevision = proposedRevision,
            fallbackMode = playMode,
            persistOnAccept = true,
            persistFallbackOnReject = false,
        )
    }

    private fun requestShuffleActivation(
        player: MediaController,
        order: List<String>,
        activationRevision: Long,
        fallbackMode: PlayMode,
        persistOnAccept: Boolean,
        persistFallbackOnReject: Boolean,
    ) {
        val canonical = List(player.mediaItemCount) { index -> player.getMediaItemAt(index).mediaId }
        if (canonical.isEmpty() || canonical.distinct().size != canonical.size || canonical.toSet() != order.toSet()) {
            fallBackFromShuffle(player, fallbackMode, persistFallbackOnReject)
            return
        }
        val request = PendingShuffleActivation(
            generation = generation,
            baseRevision = snapshotRevision,
            activationRevision = activationRevision,
            canonicalIds = canonical,
            orderIds = order,
            fallbackMode = fallbackMode,
            persistOnAccept = persistOnAccept,
            persistFallbackOnReject = persistFallbackOnReject,
        )
        pendingShuffle = request
        val args = Bundle().apply {
            putStringArrayList(PlaybackCommands.MediaIds, ArrayList(order))
            putLong(PlaybackCommands.SnapshotRevision, activationRevision)
        }
        val future = player.sendCustomCommand(PlaybackCommands.SetShuffleOrderCommand, args)
        future.addListener(
            {
                if (pendingShuffle !== request) return@addListener
                if (generation != request.generation || queueKind != QueueKind.Normal) {
                    pendingShuffle = null
                    return@addListener
                }
                val result = runCatching { future.get() }.getOrNull()
                val acknowledgement = result
                    ?.takeIf { it.resultCode == SessionResult.RESULT_SUCCESS }
                    ?.let {
                        ShuffleAcknowledgement(
                            revision = it.extras.getLong(PlaybackCommands.SnapshotRevision, -1L),
                            orderIds = it.extras.getStringArrayList(PlaybackCommands.MediaIds).orEmpty(),
                        )
                    }
                val accepted = acknowledgement != null && request.accepts(
                    acknowledgement = acknowledgement,
                    currentGeneration = generation,
                    currentRevision = snapshotRevision,
                    currentCanonicalIds = List(player.mediaItemCount) { index -> player.getMediaItemAt(index).mediaId },
                )
                if (!accepted) {
                    pendingShuffle = null
                    fallBackFromShuffle(player, request.fallbackMode, request.persistFallbackOnReject)
                    return@addListener
                }
                pendingShuffle = null
                playMode = PlayMode.Shuffle
                shuffleOrderIds = request.orderIds
                applyModeLocally(player, PlayMode.Shuffle)
                project(player)
                if (request.persistOnAccept) {
                    check(structuralSnapshot(player)?.revision == request.activationRevision) {
                        "Shuffle activation revision changed before persistence"
                    }
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun fallBackFromShuffle(player: MediaController, mode: PlayMode, persist: Boolean) {
        playMode = mode.takeUnless { it == PlayMode.Shuffle } ?: PlayMode.ListRepeat
        shuffleOrderIds = emptyList()
        applyModeLocally(player, playMode)
        project(player)
        if (persist) structuralSnapshot(player)
    }

    fun enrichCurrentItem(track: Track) {
        val player = controller ?: return
        val index = player.currentMediaItemIndex
        val current = player.currentMediaItem ?: return
        if (index !in 0 until player.mediaItemCount || current.mediaId != track.guid.value) return
        val old = current.mediaMetadata
        val coverId = track.coverId ?: old.extras?.getString(COVER_ID_KEY)
        val durationMs = track.durationMs ?: old.extras
            ?.takeIf { it.containsKey(DECLARED_DURATION_MS_KEY) }
            ?.getLong(DECLARED_DURATION_MS_KEY)
        val updatedMetadata = old.buildUpon()
            .setTitle(track.title.ifBlank { old.title })
            .setArtist(track.artistName ?: old.artist)
            .setAlbumTitle(track.albumName ?: old.albumTitle)
            .setExtras(mediaExtras(track.audioFormat ?: old.extras?.getString(AUDIO_FORMAT_KEY), coverId, durationMs))
            .build()
        val oldKey = presentationKey(current)
        val updated = current.buildUpon().setMediaMetadata(updatedMetadata).build()
        if (oldKey == presentationKey(updated)) return
        player.replaceMediaItem(index, updated)
        project(player)
        structuralSnapshot(player)
    }

    fun retryQueuePage() {
        val direction = failedDirection ?: return
        failedDirection = null
        queueError = null
        loadQueuePage(direction)
    }

    /** 歌曲磁盘缓存当前占用字节数（目录内全部文件大小之和）。 */
    fun songCacheUsageBytes(): Long {
        val root = File(context.filesDir, SONG_CACHE_DIRECTORY)
        if (!root.isDirectory) return 0L
        return root.walkTopDown().filter { it.isFile }.sumOf { runCatching { it.length() }.getOrDefault(0L) }
    }

    /**
     * 一键清空歌曲磁盘缓存。播放服务已连接时通过自定义命令逐条移除缓存资源（不影响当前播放）；
     * 服务未连接时直接清理缓存目录。
     */
    fun clearSongCache() {
        val active = controller
        if (active != null) {
            runCatching { active.sendCustomCommand(PlaybackCommands.ClearSongCacheCommand, Bundle.EMPTY) }
            return
        }
        val root = File(context.filesDir, SONG_CACHE_DIRECTORY)
        if (root.isDirectory) {
            runCatching { root.walkBottomUp().forEach { file -> file.delete() } }
        }
    }

    fun clearSession(): PlaybackTransition? {
        val namespace = currentNamespace
        beginStructuralTransition()
        controller?.run {
            stop()
            clearMediaItems()
            sendCustomCommand(PlaybackCommands.ClearAuthCommand, Bundle.EMPTY)
        }
        pendingCredentials = null
        restored = false
        currentNamespace = null
        frozenQueueSnapshot = null
        queueSource = null
        queueWindow = null
        queueKind = QueueKind.Normal
        playMode = PlayMode.ListRepeat
        shuffleOrderIds = emptyList()
        roamWindow = null
        currentRoamId = null
        failedDirection = null
        queueError = null
        roamError = null
        failedRoamDirection = null
        lastPresentationKey = null
        val transition = namespace?.let { value ->
            val clearRevision = ++snapshotRevision
            val acknowledgement = snapshotPersistence.submitStructural(
                PlaybackSnapshotWriteRequest(value, clearRevision, null),
            )
            snapshotCommitTracker.track(value, clearRevision, acknowledgement) {
                sessionStore.clear(value)
            }
        }
        PlaybackTransportBridge.setOwnership(PlaybackTransportOwnership.Restoring)
        queueProjector.clear()
        _state.value = PlaybackUiState(connected = controller != null)
        _progress.value = PlaybackProgressState()
        return transition
    }

    suspend fun clearSessionDurably() {
        clearSession()?.awaitCommitted()
    }

    /**
     * 切换数据源（换平台）时用：停播 + 清空队列 + 清掉持久化的队列快照，
     * 但**保留 [currentNamespace] 与 [pendingCredentials]**。
     *
     * 这里刻意不等于 [clearSession]：clearSession 会把 namespace 置空，
     * 而 namespace 只有 SessionRepository 再次下发凭据（[configure]）时才会重新赋值。
     * 换平台并不会触发新的 session 状态，于是 namespace 永久为 null ——
     * [structuralSnapshot] 直接返回 null，[playQueue] 跟着返回 null，
     * UI 就报「兼容播放参数尚未确认」，表现为"任何歌都播不了"。
     */
    fun stopAndClearQueue() {
        beginStructuralTransition()
        controller?.run {
            stop()
            clearMediaItems()
        }
        queueSource = null
        queueWindow = null
        queueKind = QueueKind.Normal
        frozenQueueSnapshot = null
        roamWindow = null
        currentRoamId = null
        failedDirection = null
        failedRoamDirection = null
        queueError = null
        roamError = null
        lastPresentationKey = null
        // 清掉已落盘的队列快照，避免下次启动把上一个平台的歌恢复出来
        currentNamespace?.let { namespace ->
            val revision = ++snapshotRevision
            val acknowledgement = snapshotPersistence.submitStructural(
                PlaybackSnapshotWriteRequest(namespace, revision, null),
            )
            snapshotCommitTracker.track(namespace, revision, acknowledgement) {
                sessionStore.clear(namespace)
            }
        }
        queueProjector.clear()
        _state.value = PlaybackUiState(connected = controller != null)
        _progress.value = PlaybackProgressState()
    }

    fun disconnect() {
        controller?.let { checkpointSnapshot(it, force = true) }
        beginStructuralTransition()
        releaseController()
    }

    suspend fun stopForAppExit() {
        beginStructuralTransition()
        val player = controller
        try {
            player?.pause()
            player?.let(::project)
            player?.let { structuralSnapshot(it, PlaybackPlayIntent.Pause) }?.awaitCommitted()
        } finally {
            try {
                player?.stop()
            } finally {
                releaseController()
            }
        }
    }

    private fun releaseController() {
        ticker?.cancel()
        ticker = null
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        PlaybackTransportBridge.unregister()
        queueProjector.clear()
        _state.value = PlaybackUiState()
        _progress.value = PlaybackProgressState()
    }

    private fun project(
        player: Player,
        forcePresentation: Boolean = false,
        rebuildQueue: Boolean = true,
    ) {
        queueWindow = queueWindow?.copy(currentIndex = player.currentMediaItemIndex.coerceAtLeast(0))
        val currentItem = player.currentMediaItem
        val captured = captureNowPlayingFields(currentItem)
        val key = currentItem?.let(::presentationKey)
        if (key != null && (forcePresentation || key != lastPresentationKey)) {
            presentationRevision++
            lastPresentationKey = key
        } else if (key == null) {
            lastPresentationKey = null
        }
        val mediaId = captured?.mediaId.orEmpty()
        val title = captured?.title.orEmpty()
        val artist = captured?.artist.orEmpty()
        val audioFormat = captured?.audioFormat.orEmpty()
        val coverId = captured?.coverId
        val album = captured?.album
        val declaredDurationMs = captured?.durationMs
        val identity = if (mediaId.isNotBlank()) {
            currentNamespace?.let { namespace ->
                NowPlayingIdentity(
                    namespace = namespace,
                    mediaId = mediaId,
                    presentationRevision = presentationRevision,
                    title = title,
                    artist = artist,
                    audioFormat = audioFormat,
                    coverId = coverId,
                    album = album,
                    durationMs = declaredDurationMs,
                )
            }
        } else {
            null
        }
        val currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val queueItems = queueProjector.project(player, currentIndex, rebuildQueue)
        _state.value = PlaybackUiState(
            connected = true,
            hasMedia = player.mediaItemCount > 0,
            isPlaying = player.isPlaying,
            playbackState = player.playbackState,
            title = title,
            artist = artist,
            audioFormat = audioFormat,
            mediaId = mediaId,
            coverId = coverId,
            artworkUrl = captured?.artworkUrl,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.coerceAtLeast(0),
            currentIndex = currentIndex,
            itemCount = player.mediaItemCount,
            loadedPlayableCount = queueItems.size,
            queueKind = queueKind,
            queueItems = queueItems,
            playMode = playMode,
            canPrevious = if (queueKind == QueueKind.Roam) currentRoamId != null else player.hasPreviousMediaItem(),
            canNext = if (queueKind == QueueKind.Roam) currentRoamId != null else player.hasNextMediaItem(),
            presentationRevision = presentationRevision,
            nowPlayingIdentity = identity,
            roamBusy = roamJob?.isActive == true || _state.value.roamBusy,
            roamError = roamError,
            canRetryRoam = failedRoamDirection != null,
            error = player.playerError
                // 正在为当前这首取链（等链开播 / 报错自愈）时先不抛错误：
                // 这是几秒内的过渡态，把「音源文件不存在」甩到界面上只会让用户以为坏了。
                // 真取不到链时会由 onResolveImpossible 走跳过/留错，界面照常有结论。
                ?.takeUnless { pendingStreamId == mediaId || awaitingRecoveryId == mediaId }
                ?.let { failure -> PlaybackFailure(failure.errorCode, failure.errorCodeName) },
            queueError = queueError,
            canRetryQueue = failedDirection != null && queueWindow?.invalidated == false,
        )
        updateProgress(player)
        maybeLoadQueueEdge(player)
    }

    private fun updateProgress(player: Player) {
        _progress.value = PlaybackProgressState(
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.coerceAtLeast(0),
            bufferedMs = player.bufferedPosition.coerceAtLeast(0),
        )
    }

    private fun maybeLoadQueueEdge(player: Player) {
        if (queueKind != QueueKind.Normal) return
        val window = queueWindow ?: return
        if (window.loading || window.invalidated || queueError != null || player.mediaItemCount == 0) return
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        when {
            index <= PREFETCH_DISTANCE && !window.reachedStart -> loadQueuePage(QueueDirection.Previous)
            index >= player.mediaItemCount - 1 - PREFETCH_DISTANCE && !window.reachedEnd -> loadQueuePage(QueueDirection.Next)
        }
    }

    private fun loadQueuePage(direction: QueueDirection) {
        val source = queueSource ?: return
        val base = queueWindow ?: return
        if (queuePageJob?.isActive == true || base.loading || base.invalidated) return
        val loading = SlidingQueueReducer.loading(base)
        if (!loading.loading) return
        queueWindow = loading
        val pageNumber = if (direction == QueueDirection.Next) loading.lastPage + 1 else loading.firstPage - 1
        val expectedGeneration = generation
        queuePageJob = scope.launch {
            var result = runCatching { contentSource.queuePage(source, pageNumber) }
            QUEUE_RETRY_DELAYS.forEach { retryDelay ->
                if (result.isFailure && generation == expectedGeneration) {
                    delay(retryDelay)
                    result = runCatching { contentSource.queuePage(source, pageNumber) }
                }
            }
            if (generation != expectedGeneration || queueKind != QueueKind.Normal) return@launch
            try {
                applyQueuePage(direction, result.getOrThrow())
            } catch (_: CancellationException) {
                return@launch
            } catch (_: Throwable) {
                queueWindow = queueWindow?.let(SlidingQueueReducer::failed)
                failedDirection = direction
                queueError = "队列分页加载失败"
            }
            queuePageJob = null
            controller?.let(::project)
        }
    }

    private suspend fun applyQueuePage(direction: QueueDirection, page: com.yueyin.tv.core.model.Page<Track>) {
        val player = controller ?: return
        val state = queueWindow ?: return
        val prepared = contentSource.prepareQueue(page.items)
        val preparedIds = prepared.map { it.track.guid.value }.toHashSet()
        val segment = QueuePageSegment(
            page = page.page,
            rawRowCount = page.items.size,
            playableItems = page.items.mapIndexedNotNull { index, track ->
                track.guid.value.takeIf(preparedIds::contains)?.let { mediaId ->
                    QueuePageItem(mediaId, (page.page - 1) * page.pageSize + index)
                }
            },
            sort = page.sort,
            knownTotal = page.total,
            pageSize = page.pageSize,
        )
        val update = if (direction == QueueDirection.Next) {
            SlidingQueueReducer.append(state, segment)
        } else {
            SlidingQueueReducer.prepend(state, segment)
        }
        queueWindow = update.state
        if (update.state.invalidated) {
            failedDirection = null
            queueError = "歌单已更新，请重新载入"
            return
        }
        val items = prepared.map(::mediaItem)
        if (direction == QueueDirection.Next) {
            if (items.isNotEmpty()) player.addMediaItems(items)
            if (update.removeFromStart > 0) player.removeMediaItems(0, update.removeFromStart)
        } else {
            if (items.isNotEmpty()) player.addMediaItems(0, items)
            if (update.removeFromEnd > 0) {
                val from = (player.mediaItemCount - update.removeFromEnd).coerceAtLeast(0)
                player.removeMediaItems(from, player.mediaItemCount)
            }
        }
        val shuffleReapplyPending = playMode == PlayMode.Shuffle
        val shuffleTransition = if (shuffleReapplyPending) reapplyShuffleAfterQueueMutation(player) else null
        failedDirection = null
        queueError = null
        project(player)
        val transition = if (shuffleReapplyPending) {
            shuffleTransition
        } else {
            structuralSnapshot(player)
        }
        transition?.awaitCommitted()
    }

    private fun advanceRoam(direction: RoamDirection) {
        if (queueKind != QueueKind.Roam || roamJob?.isActive == true) return
        val player = controller ?: return
        val roamId = currentRoamId ?: roamWindow?.current?.roamId ?: return
        val expectedGeneration = generation
        failedRoamDirection = null
        roamError = null
        updateRoamStatus(busy = true, error = null)
        val request = scope.launch(start = CoroutineStart.LAZY) {
            var rollbackSnapshot: PlaybackSnapshot? = null
            var installedRoamId: String? = null
            try {
                val first = when (direction) {
                    RoamDirection.Next -> contentSource.nextRoam(roamId)
                    RoamDirection.Previous -> contentSource.previousRoam(roamId)
                }
                val resolved = resolveRoam(first, direction, expectedGeneration, currentCursor = roamId)
                if (generation != expectedGeneration || queueKind != QueueKind.Roam || currentRoamId != roamId) return@launch
                rollbackSnapshot = captureSnapshot(player, revision = snapshotRevision)
                installedRoamId = resolved.window.current.roamId
                roamWindow = resolved.window
                currentRoamId = installedRoamId
                installRoamTrack(player, resolved.track, autoPlay = true)
                structuralSnapshot(player)?.awaitCommitted()
                autoAdvanceGate.reset()
                updateRoamStatus(busy = false, error = null)
            } catch (cause: CancellationException) {
                if (
                    generation == expectedGeneration &&
                    queueKind == QueueKind.Roam &&
                    currentRoamId == installedRoamId
                ) {
                    restoreAfterFailedRoamMutation(player, rollbackSnapshot)
                }
                throw cause
            } catch (cause: Throwable) {
                if (
                    generation == expectedGeneration &&
                    queueKind == QueueKind.Roam &&
                    currentRoamId == installedRoamId
                ) {
                    restoreAfterFailedRoamMutation(player, rollbackSnapshot)
                }
                failedRoamDirection = direction
                updateRoamStatus(busy = false, error = cause.appError())
            } finally {
                val running = currentCoroutineContext()[Job]
                if (roamJob === running) {
                    roamJob = null
                    updateRoamStatus(busy = false, error = roamError)
                }
                project(player)
            }
        }
        roamJob = request
        request.start()
    }

    private suspend fun resolveRoam(
        initial: RoamWindow,
        direction: RoamDirection,
        expectedGeneration: Long,
        currentCursor: String?,
    ): ResolvedRoam = resolvePlayableRoam(
        initial = initial,
        direction = direction,
        currentCursor = currentCursor,
        ensureCurrent = {
            if (generation != expectedGeneration) throw CancellationException("Playback session changed")
        },
        prepare = { window -> contentSource.prepare(window.current.track) },
        move = { requestedDirection, roamId ->
            when (requestedDirection) {
                RoamDirection.Next -> contentSource.nextRoam(roamId)
                RoamDirection.Previous -> contentSource.previousRoam(roamId)
            }
        },
    )

    private fun installRoamTrack(player: MediaController, track: PlaybackTrack, autoPlay: Boolean) {
        player.setMediaItem(mediaItem(track))
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.shuffleModeEnabled = false
        player.prepare()
        if (autoPlay) player.playIfCastAllows() else player.pause()
        project(player)
    }

    private fun restoreAfterFailedRoamMutation(player: MediaController, snapshot: PlaybackSnapshot?) {
        if (snapshot == null || snapshot.items.isEmpty()) {
            player.stop()
            player.clearMediaItems()
            queueKind = QueueKind.Normal
            playMode = PlayMode.ListRepeat
            shuffleOrderIds = emptyList()
            queueSource = null
            queueWindow = null
            roamWindow = null
            currentRoamId = null
            frozenQueueSnapshot = null
            PlaybackTransportBridge.setOwnership(PlaybackTransportOwnership.Normal)
            project(player)
            return
        }

        val shuffleOrderToRestore = applySnapshot(player, snapshot, activatePersistedShuffle = false)
        if (snapshot.playIntent == PlaybackPlayIntent.Play) player.playIfCastAllows() else player.pause()
        PlaybackTransportBridge.setOwnership(ownershipFor(snapshot.kind))
        project(player)
        shuffleOrderToRestore?.let { order ->
            requestShuffleActivation(
                player = player,
                order = order,
                activationRevision = snapshotRevision,
                fallbackMode = PlayMode.ListRepeat,
                persistOnAccept = false,
                persistFallbackOnReject = true,
            )
        }
    }

    private fun updateRoamStatus(busy: Boolean, error: AppError?) {
        roamError = error
        _state.value = _state.value.copy(
            roamBusy = busy,
            roamError = error,
            canRetryRoam = failedRoamDirection != null,
        )
    }

    private fun resetActiveSessionForRebind(player: MediaController?) {
        player?.run {
            stop()
            clearMediaItems()
        }
        pendingShuffle = null
        frozenQueueSnapshot = null
        queueSource = null
        queueWindow = null
        queueKind = QueueKind.Normal
        playMode = PlayMode.ListRepeat
        shuffleOrderIds = emptyList()
        roamWindow = null
        currentRoamId = null
        failedDirection = null
        queueError = null
        roamError = null
        failedRoamDirection = null
        lastPresentationKey = null
        queueProjector.clear()
        _state.value = PlaybackUiState(connected = player != null)
        _progress.value = PlaybackProgressState()
    }

    private fun beginStructuralTransition(cancelRoam: Boolean = true) {
        generation++
        pendingShuffle = null
        // 换了队列/换了会话，上一首的补链任务必须作废，否则会把旧链 patch 到新队列上。
        streamResolveJob?.cancel()
        streamResolveJob = null
        pendingStreamId = null
        pendingStreamAutoPlay = false
        awaitingRecoveryId = null
        // 换了队列，之前「等链再切」的目标已经不属于新队列了。
        pendingSwitchIndex = null
        pendingSwitchAutoPlay = false
        // 换了队列，上一轮预取的失败记录同样作废，否则新队列里的同一首会被无谓地跳过 30 秒。
        prefetchFailures.clear()
        abandonedStreamId = null
        targetSkipStreak = 0
        // 换了队列，跳过游标按 index 记的，对新队列没有任何意义。
        skipFrontier = null
        // 2.1.4 新增的预取任务原来漏了取消：切歌后它还在后台取旧歌的链，
        // 和当前首重取抢同一个 WebView，链取到时 pending 已被清，白取一场。
        nextPrefetchJob?.cancel()
        nextPrefetchJob = null
        nextPrefetchId = null
        queuePageJob?.cancel()
        queuePageJob = null
        if (cancelRoam) {
            roamJob?.cancel()
            roamJob = null
            updateRoamStatus(busy = false, error = null)
        }
        autoAdvanceGate.reset()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                controller?.let { player ->
                    updateProgress(player)
                    checkpointSnapshot(player)
                    prefetchNextIfNeeded(player)
                }
                delay(PROGRESS_TICK_MS)
            }
        }
    }

    /**
     * 给「下一首」提前取直链。
     *
     * 队列里除开头几首外都是空 uri 入队，后台补链串行，播得快时追不上；
     * 以前只在「最后 20 秒」才预取，用户中途按下一首就必然撞上空链——
     * 那正是「一按下一首就播放失败」的主因。现在改成**滚动预取**：
     * 只要下一首还没有链，当前这首稳定播放时就立刻给它取，不等临近结尾。
     *
     * 注意不复用 [requestStreamUrl]：那个会掐掉进行中的任务并触发自动跳歌，
     * 用在「预取下一首」上会把当前播放搞乱。
     */
    private fun prefetchNextIfNeeded(player: Player) {
        // 当前首正在重取 / 正在等自愈 / 正在为用户点的下一首取链：都别抢同一个 WebView。
        if (awaitingRecoveryId != null || streamResolveJob?.isActive == true || pendingSwitchIndex != null) return
        if (nextPrefetchJob?.isActive == true) return

        val currentIndex = player.currentMediaItemIndex
        // A1（失败补位）：从下一格往后找第一个「无链、不在冷却」的候选。以前只看 +1：
        // 下一首预取失败进了冷却，整条预取管线就停摆，坏曲目一直挡在「下一首」的位置上，
        // 用户每次点下一首都要撞上它现取（实测最差 19.5 秒）。现在继续往后看，
        // 坏曲目挡不住再下一首的预取；已有直链的格子直接跳过（不需要预取）。
        var targetIndex = -1
        var targetId = ""
        var targetTrack: Track? = null
        for (offset in 1..PREFETCH_LOOKAHEAD) {
            val index = currentIndex + offset
            if (index < 0 || index >= player.mediaItemCount) break
            val item = player.getMediaItemAt(index)
            val id = item.mediaId
            if (id.isBlank()) continue
            if (item.localConfiguration?.uri?.toString().orEmpty().isNotBlank()) continue
            if (isPrefetchCooling(id)) continue
            val candidate = submittedTracks.getOrNull(index)?.track ?: continue
            if (candidate.origin == TrackOrigin.Local) continue
            targetIndex = index
            targetId = id
            targetTrack = candidate
            break
        }
        val track = targetTrack ?: return

        nextPrefetchId = targetId
        nextPrefetchJob = scope.launch {
            val started = SystemClock.elapsedRealtime()
            // B（取链测速）：统计上「失败率高且平均慢」的源，预取时收紧到 3 秒——
            // 反正大概率取不到，快失败才能尽快补位到后面的候选，别让唯一 WebView 干等。
            val timeoutMs = sourceStats.suggestPrefetchTimeoutMs(StreamSourceStats.sourceOf(targetId))
            val url = runCatching {
                withContext(Dispatchers.IO) {
                    if (timeoutMs > 0L) {
                        withTimeoutOrNull(timeoutMs) { contentSource.prepare(track).streamUrl } ?: ""
                    } else {
                        contentSource.prepare(track).streamUrl
                    }
                }
            }.getOrNull().orEmpty()
            val elapsed = SystemClock.elapsedRealtime() - started
            val snapshot = sourceStats.record(StreamSourceStats.sourceOf(targetId), elapsed, url.isNotBlank())
            maybeLogSourceStats(snapshot)
            // 取链期间用户已切走：这条链不属于「下一首」了，丢弃。
            if (!isActive || nextPrefetchId != targetId) return@launch
            nextPrefetchId = null
            if (url.isNotBlank()) {
                // 直链探活：取链「成功」不等于链接能播（签名过期/下架/返回网页错误页）。
                // 预取在后台跑，这里多花一次 2 字节 Range 请求零感知；死链在这里被拦下，
                // 就不会留给用户「点下一首 → 起播 → 报错 → 自愈」的十几秒。
                val probeStarted = SystemClock.elapsedRealtime()
                val verdict = withContext(Dispatchers.IO) { StreamUrlProbe.probe(url) }
                val probeMs = SystemClock.elapsedRealtime() - probeStarted
                if (verdict == StreamUrlProbe.Result.BAD) {
                    Log.w(
                        "YueyinPlayback",
                        "prefetch next id=$targetId 取到直链但探活判死（${probeMs}ms），按失败处理，继续找后面的候选",
                    )
                    // 修正统计：上面 record 记了成功，这里补一条失败，让该源尽快被判定不健康。
                    sourceStats.record(StreamSourceStats.sourceOf(targetId), probeMs, false)
                    maybeLogSourceStats(snapshot)
                    recordPrefetchFailure(targetId)
                    // 这首正卡在「等链再切」上：补一次重取（失败就由 onResolveImpossible 跳过这首）。
                    if (pendingStreamId == targetId) {
                        nextPrefetchJob = null
                        requestStreamUrl(targetId, attempts = 1)
                    }
                    return@launch
                }
                if (verdict == StreamUrlProbe.Result.UNKNOWN) {
                    Log.i("YueyinPlayback", "prefetch next id=$targetId 探活超时（${probeMs}ms），放行交给播放器")
                }
                prefetchFailures.remove(targetId)
                Log.i("YueyinPlayback", "prefetch next id=$targetId 提前取链成功（${elapsed}ms${if (verdict == StreamUrlProbe.Result.UNKNOWN) "，探活未确认" else ""}）")
                patchStreamUrl(targetId, url)
            } else {
                recordPrefetchFailure(targetId)
                Log.w(
                    "YueyinPlayback",
                    "prefetch next id=$targetId 提前取链失败（${elapsed}ms），冷却期内不再预取这首；继续找后面的候选",
                )
                // 这首正卡在「等链再切」上：预取刚失败过，再给三次重试等于让用户干等十几秒，
                // 只补一次（失败就由 onResolveImpossible 跳过这首）。
                if (pendingStreamId == targetId) {
                    nextPrefetchJob = null
                    requestStreamUrl(targetId, attempts = 1)
                }
            }
        }
    }

    /** 该 id 是否还在预取失败冷却期内；过期顺手清掉。 */
    private fun isPrefetchCooling(id: String): Boolean {
        val failedAt = prefetchFailures[id] ?: return false
        if (SystemClock.elapsedRealtime() - failedAt < PREFETCH_FAIL_COOLDOWN_MS) return true
        prefetchFailures.remove(id)
        return false
    }

    private fun recordPrefetchFailure(id: String) {
        prefetchFailures[id] = SystemClock.elapsedRealtime()
        // 防御性上限：队列里坏曲目很多时别让 Map 无限长，挤掉最旧的记录即可。
        if (prefetchFailures.size > PREFETCH_FAILURE_MEMORY) {
            val it = prefetchFailures.entries.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
    }

    /** 每累计 10 次取链打一条按源汇总，方便看哪个源慢、哪个源失败率高。 */
    private fun maybeLogSourceStats(snapshot: StreamSourceStats.Snapshot?) {
        if (snapshot == null || snapshot.samples == 0 || snapshot.samples % SOURCE_STATS_LOG_EVERY != 0) return
        Log.i("YueyinPlayback", "取链统计 ${sourceStats.summary()}")
    }

    private fun mediaItem(playback: PlaybackTrack): MediaItem = MediaItem.Builder()
        .setMediaId(playback.track.guid.value)
        .setUri(playback.streamUrl)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(playback.track.title)
                .setArtist(playback.track.artistName)
                .setAlbumTitle(playback.track.albumName)
                .setArtworkUri(playback.artworkUrl?.let(Uri::parse))
                .setExtras(mediaExtras(playback.track.audioFormat, playback.track.coverId, playback.track.durationMs))
                .build(),
        )
        .build()

    /**
     * 给队列里还没取到直链的那一项补上地址。
     *
     * 「播放全部」时只同步取开头几首（否则要等几十秒），其余项先以空 uri 入队；
     * 由调用方在后台逐首取链后调这里补回去。已经有 uri 的项直接跳过，不打断播放。
     */
    /**
     * 切歌守卫：目标曲目是否已经有直链。
     *
     * 返回 true = 放行，让播放器原生切过去；false = 已接管，先取链，取到后由
     * [patchStreamUrl] 完成真正的切换。注册在 [PlaybackTransportBridge.skipGuard] 上，
     * 所以媒体键、通知栏、车机发出的切歌也会经过这里。
     */
    private fun canSwitchTo(rawTargetIndex: Int, userInitiated: Boolean = true): Boolean {
        val player = controller ?: return true
        if (rawTargetIndex !in 0 until player.mediaItemCount) return true
        var targetIndex = rawTargetIndex
        // 连按「下一首」时守卫算出的目标永远是「当前播放项 + 1」，而自动跳过失败时
        // 播放位置并没有变化——于是同一个播不了的位置会被反复当成目标，连跳额度一满
        // 就再也不动了（实测连点 10 次全部停在原曲）。这里接着上次停下的游标往后走。
        if (userInitiated && rawTargetIndex > player.currentMediaItemIndex) {
            val frontier = skipFrontier
            if (frontier != null && targetIndex <= frontier) {
                val resumed = frontier + 1
                if (resumed >= player.mediaItemCount) {
                    Log.w("YueyinPlayback", "canSwitchTo: 已到队尾 frontier=$frontier，不再往后跳")
                    return false
                }
                targetIndex = resumed
                Log.i("YueyinPlayback", "canSwitchTo: 上次停下的 $frontier 之后继续 → index=$targetIndex")
            }
        }
        val item = player.getMediaItemAt(targetIndex)
        val uri = item.localConfiguration?.uri?.toString().orEmpty()
        val id = item.mediaId
        if (uri.isNotBlank() || id.isBlank()) {
            pendingSwitchIndex = null
            pendingSwitchAutoPlay = false
            // 真正切成了：连跳计数与跳过游标一起归零，下一次卡顿重新给足机会。
            targetSkipStreak = 0
            skipFrontier = null
            return true
        }
        // 同一首已经在取链，用户又按了一次「下一首」：这不再是「继续等」，
        // 而是「这首我不要了」。以前这里静默 return，用户连按十几下界面毫无反应，
        // 看起来就是按键失灵。现在按「取不到链」处理，直接把这一首跳过去。
        if (pendingSwitchIndex == targetIndex && pendingStreamId == id &&
            (streamResolveJob?.isActive == true || (nextPrefetchId == id && nextPrefetchJob?.isActive == true))
        ) {
            Log.i("YueyinPlayback", "canSwitchTo: index=$targetIndex id=$id 再按一次下一首，放弃这首往后跳")
            abandonPendingSwitch()
            skipUnplayableTarget(targetIndex)
            return false
        }
        // 用户每次新按键都重新给足连跳额度：上一次是「这首真播不了」而不是「键盘失效」，
        // 用户再按一下应该继续往后找，而不是把额度耗尽的沉默留给他。
        if (userInitiated) targetSkipStreak = 0
        pendingSwitchIndex = targetIndex
        pendingSwitchAutoPlay = true
        pendingStreamId = id
        pendingStreamAutoPlay = true
        awaitingRecoveryId = null
        // 用户明确点了这首：清掉预取失败冷却，允许重新试一次（预取那条只负责省流量，不能挡用户）。
        prefetchFailures.remove(id)
        if (abandonedStreamId == id) abandonedStreamId = null
        Log.i("YueyinPlayback", "canSwitchTo: index=$targetIndex id=$id 还没有直链，先取链再切")
        // 用户主动切歌只给一次取链机会、并加一个更短的上限：底层脚本对 VIP 曲目会连试五组
        // 接口（实测 11 秒才失败），这期间用户的每一次「下一首」都被吞掉。6 秒没结果就先放手。
        // 预取正在取这一首时直接复用它，避免两条取链互相掐掉（单 WebView 串行）。
        if (!(nextPrefetchId == id && nextPrefetchJob?.isActive == true)) {
            requestStreamUrl(id, attempts = 1, timeoutMs = USER_SWITCH_TIMEOUT_MS)
        }
        return false
    }

    /**
     * 放弃「等链再切」：停掉这条取链，并把目标记为已放弃。
     *
     * 只清控制器自己的状态，不动播放器的当前项——用户原本那首还在放，
     * 往后跳由调用方接着做（见 [skipUnplayableTarget]）。
     */
    private fun abandonPendingSwitch() {
        val id = pendingStreamId
        streamResolveJob?.cancel()
        streamResolveJob = null
        if (nextPrefetchJob?.isActive == true) {
            nextPrefetchJob?.cancel()
            nextPrefetchJob = null
            nextPrefetchId = null
        }
        // 被放弃的曲目随后可能补来一条空 URI 的 ERROR 回调，不标记就会把
        // 刚为下一首发起的取链掐掉重来。
        if (!id.isNullOrBlank()) abandonedStreamId = id
        pendingSwitchIndex = null
        pendingSwitchAutoPlay = false
        pendingStreamId = null
        pendingStreamAutoPlay = false
        awaitingRecoveryId = null
    }

    fun patchStreamUrl(mediaId: String, streamUrl: String, force: Boolean = false) {
        if (streamUrl.isBlank()) return
        val player = controller ?: return
        val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == mediaId }
            ?: return
        val item = player.getMediaItemAt(index)
        val existing = item.localConfiguration?.uri?.toString().orEmpty()
        // force 用于播放失败后的「换新链」：旧链还在队列里，但已经失效了。
        if (!force && existing.isNotBlank()) return
        player.replaceMediaItem(index, item.buildUpon().setUri(streamUrl).build())
        // 用户之前点了「下一首」并卡在这首上等链：现在链到手，完成那次切换。
        val switchTarget = pendingSwitchIndex
        if (switchTarget != null &&
            switchTarget in 0 until player.mediaItemCount &&
            player.getMediaItemAt(switchTarget).mediaId == mediaId
        ) {
            val autoPlay = pendingSwitchAutoPlay
            pendingSwitchIndex = null
            pendingSwitchAutoPlay = false
            pendingStreamId = null
            pendingStreamAutoPlay = false
            awaitingRecoveryId = null
            // 这一次「等链再切」真的落地了，跳过游标作废。
            skipFrontier = null
            targetSkipStreak = 0
            player.seekTo(switchTarget, 0L)
            player.prepare()
            if (autoPlay) player.playIfCastAllows()
            Log.i("YueyinPlayback", "patchStreamUrl: id=$mediaId 链已到手，切到 index=$switchTarget")
            return
        }
        val isCurrent = player.currentMediaItemIndex == index
        // 这首正是「等链开播」的当前首：补到链就立刻启动，不再停在未 prepare 的状态。
        val pendingThis = pendingStreamId == mediaId && isCurrent
        // P0 自愈：直链失效后播放器停在 ERROR，ExoPlayer 不会因 replaceMediaItem 自动恢复。
        // 即使结构转换清掉了 pendingStreamId，只要这首仍在等自愈，就要主动 prepare + play。
        val recoverThis = awaitingRecoveryId == mediaId && isCurrent
        if (pendingThis || recoverThis) {
            val autoPlay = pendingStreamAutoPlay || recoverThis
            pendingStreamId = null
            pendingStreamAutoPlay = false
            awaitingRecoveryId = null
            player.prepare()
            if (autoPlay) player.playIfCastAllows() else player.pause()
            Log.i(
                "YueyinPlayback",
                "patchStreamUrl: id=$mediaId 已补到直链，${if (recoverThis) "ERROR 自愈" else "启动播放"}",
            )
        }
    }

    /**
     * 当前首没直链时就地重取。
     *
     * 在线音源只有一个隐藏 WebView、取链严格串行，所以必须在起播当下就发起（早于后台
     * 补链那几十首），且全局只留一条在跑；`beginStructuralTransition` 会把旧任务掐掉。
     * 每次先作废 [StreamUrlCache]，否则会一直命中刚才那条失败的空结果。
     */
    private fun requestStreamUrl(
        id: String,
        attempts: Int = MAX_STREAM_RESOLVE_ATTEMPTS,
        timeoutMs: Long = 0L,
    ) {
        // 预取任务正在取同一首：直接复用它，别再起一条把它掐掉——
        // 单 WebView 是串行的，互相掐的结果就是两条都白跑。
        if (nextPrefetchId == id && nextPrefetchJob?.isActive == true) {
            Log.i("YueyinPlayback", "requestStreamUrl: id=$id 复用进行中的预取")
            return
        }
        streamResolveJob?.cancel()
        // 当前首要重取时，若预取目标不是它，就让预取让路：一次取链要 1~6 秒，
        // 让当前这首排在预取后面，用户看到的就是「按了下一首没反应」。
        if (nextPrefetchJob?.isActive == true) {
            nextPrefetchJob?.cancel()
            nextPrefetchJob = null
            Log.i("YueyinPlayback", "requestStreamUrl: id=$id 让预取让路")
            nextPrefetchId = null
        }
        val track = submittedTracks.firstOrNull { it.track.guid.value == id }?.track
        if (track == null) {
            Log.w("YueyinPlayback", "requestStreamUrl: 找不到 id=$id 的曲目快照")
            onResolveImpossible(id)
            return
        }
        // 本地曲目的 content:// URI 已直接写进 MediaItem，不需要也不可能联网取链。
        // 不在这里提前返回的话，播放失败会被误判成「在线直链没补到」，陷入无限重取。
        if (track.origin == TrackOrigin.Local) {
            Log.i("YueyinPlayback", "requestStreamUrl: 本地曲目跳过联网补链 id=$id")
            return
        }
        streamResolveJob = scope.launch {
            val rounds = attempts.coerceIn(1, MAX_STREAM_RESOLVE_ATTEMPTS)
            repeat(rounds) { attempt ->
                StreamUrlCache.invalidate(id)
                var timedOut = false
                val started = SystemClock.elapsedRealtime()
                val url = runCatching {
                    withContext(Dispatchers.IO) {
                        if (timeoutMs > 0L) {
                            // 只让「这一轮等待」提前结束，不往下追：底层脚本那次求值会自己
                            // 在 12 秒硬超时里收尾，这里提前放手是为了让用户能立刻跳到下一首。
                            withTimeoutOrNull(timeoutMs) { contentSource.prepare(track).streamUrl }
                                ?: "".also { timedOut = true }
                        } else {
                            contentSource.prepare(track).streamUrl
                        }
                    }
                }.getOrDefault("")
                // B（取链测速）：用户主动取链也计入按源统计（超时按失败记）。
                sourceStats.record(
                    StreamSourceStats.sourceOf(id),
                    SystemClock.elapsedRealtime() - started,
                    url.isNotBlank(),
                )
                if (timedOut) {
                    Log.w("YueyinPlayback", "requestStreamUrl: id=$id ${timeoutMs}ms 内没取到链，先放手")
                }
                if (url.isNotBlank()) {
                    // 直链探活：BAD = 签名过期/下架/返回错误页。这种死链交给播放器要十几秒
                    // 报错自愈，在这里按「这次取链失败」处理，走既有的重试/换源/跳过路径。
                    val probeStarted = SystemClock.elapsedRealtime()
                    val verdict = withContext(Dispatchers.IO) { StreamUrlProbe.probe(url) }
                    val probeMs = SystemClock.elapsedRealtime() - probeStarted
                    if (verdict != StreamUrlProbe.Result.BAD) {
                        if (verdict == StreamUrlProbe.Result.UNKNOWN) {
                            Log.i("YueyinPlayback", "requestStreamUrl: id=$id 探活超时（${probeMs}ms），放行交给播放器")
                        }
                        patchStreamUrl(id, url, force = true)
                        return@launch
                    }
                    Log.w("YueyinPlayback", "requestStreamUrl: id=$id 取到直链但探活判死（${probeMs}ms），按本次失败处理")
                    sourceStats.record(StreamSourceStats.sourceOf(id), probeMs, false)
                    // 落到下面的重试/放弃逻辑：attempt 未用尽则重取（脚本可能给出新链接），
                    // 用尽则 onResolveImpossible 跳过这一首。
                }
                if (attempt < rounds - 1) {
                    // 退避重试：脚本取链失败多半是瞬时网络问题，500ms 一次太急。
                    delay(STREAM_RESOLVE_DELAY_MS * (attempt + 1))
                }
            }
            Log.w("YueyinPlayback", "requestStreamUrl: id=$id 重取直链仍失败，尝试换下一首")
            if (awaitingRecoveryId == id) awaitingRecoveryId = null
            onResolveImpossible(id)
        }
    }

    /**
     * 取链彻底失败（或压根没有这首的曲目快照）之后怎么办。
     *
     * 两种情况分开处理，不能都塞进 [autoSkipAfterFailure]：
     * - 这首是**用户点的下一首**：当前歌曲根本没变，播放器里没有报错，
     *   自动跳过那套（按 mediaId 对比当前项）完全不成立。此时直接把「等链切换」取消，
     *   顺手跳过一个位置，让用户不至于卡在原地看着没反应。
     * - 这首是**当前正在播的**：走原来的自动跳过。
     */
    private fun onResolveImpossible(id: String) {
        // 记下这首已经放弃：播放器随后补来的空 URI ERROR 不该再触发一轮取链。
        abandonedStreamId = id
        val target = pendingSwitchIndex
        if (target != null) {
            val mediaId = controller?.let { player ->
                if (target in 0 until player.mediaItemCount) player.getMediaItemAt(target).mediaId else null
            }
            if (mediaId == id) {
                pendingSwitchIndex = null
                pendingSwitchAutoPlay = false
                pendingStreamId = null
                pendingStreamAutoPlay = false
                Log.w("YueyinPlayback", "onResolveImpossible: 目标 index=$target id=$id 取不到链，跳过这一首")
                skipUnplayableTarget(target, auto = true)
                return
            }
        }
        autoSkipAfterFailure(id)
    }

    /** 取不到链的目标：顺着用户按键的方向找一个能播的（或直接放到相邻一首，由守卫继续接管）。 */
    private fun skipUnplayableTarget(fromIndex: Int, auto: Boolean = false) {
        val player = controller ?: return
        // 方向按「目标相对当前播放项」判断：按下一首就往后、按上一首就往前。
        // 固定 +1 会让「上一首卡住」时反着跳到用户身后，那个更难看懂。
        val step = if (fromIndex >= player.currentMediaItemIndex) 1 else -1
        // 往后跳时记下游标：因为播放位置没变，下一次按键算出的目标还会落回这一格，
        // 只有记住「已经试到哪」才能接着往后走（见 canSwitchTo 开头）。
        if (step > 0) skipFrontier = maxOf(skipFrontier ?: fromIndex, fromIndex)
        // 自动（非用户直接按键）跳过要设上限：超时把每次等待从 11 秒压到 6 秒之后，
        // 队列里连着几首取不到链时会一路「6 秒跳一首」，用户按一下可能落到完全不相干的位置。
        // 达到上限先停下，但游标已经记好，用户再按一下就从这里继续，不会回到原点。
        if (auto) {
            if (targetSkipStreak >= MAX_CHAINED_TARGET_SKIPS) {
                Log.w(
                    "YueyinPlayback",
                    "连续跳过 ${targetSkipStreak} 首仍取不到链，先停在这里（下次按键从 ${(skipFrontier ?: fromIndex) + 1} 继续）",
                )
                return
            }
            targetSkipStreak++
        } else {
            targetSkipStreak = 0
        }
        val nextIndex = fromIndex + step
        if (nextIndex !in 0 until player.mediaItemCount) return
        // 内部连跳不能带 userInitiated=true：否则每跳一首都会把额度清零，变成无限连跳。
        if (!canSwitchTo(nextIndex, userInitiated = false)) return
        player.seekTo(nextIndex, 0L)
        player.prepare()
        player.playIfCastAllows()
    }

    /**
     * 这一首彻底播不了时换下一首。
     *
     * 两条路都会走到这里：① 直链失效且自动重取也失败；② 同一首歌已经重取过一次仍报错。
     * 连续失败达到上限就停手，把错误留在界面上（说明是真断网/音源挂了，再跳也是白跳）。
     */
    private fun autoSkipAfterFailure(failingId: String) {
        val player = controller ?: return
        if (player.currentMediaItem?.mediaId != failingId) return
        if (consecutivePlaybackFailures >= MAX_AUTO_SKIP_FAILURES) return
        consecutivePlaybackFailures++
        if (!player.hasNextMediaItem()) return
        scope.launch {
            delay(AUTO_SKIP_DELAY_MS)
            val current = controller ?: return@launch
            if (current.currentMediaItem?.mediaId != failingId) return@launch
            runCatching {
                current.seekToNextMediaItem()
                // 下一首的 uri 同样来自预取缓存，先作废：即使这条也过期，
                // 它报错时会走「自动重取」分支自愈，而不是连环失败。
                current.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
                    ?.let { StreamUrlCache.invalidate(it) }
                current.prepare()
                current.playIfCastAllows()
            }
        }
    }

    /**
     * 「重试」真正该做的事：把当前这首的过期直链作废、现场重取一条新的，
     * 替换队列里的 uri 后重新 prepare/play。
     *
     * 以前播放页的重试只调 playPause()，对已经报 IO 错误的 MediaItem 什么都不会改变；
     * 换音源也只是命中同一份无 TTL 缓存。这两条一起造成「怎么试都连不上音源」。
     */
    fun retryCurrentPlayback() {
        val player = controller ?: return
        val id = player.currentMediaItem?.mediaId ?: return
        // 用户主动点「重试」时，取消后台那条重取任务，避免两条取链互相抢 WebView、
        // 也避免旧的 patch 把这次刚换好的链又覆盖回去。
        streamResolveJob?.cancel()
        streamResolveJob = null
        pendingStreamId = null
        pendingStreamAutoPlay = false
        awaitingRecoveryId = null
        pendingSwitchIndex = null
        pendingSwitchAutoPlay = false
        // 用户主动点「重试」：之前对这首的放弃记录与预取冷却都要清掉，否则重试会被直接挡回来。
        abandonedStreamId = null
        prefetchFailures.remove(id)
        targetSkipStreak = 0
        nextPrefetchJob?.cancel()
        nextPrefetchJob = null
        nextPrefetchId = null
        val track = submittedTracks.firstOrNull { it.track.guid.value == id }?.track
        StreamUrlCache.invalidate(id)
        if (track == null) {
            Log.w("YueyinPlayback", "retryCurrentPlayback: 找不到 id=$id 的曲目快照，回退到下一首")
            if (player.hasNextMediaItem()) {
                runCatching {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.playIfCastAllows()
                }
            }
            return
        }
        scope.launch {
            val prepared = runCatching {
                withContext(Dispatchers.IO) { contentSource.prepare(track) }
            }.getOrNull()
            val url = prepared?.streamUrl.orEmpty()
            if (url.isBlank()) {
                Log.w("YueyinPlayback", "retryCurrentPlayback: id=$id 重新取链失败")
                return@launch
            }
            val current = controller ?: return@launch
            val index = (0 until current.mediaItemCount).firstOrNull {
                current.getMediaItemAt(it).mediaId == id
            } ?: return@launch
            current.replaceMediaItem(index, current.getMediaItemAt(index).buildUpon().setUri(url).build())
            current.prepare()
            current.playIfCastAllows()
            val host = runCatching { java.net.URI(url).host }.getOrNull()
            Log.i("YueyinPlayback", "retryCurrentPlayback: id=$id 已换新链 host=$host")
        }
    }

    private fun applyModeLocally(player: Player, mode: PlayMode) {
        player.repeatMode = when (mode.mapping.repeatBehavior) {
            RepeatBehavior.Off -> Player.REPEAT_MODE_OFF
            RepeatBehavior.One -> Player.REPEAT_MODE_ONE
            RepeatBehavior.All -> Player.REPEAT_MODE_ALL
        }
        player.shuffleModeEnabled = mode.mapping.shuffleEnabled
    }

    private fun stableShuffle(ids: List<String>, revision: Long): List<String> {
        val seed = revision xor (currentNamespace?.hashCode()?.toLong() ?: 0L)
        val random = Random(seed)
        return ids.toMutableList().apply {
            for (index in lastIndex downTo 1) {
                val other = random.nextInt(index + 1)
                val value = this[index]
                this[index] = this[other]
                this[other] = value
            }
        }
    }

    private fun reapplyShuffleAfterQueueMutation(player: MediaController): PlaybackTransition? {
        val canonical = List(player.mediaItemCount) { index -> player.getMediaItemAt(index).mediaId }
        val retained = shuffleOrderIds.filter(canonical::contains)
        val added = canonical.filterNot(retained::contains)
        val order = retained + stableShuffle(added, snapshotRevision + 2L)
        playMode = PlayMode.ListRepeat
        shuffleOrderIds = emptyList()
        applyModeLocally(player, playMode)
        val transition = structuralSnapshot(player)
        requestShuffleActivation(
            player = player,
            order = order,
            activationRevision = snapshotRevision + 1L,
            fallbackMode = PlayMode.ListRepeat,
            persistOnAccept = true,
            persistFallbackOnReject = false,
        )
        return transition
    }

    private fun checkpointSnapshot(player: Player, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSnapshotAt < SNAPSHOT_INTERVAL_MS) return
        if (pendingShuffle != null) return
        lastSnapshotAt = now
        val namespace = currentNamespace ?: return
        val revision = ++snapshotRevision
        snapshotPersistence.enqueueCheckpoint(
            PlaybackSnapshotWriteRequest(
                namespace = namespace,
                revision = revision,
                payload = null,
                snapshot = captureSnapshot(player, revision),
            ),
        )
    }

    private fun structuralSnapshot(
        player: Player,
        playIntentOverride: PlaybackPlayIntent? = null,
    ): PlaybackTransition? {
        lastSnapshotAt = System.currentTimeMillis()
        val namespace = currentNamespace ?: return null
        val revision = ++snapshotRevision
        val request = PlaybackSnapshotWriteRequest(
            namespace = namespace,
            revision = revision,
            payload = null,
            snapshot = captureSnapshot(player, revision, playIntentOverride = playIntentOverride),
        )
        return snapshotCommitTracker.track(namespace, revision, snapshotPersistence.submitStructural(request))
    }

    suspend fun flushPlaybackSnapshot() = snapshotCommitTracker.flush()

    private fun captureSnapshot(
        player: Player,
        revision: Long,
        includeFrozen: Boolean = true,
        playIntentOverride: PlaybackPlayIntent? = null,
    ): PlaybackSnapshot = PlaybackSnapshot(
        generation = generation,
        revision = revision,
        items = List(player.mediaItemCount.coerceAtMost(MAX_QUEUE_ITEMS)) { index -> player.getMediaItemAt(index) },
        index = player.currentMediaItemIndex.coerceAtLeast(0),
        positionMs = player.currentPosition.coerceAtLeast(0),
        source = queueSource,
        window = queueWindow,
        kind = queueKind,
        mode = playMode,
        shuffleOrder = shuffleOrderIds.takeIf { queueKind == QueueKind.Normal }.orEmpty(),
        roamWindow = roamWindow,
        currentRoamId = currentRoamId,
        frozen = frozenQueueSnapshot.takeIf { includeFrozen },
        playIntent = playIntentOverride
            ?: if (player.playWhenReady) PlaybackPlayIntent.Play else PlaybackPlayIntent.Pause,
    )

    private suspend fun restoreQueue(player: MediaController, encoded: String?, legacyFrozen: String?) {
        val snapshot = encoded?.let { PlaybackSnapshotCodec.decode(it, legacyFrozen) }
        Log.i(
            "YueyinPlayback",
            "restoreQueue 解码=${snapshot != null} 条目=${snapshot?.items?.size ?: 0} index=${snapshot?.index}",
        )
        if (snapshot == null) return
        if (snapshot.items.isEmpty()) return
        applySnapshot(player, snapshot)
        player.pause()
        if (snapshot.legacy) structuralSnapshot(player)?.awaitCommitted()
    }

    private fun applySnapshot(
        player: MediaController,
        snapshot: PlaybackSnapshot,
        activatePersistedShuffle: Boolean = true,
    ): List<String>? {
        if (snapshot.items.isEmpty()) return null
        // v2 快照里可能还写着在线直链；那些链早就过期了，恢复时必须清空重新取，
        // 否则「退出再进」恢复出来的是一整队死链（这次的第二个问题）。
        val items = snapshot.items.map(::snapshotItemWithFreshUri)
        val restoredIndex = snapshot.index.coerceIn(items.indices)
        player.setMediaItems(items, restoredIndex, snapshot.positionMs)
        // 重启后 submittedTracks 是空的：不重建的话 requestStreamUrl 找不到 Track，
        // 会直接 return，重试/换源/自动跳过全部不执行 —— 表现就是「怎么点都播不了」。
        submittedTracks = items.map(::rebuildSubmittedTrack)
        queueSource = snapshot.source
        queueWindow = snapshot.window
        queueKind = snapshot.kind
        val restoreShuffle = snapshot.kind == QueueKind.Normal && snapshot.mode == PlayMode.Shuffle
        playMode = if (restoreShuffle) PlayMode.ListRepeat else snapshot.mode
        shuffleOrderIds = if (restoreShuffle) emptyList() else snapshot.shuffleOrder
        roamWindow = snapshot.roamWindow
        currentRoamId = snapshot.currentRoamId
        frozenQueueSnapshot = snapshot.frozen
        snapshotRevision = maxOf(snapshotRevision, snapshot.revision)
        val currentId = items.getOrNull(restoredIndex)?.mediaId.orEmpty()
        val currentUri = items.getOrNull(restoredIndex)
            ?.localConfiguration?.uri?.toString().orEmpty()
        if (currentUri.isBlank() && currentId.isNotBlank()) {
            // 先取链，不 prepare：空 uri 交给 ExoPlayer 只会立刻报「音源文件不存在」。
            pendingStreamId = currentId
            pendingStreamAutoPlay = false
            awaitingRecoveryId = null
            requestStreamUrl(currentId)
        } else {
            player.prepare()
        }
        applyModeLocally(player, if (queueKind == QueueKind.Roam) PlayMode.Sequence else playMode)
        if (restoreShuffle && activatePersistedShuffle) {
            requestShuffleActivation(
                player = player,
                order = snapshot.shuffleOrder,
                activationRevision = snapshot.revision,
                fallbackMode = PlayMode.ListRepeat,
                persistOnAccept = false,
                persistFallbackOnReject = true,
            )
        }
        return snapshot.shuffleOrder.takeIf { restoreShuffle }
    }

    /**
     * 恢复快照时判断一条在线直链还能不能用。
     *
     * 冷启动时 [StreamUrlCache] 是空的，任何 http 直链都当作过期、清空重取；
     * 若是本次会话内的回滚（例如随想失败后还原队列），缓存里还留着同一条刚取到的链，
     * 就直接沿用，省掉一次 1~2 秒的取链。
     */
    private fun snapshotItemWithFreshUri(item: MediaItem): MediaItem {
        val uri = item.localConfiguration?.uri ?: return item
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") return item
        val text = uri.toString()
        val cached = StreamUrlCache.get(item.mediaId, STREAM_URI_FRESH_MS)
        if (cached != null && cached == text) return item
        return item.buildUpon().setUri("").build()
    }

    /**
     * 用快照里的元数据把「曲目快照」重建出来。
     *
     * 重启后 [submittedTracks] 本是空的，而重取链依赖它按 mediaId 找 [Track]。
     * 重建它，`requestStreamUrl` 才能在恢复的队列上正常工作。
     */
    private fun rebuildSubmittedTrack(item: MediaItem): PlaybackTrack {
        val metadata = item.mediaMetadata
        val uri = item.localConfiguration?.uri?.toString().orEmpty()
        val local = uri.startsWith("content://") || uri.startsWith("file://")
        val extras = metadata.extras
        val track = Track(
            guid = TrackGuid(item.mediaId),
            title = metadata.title?.toString().orEmpty(),
            artistName = metadata.artist?.toString()?.takeIf(String::isNotBlank),
            albumName = metadata.albumTitle?.toString()?.takeIf(String::isNotBlank),
            coverId = extras?.getString(COVER_ID_KEY),
            durationMs = extras
                ?.takeIf { it.containsKey(DECLARED_DURATION_MS_KEY) }
                ?.getLong(DECLARED_DURATION_MS_KEY)
                ?.takeIf { it > 0L },
            isCue = false,
            audioFormat = extras?.getString(AUDIO_FORMAT_KEY),
            origin = if (local) TrackOrigin.Local else TrackOrigin.Online,
            localUri = uri.takeIf { local },
        )
        return PlaybackTrack(track = track, streamUrl = uri, artworkUrl = metadata.artworkUri?.toString())
    }

    private fun mediaExtras(audioFormat: String?, coverId: String?, durationMs: Long?): Bundle = Bundle().apply {        audioFormat?.takeIf(String::isNotBlank)?.let { putString(AUDIO_FORMAT_KEY, it) }
        coverId?.takeIf(String::isNotBlank)?.let { putString(COVER_ID_KEY, it) }
        durationMs?.takeIf { it > 0L }?.let { putLong(DECLARED_DURATION_MS_KEY, it) }
    }


    private fun presentationKey(item: MediaItem): PresentationKey {
        val captured = requireNotNull(captureNowPlayingFields(item))
        return PresentationKey(
            mediaId = captured.mediaId,
            title = captured.title,
            artist = captured.artist,
            audioFormat = captured.audioFormat,
            coverId = captured.coverId,
            album = captured.album,
            durationMs = captured.durationMs,
        )
    }

    private fun ownershipFor(kind: QueueKind): PlaybackTransportOwnership = when (kind) {
        QueueKind.Normal -> PlaybackTransportOwnership.Normal
        QueueKind.Roam -> PlaybackTransportOwnership.Roam
    }

    private fun Throwable.appError(): AppError = (this as? AppException)?.error ?: AppError.Unknown()

    private data class PresentationKey(
        val mediaId: String,
        val title: String,
        val artist: String,
        val audioFormat: String,
        val coverId: String?,
        val album: String?,
        val durationMs: Long?,
    )

    private data class RoamExitTransition(
        val restoredQueue: Boolean,
        val transition: PlaybackTransition?,
    )

    private enum class QueueDirection { Previous, Next }

    private companion object {
        const val PAGE_SIZE = 50
        const val PREFETCH_DISTANCE = 15
        const val MAX_QUEUE_ITEMS = 250
        const val PROGRESS_TICK_MS = 250L
        const val SNAPSHOT_INTERVAL_MS = 5_000L
        const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L
        val QUEUE_RETRY_DELAYS = longArrayOf(500L, 1_000L, 2_000L)

        /** 坏链接自动跳过的上限：连续这么多次失败就认为是真的断网，把错误留在界面上。 */
        const val MAX_AUTO_SKIP_FAILURES = 2
        const val AUTO_SKIP_DELAY_MS = 400L

        /**
         * 当前首没链时的重取次数与间隔。
         *
         * 从 3 次收到 2 次：单次取链现在最多 9 秒，3 次重试意味着用户要干等近半分钟，
         * 而失败两次基本就能确定是这首/这个源不行，交给「重试」按钮更诚实。
         */
        const val MAX_STREAM_RESOLVE_ATTEMPTS = 3
        const val STREAM_RESOLVE_DELAY_MS = 600L

        /**
         * 用户主动切歌时的取链上限。
         *
         * 底层脚本对版权受限曲目会连试五组接口，实测 11 秒才返回失败；那 11 秒里用户按的
         * 每一下「下一首」都被守卫拦下并丢掉，体感就是「按不动」。
         * 6 秒是个折中：正常曲目 0.2~2 秒就能拿到，超过 6 秒的多半是这首真取不到。
         */
        const val USER_SWITCH_TIMEOUT_MS = 6_000L

        /**
         * 一次按键最多自动跳过几首：连跳超过这个数就停下。
         *
         * 原来是 2——太紧了。咪咕那批曲目取链是「0ms 直接失败」，队列里连着三首播不了
         * 就会撞满额度，配合「播放位置没变 → 目标永远是同一格」形成死循环，
         * 用户连点十几次全都没有反应。放宽到 8 并配上跳过游标：一次按键就能越过一小段
         * 播不了的曲目，越不过去的下次按键接着往后走。
         */
        const val MAX_CHAINED_TARGET_SKIPS = 8

        /** 恢复快照时，这个时限内取到的直链还当作可用（否则清空重取）。 */
        const val STREAM_URI_FRESH_MS = 10 * 60_000L

        /**
         * 同一首预取失败后的冷却时间。
         *
         * ticker 每 250ms 调一次预取，秒失败的曲目会被无限重试（实测 1.3 秒 6 次），
         * 每次都去抢唯一的 WebView。冷却 30 秒足够让当前首的重取/用户切歌先走完。
         */
        const val PREFETCH_FAIL_COOLDOWN_MS = 30_000L

        /**
         * A1（失败补位）：预取最多往前看几格。下一首失败进冷却时继续往后找，
         * 别让坏曲目挡住整条预取管线。3 格兼顾覆盖率与 WebView 负载。
         */
        const val PREFETCH_LOOKAHEAD = 3

        /** 预取失败记录的内存上限（超过就挤掉最旧的），坏曲目很多时防 Map 无限长。 */
        const val PREFETCH_FAILURE_MEMORY = 12

        /** 每累计多少次取链打一条按源汇总日志。 */
        const val SOURCE_STATS_LOG_EVERY = 10
    }
}

const val AUDIO_FORMAT_KEY = "com.yueyin.tv.AUDIO_FORMAT"
const val COVER_ID_KEY = "com.yueyin.tv.COVER_ID"
const val DECLARED_DURATION_MS_KEY = "com.yueyin.tv.DECLARED_DURATION_MS"
