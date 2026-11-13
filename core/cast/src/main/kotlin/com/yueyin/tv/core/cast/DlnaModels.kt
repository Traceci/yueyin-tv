package com.yueyin.tv.core.cast

/**
 * 一个可投播的 DLNA 渲染器（电视 / 音箱 / 功放）。
 *
 * 只保留推送单曲需要的最小字段：AVTransport 控制地址是必须的，
 * RenderingControl 暂不参与（P2 不做音量回传），留着备用。
 */
data class DlnaDevice(
    val usn: String,
    val name: String,
    val modelName: String?,
    val location: String,
    val controlUrl: String,
    val renderingControlUrl: String? = null,
) {
    val subtitle: String
        get() = listOfNotNull(modelName?.takeIf { it.isNotBlank() }, "DLNA").joinToString(" · ")
}

/** 投播界面状态。错误一律留在 state 里让 UI 显示，不抛给调用方。 */
data class CastUiState(
    val scanning: Boolean = false,
    val devices: List<DlnaDevice> = emptyList(),
    val active: DlnaDevice? = null,
    val casting: Boolean = false,
    val connecting: Boolean = false,
    val error: String? = null,
    /** 当前投出去的这一条，用于 UI 显示曲名/时长。 */
    val current: CastSource? = null,
    /** 渲染器上报的播放状态。遥控器模型下，手机端进度与播放键都看它。 */
    val playback: CastPlaybackState = CastPlaybackState(),
) {
    val hasActive: Boolean get() = active != null && casting
}

/**
 * 渲染器上报的播放状态（遥控器模型的「回传」）。
 *
 * DLNA 是「手机把地址交给音响、音响自己播」，本机播放器并不知道音响播到哪，
 * 所以投播期间进度、时长、播放/暂停全部以这里为准。
 */
data class CastPlaybackState(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** TRANSITIONING 也算在播，避免切歌瞬间按钮闪一下。 */
    val playing: Boolean = false,
    /** 原始 TransportState，出问题时便于排查。 */
    val transport: String = "STOPPED",
    /** 最近一次轮询是否成功；连续失败说明设备掉线。 */
    val reachable: Boolean = true,
)

/**
 * 要投出去的一条音频。
 *
 * [uri] 是「手机自己能不能读到」的地址：`content://`（本地/文件夹）、
 * `http://127.0.0.1:port/...`（协议平台代理）、或在线直链。
 * 真正写给电视的是局域网中转地址，由 [LanMediaServer] 生成——
 * 电视读不到 content://，也读不到 127.0.0.1。
 */
data class CastSource(
    val title: String,
    val artist: String?,
    val album: String?,
    val mimeType: String,
    val uri: String,
    val durationMs: Long? = null,
)
