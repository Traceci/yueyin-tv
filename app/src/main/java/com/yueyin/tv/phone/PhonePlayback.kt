package com.yueyin.tv.phone

import com.yueyin.tv.AppUiDependencies
import com.yueyin.tv.core.model.PlaybackTrack
import com.yueyin.tv.core.model.Track
import com.yueyin.tv.core.model.playback.PlayMode
import com.yueyin.tv.ui.RoamPrewarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 手机端的起播逻辑。
 *
 * 与电视端共用同一批仓库方法，但队列策略不同：
 *
 * - 电视端「播放全部」用的是**滑动窗口队列**（`QueueSource` + `QueuePageSegment`），
 *   为了在 10 万首的在线曲库里翻页时也能无缝续播，代价是状态机很重（窗口、分页边界、排序校验）。
 * - 手机 P1 用**首页队列**：点进一个集合就把它已加载的那一页整体交给播放器。
 *   代价是集合超过一页（100 首）时队列到页尾就停；换来的是「点了立刻出声」且不会有窗口状态错乱。
 *
 * 到 P3 做播放页时再升级成滑动窗口——那时队列面板要显示总数与页码，才有必要把窗口状态引进来。
 */
internal object PhonePlayback {

    /**
     * 起播一组曲目。
     *
     * 只给「要立刻播的那首 + 下一首」取直链，其余先空着在后台补：
     * 在线音源每首取链要过一次隐藏 WebView 的脚本 + 一次探活（约 300ms），
     * 整页 50 首一次性取完要 15 秒以上，用户点完「播放全部」会以为没反应。
     *
     * @return 队列是否已提交给播放器。false 表示没有可播曲目（全是 CUE / 无版权 / 取链全失败）。
     */
    suspend fun start(
        deps: AppUiDependencies,
        tracks: List<Track>,
        startIndex: Int,
        backgroundScope: CoroutineScope,
        shuffle: Boolean = false,
    ): Boolean {
        val playable = tracks.filter(::isPlayable)
        if (playable.isEmpty()) return false
        // 随机起播：起点也从可播曲目里随机挑一首，而不是固定第 0 首。
        // `PlaybackController.setPlayMode(Shuffle)` 只改「下一首跳哪儿」、不动当前播放项，
        // 所以不随机选起点的话，用户点「随机」听到的永远是列表第一首。
        val target = if (shuffle) playable.random() else (tracks.getOrNull(startIndex) ?: return false)
        val playableIndex = playable.indexOfFirst { it.guid == target.guid }.coerceAtLeast(0)

        var queue = withContext(Dispatchers.IO) {
            runCatching { deps.musicRepository.prepareQueue(playable, playableIndex, 2) }
                .getOrDefault(emptyList())
        }
        if (queue.isEmpty()) return false
        // 用 guid 反查，不用 playableIndex：`prepareQueue` 会过滤掉不可播与 CUE 条目，
        // 下标会整体前移。按 guid 找回来才是当前这首在队列里的真实位置。
        var currentIndex = queue.indexOfFirst { it.track.guid == target.guid }
        if (currentIndex < 0) return false

        // 取链有失败率（版权受限、接口抖动、脚本某组 API 挂了）。当前这首要是没拿到链，
        // 再单独补一次——只补这一首，成本 300ms，但能避免「点了 A 歌结果从 B 歌开始放」。
        if (queue[currentIndex].streamUrl.isBlank()) {
            val retry = withContext(Dispatchers.IO) {
                runCatching { deps.musicRepository.prepareQueue(listOf(target), 0, 1) }.getOrNull()
            }
            val url = retry?.firstOrNull()?.streamUrl
            if (!url.isNullOrBlank()) {
                queue = queue.toMutableList().also { it[currentIndex] = it[currentIndex].copy(streamUrl = url) }
            }
        }

        // 当前这首仍然取不到直链时，不能把队列提交给播放器。
        // 播放器拿到空 URL 会抛 IO_FILE_NOT_FOUND，播放页显示「播放失败：音源文件不存在」；
        // 更糟的是 playQueue 只要有曲目就返回非 null，于是这里会被误判成「起播成功」，
        // 调用方的兜底重搜永远不会被触发 —— 用户看到的就是点一次报一次错。
        // 在线音源冷启动后 songIndex 为空，而「最近播放」只有 guid、没有完整曲目元数据，
        // 正是踩在这条路径上。返回 false，让上层去按歌名在当前平台重搜再播。
        if (queue[currentIndex].streamUrl.isBlank()) return false

        val submitted = deps.playbackController.playQueue(queue, currentIndex) != null
        if (!submitted) return false

        // 起播成功后切到随机模式。放后台重试而不是在这里同步等：
        // 队列刚提交时后台还在补直链（见下方 fillUrls），快照 revision 会继续推进，
        // 此刻发出的 setPlayMode 会被播放内核判过期、静默回滚成列表循环——
        // 表现就是「点了随机，歌在播，但播放条上的模式图标还是循环」。
        if (shuffle) backgroundScope.launch { requestShuffleMode(deps) }

        if (queue.any { it.streamUrl.isBlank() }) {
            backgroundScope.launch { fillUrls(deps, queue, currentIndex) }
        }
        return true
    }

    /**
     * 把播放器切成随机模式，确认几次直到真的生效。
     *
     * 随机模式在播放内核里是一次带 revision 校验的异步切换（`PendingShuffleActivation`），
     * 队列稳定前的请求会被判过期。这里每 260~360ms 复核一次，最多 6 次，
     * 等价于用户手动按到模式键——比「一次调用靠运气」可靠得多。
     * 与电视端 `requestShuffleMode()` 同一套策略，两端行为保持一致。
     */
    private suspend fun requestShuffleMode(deps: AppUiDependencies) {
        val controller = deps.playbackController
        repeat(6) { attempt ->
            if (controller.state.value.playMode == PlayMode.Shuffle) return
            controller.setPlayMode(PlayMode.Shuffle)
            delay(if (attempt == 0) 260L else 360L)
        }
        if (controller.state.value.playMode != PlayMode.Shuffle) {
            android.util.Log.w("YueyinPlay", "手机端随机模式未生效：多次切换均被回滚")
        }
    }

    private fun isPlayable(track: Track): Boolean =
        !track.isCue && (track.accessStatus == null || track.accessStatus == 0)

    /**
     * 后台按「下一首优先」的顺序补齐空直链。
     *
     * `patchStreamUrl` 动的是 MediaController，必须在主线程调用；取链放 IO。
     * 每轮显式 `ensureActive()`：`runCatching` 会把 CancellationException 一起吞掉，
     * 不检查的话「新的起播掐掉旧补链」要等几十首补完才生效。
     */
    private suspend fun fillUrls(
        deps: AppUiDependencies,
        queue: List<PlaybackTrack>,
        playIndex: Int,
    ) {
        val order = buildList {
            for (i in (playIndex + 1) until queue.size) if (queue[i].streamUrl.isBlank()) add(i)
            for (i in 0 until playIndex) if (queue[i].streamUrl.isBlank()) add(i)
        }
        for (i in order) {
            currentCoroutineContext().ensureActive()
            val item = queue.getOrNull(i) ?: continue
            val prepared = withContext(Dispatchers.IO) {
                runCatching { deps.musicRepository.prepareQueue(listOf(item.track), 0, 1).firstOrNull() }
                    .getOrNull()
            } ?: continue
            if (prepared.streamUrl.isNotBlank()) {
                deps.playbackController.patchStreamUrl(item.track.guid.value, prepared.streamUrl)
            }
        }
    }
}

/**
 * 随心听。
 *
 * 这里刻意与电视端共用 [RoamPrewarm]：它是「提前备好一包随机曲目 + 第一首直链」的缓存，
 * 与界面无关。共用的好处是用户在手机上点随心听时，如果之前在电视上预热过同一平台，
 * 这一包还能直接用。
 */
internal object PhoneRoam {

    /** 当前曲库标识：换了平台，之前备好的包就作废，免得播到别的源上。 */
    fun sourceKey(deps: AppUiDependencies): String {
        val source = deps.musicRepository.activeProtocolSource ?: return "local"
        return "${source.type}:${source.displayName}"
    }

    private suspend fun build(deps: AppUiDependencies, sourceKey: String, settleMs: Long = 0): RoamPrewarm.Packet? {
        if (settleMs > 0) kotlinx.coroutines.delay(settleMs)
        return withContext(Dispatchers.IO) {
            val picked = runCatching {
                deps.musicRepository.allTracks(1).items.shuffled().take(50)
            }.getOrDefault(emptyList())
            if (picked.isEmpty()) return@withContext null
            val queue = deps.musicRepository.prepareQueue(picked, 0, 2)
            if (queue.isEmpty()) null else RoamPrewarm.Packet(queue, sourceKey, System.currentTimeMillis())
        }
    }

    /** 空闲时预热下一包。失败静默——预热是优化，不该给用户看到错误。 */
    fun prepareInBackground(deps: AppUiDependencies, scope: CoroutineScope) {
        val key = sourceKey(deps)
        RoamPrewarm.prepare(key, scope) { build(deps, key) }
    }

    /**
     * 点随心听。
     *
     * 有预热包就直接播（点下去立刻出声），没有就现场取：随机 50 首 → 只取首两首直链 →
     * 提交队列 → 播起来再备下一包。
     */
    fun play(
        deps: AppUiDependencies,
        scope: CoroutineScope,
        onStarted: () -> Unit,
        onFailed: () -> Unit,
        onFinished: () -> Unit,
    ) {
        val key = sourceKey(deps)
        val ready = RoamPrewarm.take(key)
        if (ready != null) {
            val ok = deps.playbackController.playQueue(ready.queue, 0) != null
            if (ok) {
                onStarted()
                scope.launch { fillAfterStart(deps, ready.queue, 0) }
                RoamPrewarm.prepare(key, scope) { build(deps, key, settleMs = 1_500) }
            } else {
                onFailed()
            }
            onFinished()
            return
        }
        scope.launch {
            try {
                val packet = build(deps, key)
                if (packet == null) {
                    onFailed()
                    return@launch
                }
                if (deps.playbackController.playQueue(packet.queue, 0) != null) {
                    onStarted()
                    fillAfterStart(deps, packet.queue, 0)
                    RoamPrewarm.prepare(key, scope) { build(deps, key, settleMs = 1_500) }
                } else {
                    onFailed()
                }
            } finally {
                onFinished()
            }
        }
    }

    private suspend fun fillAfterStart(
        deps: AppUiDependencies,
        queue: List<PlaybackTrack>,
        index: Int,
    ) {
        val order = (index until queue.size).filter { queue[it].streamUrl.isBlank() }
        for (i in order) {
            currentCoroutineContext().ensureActive()
            val item = queue.getOrNull(i) ?: continue
            val prepared = withContext(Dispatchers.IO) {
                runCatching { deps.musicRepository.prepareQueue(listOf(item.track), 0, 1).firstOrNull() }
                    .getOrNull()
            } ?: continue
            if (prepared.streamUrl.isNotBlank()) {
                deps.playbackController.patchStreamUrl(item.track.guid.value, prepared.streamUrl)
            }
        }
    }
}
