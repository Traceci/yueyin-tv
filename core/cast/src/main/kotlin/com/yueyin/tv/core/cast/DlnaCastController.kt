package com.yueyin.tv.core.cast

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 投播总控：发现设备 → 建局域网中转 → SetAVTransportURI + Play。
 *
 * 本版（P2）只投「当前这一首」：不做队列同步、不做音量/进度回传。
 * 停止投播时关掉中转服务，本机播放由 UI 侧决定是否继续。
 */
class DlnaCastController(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = LanMediaServer(appContext)
    private val _state = MutableStateFlow(CastUiState())
    val state: StateFlow<CastUiState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var pollJob: Job? = null

    fun startScan() {
        if (scanJob?.isActive == true) return
        _state.update { it.copy(scanning = true, error = null) }
        scanJob = scope.launch {
            val found = runCatching { DlnaDiscovery.discover(appContext) }
                .getOrElse { emptyList() }
            _state.update {
                it.copy(
                    scanning = false,
                    devices = found,
                    error = if (found.isEmpty()) "没有发现可投播的设备，确认手机与设备在同一 Wi-Fi" else null,
                )
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _state.update { it.copy(scanning = false) }
    }

    suspend fun castTo(
        device: DlnaDevice,
        source: CastSource,
        positionMs: Long = 0L,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        _state.update { it.copy(connecting = true, active = device, error = null) }
        runCatching {
            if (source.uri.isBlank()) error("当前曲目还没有可播放的地址")
            // 分段计时：投播慢到底慢在「我们准备地址」还是「设备来拉流」，日志里一眼能分出来。
            val t0 = System.currentTimeMillis()
            fun lap(tag: String) {
                android.util.Log.i("YueyinCast", "castTo $tag +${System.currentTimeMillis() - t0}ms")
            }
            if (!server.start()) error("无法在局域网开放端口")
            val ip = LanMediaServer.localIpv4() ?: error("没有可用的局域网地址，请先连上 Wi-Fi")
            val path = server.register(source)
            val url = "http://$ip:${server.port}$path"
            lap("server")
            DlnaControl.setAvTransportUri(device, url, buildDidlMetadata(source, url))
            lap("setUri")
            DlnaControl.play(device)
            lap("play")
            android.util.Log.i("YueyinCast", "castTo url=$url pos=$positionMs")
            _state.update { it.copy(connecting = false, casting = true, current = source) }
            // 投出去之后开始轮询：遥控器模型下，手机端进度与播放状态都来自这里。
            startPolling(device)
            // 起始位置放到后台校准：SetURI 之后设备还在 TRANSITIONING，立刻 Seek 会被拒；
            // 但绝不能为了等它就卡住 castTo——那会让「点了投播」看起来像没反应。
            if (positionMs > 1_500L) {
                scope.launch { applyStartPosition(device, positionMs) }
            }
            Unit
        }.onFailure { error ->
            android.util.Log.e("YueyinCast", "castTo failed: ${error.message}", error)
            server.stop()
            _state.update {
                it.copy(
                    connecting = false,
                    casting = false,
                    active = null,
                    error = error.message ?: "投播失败",
                )
            }
        }
    }

    /**
     * 后台校准起始位置。
     *
     * 只在后台跑，不影响投播返回与出声时机。两个必须守住的点：
     * - 不能太早：刚 SetAVTransportURI 时设备还在 TRANSITIONING，Seek 会被拒（701），
     *   或者让它从头重新缓冲，用户就会觉得「投播要等好几秒」；
     * - 不能因为读不到位置就放弃：不少设备 `GetPositionInfo` 返回 NOT_IMPLEMENTED，
     *   位置恒为 0，若据此跳过 Seek，首次投播就永远从头播（实测就是这样）。
     */
    private suspend fun applyStartPosition(device: DlnaDevice, positionMs: Long) {
        // 等设备离开 TRANSITIONING（约 2 秒上限）；状态查询本身不支持时给 500ms 余量再走。
        var ready = false
        for (attempt in 0 until 8) {
            val state = runCatching { DlnaControl.transportState(device) }.getOrDefault("")
            if (state == "PLAYING" || state == "PAUSED_PLAYBACK") {
                ready = true
                break
            }
            // 设备自己停了，说明这轮投播没起来，再去 Seek 只会更乱。
            if (state == "STOPPED") return
            delay(250)
        }
        if (!ready) delay(500)

        // 无条件先定位一次：设备可能在 0 位置已经播了一小段，也可能压根报不出位置。
        val first = runCatching { DlnaControl.seek(device, positionMs) }
        if (first.isFailure) {
            // 一次被拒（还在过渡）就补一次，间隔放到 600ms；再不行就接受从头播。
            delay(600)
            runCatching { DlnaControl.seek(device, positionMs) }
            return
        }

        // 只有确实读到位置、且偏差明显时才补第二次——读不到就不打扰，避免反复打断播放。
        delay(400)
        val actual = runCatching { DlnaControl.position(device) }.getOrDefault(0L to 0L).first
        if (actual > 0L && kotlin.math.abs(actual - positionMs) > 3_000L) {
            runCatching { DlnaControl.seek(device, positionMs) }
        }
    }

    suspend fun pauseCast() {
        val device = _state.value.active ?: return
        withContext(Dispatchers.IO) { runCatching { DlnaControl.pause(device) } }
        refreshPlaybackOnce(device)
    }

    /** 遥控：继续播放。投播中手机端的「播放」键走这里，不再驱动本机播放器。 */
    suspend fun resumeCast() {
        val device = _state.value.active ?: return
        withContext(Dispatchers.IO) { runCatching { DlnaControl.play(device) } }
        refreshPlaybackOnce(device)
    }

    /** 遥控：跳到指定位置（拖动进度条）。 */
    suspend fun seekCast(positionMs: Long) {
        val device = _state.value.active ?: return
        withContext(Dispatchers.IO) { runCatching { DlnaControl.seek(device, positionMs) } }
        refreshPlaybackOnce(device)
    }

    /**
     * 换一首投出去（投播中切歌）。
     *
     * 必须先 Stop 再 SetAVTransportURI：不少渲染器在播放中直接改地址会返回
     * 701（Transition not available），用户看到的就是「切了歌，音响还在放上一首」。
     */
    suspend fun replaceSource(source: CastSource): Result<Unit> {
        val device = _state.value.active
            ?: return Result.failure(IllegalStateException("投播设备已断开"))
        withContext(Dispatchers.IO) { runCatching { DlnaControl.stop(device) } }
        return castTo(device, source, positionMs = 0L)
    }

    /**
     * 轮询渲染器状态（正常 1 秒一次）。
     *
     * 连续 3 次失败判定设备掉线，之后放慢到 5 秒继续试——设备重新上线能自己恢复，
     * 不必让用户退出再投一次。
     */
    private fun startPolling(device: DlnaDevice) {
        pollJob?.cancel()
        pollJob = scope.launch {
            var failures = 0
            while (isActive) {
                val snapshot = runCatching { fetchSnapshot(device) }.getOrNull()
                if (snapshot != null) {
                    failures = 0
                    _state.update { it.copy(playback = snapshot.toPlayback(reachable = true)) }
                } else {
                    failures++
                    if (failures >= 3) {
                        _state.update { it.copy(playback = it.playback.copy(reachable = false)) }
                    }
                }
                delay(if (failures >= 3) 5_000L else 1_000L)
            }
        }
    }

    /** 动作后立刻取一次，别让 UI 等下一轮轮询（否则按暂停后按钮要一秒才变）。 */
    private suspend fun refreshPlaybackOnce(device: DlnaDevice) {
        val snapshot = withContext(Dispatchers.IO) { runCatching { fetchSnapshot(device) }.getOrNull() } ?: return
        _state.update { it.copy(playback = snapshot.toPlayback(reachable = true)) }
    }

    private fun fetchSnapshot(device: DlnaDevice): TransportSnapshot {
        val transport = DlnaControl.transportState(device)
        val (position, duration) = DlnaControl.position(device)
        return TransportSnapshot(transport, position, duration)
    }

    private data class TransportSnapshot(
        val transport: String,
        val positionMs: Long,
        val durationMs: Long,
    ) {
        fun toPlayback(reachable: Boolean) = CastPlaybackState(
            positionMs = positionMs,
            durationMs = durationMs,
            playing = transport == "PLAYING" || transport == "TRANSITIONING",
            transport = transport,
            reachable = reachable,
        )
    }

    suspend fun stopCast() {
        val device = _state.value.active
        pollJob?.cancel()
        pollJob = null
        withContext(Dispatchers.IO) {
            if (device != null) runCatching { DlnaControl.stop(device) }
            server.stop()
        }
        _state.update {
            it.copy(
                active = null,
                casting = false,
                connecting = false,
                current = null,
                playback = CastPlaybackState(),
            )
        }
    }

    fun release() {
        scanJob?.cancel()
        pollJob?.cancel()
        server.stop()
        scope.cancel()
    }
}
