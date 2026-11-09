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
            if (!server.start()) error("无法在局域网开放端口")
            val ip = LanMediaServer.localIpv4() ?: error("没有可用的局域网地址，请先连上 Wi-Fi")
            val path = server.register(source)
            val url = "http://$ip:${server.port}$path"
            DlnaControl.setAvTransportUri(device, url, buildDidlMetadata(source, url))
            DlnaControl.play(device)
            if (positionMs > 1_500L) {
                // 定位失败不影响「已经在放」，只是会从 0 开始。
                runCatching { DlnaControl.seek(device, positionMs) }
            }
            _state.update { it.copy(connecting = false, casting = true) }
        }.onFailure { error ->
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

    suspend fun pauseCast() {
        val device = _state.value.active ?: return
        withContext(Dispatchers.IO) { runCatching { DlnaControl.pause(device) } }
    }

    suspend fun stopCast() {
        val device = _state.value.active
        withContext(Dispatchers.IO) {
            if (device != null) runCatching { DlnaControl.stop(device) }
            server.stop()
        }
        _state.update { it.copy(active = null, casting = false, connecting = false) }
    }

    fun release() {
        scanJob?.cancel()
        server.stop()
        scope.cancel()
    }
}
