package com.yueyin.tv.ui

import com.yueyin.tv.core.model.PlaybackTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 随心听的「预备包」。
 *
 * 本地 / NAS 曲库取链只是拼个 URL，点下去立刻就能播；在线音源不一样——每首歌都要
 * 过一次隐藏 WebView 里的取链脚本再做一次 1KB 探活，一首 1~3 秒。点下去才现场取链，
 * 就一定会有「每次点都在准备中」的等待。
 *
 * 所以这里把「随机挑 50 首 + 取好第一首直链」提前备好放在一边：点的时候直接拿去播，
 * 播起来之后立刻在后台再备下一包，下一次点击又是即时的。
 */
internal object RoamPrewarm {

    class Packet(
        val queue: List<PlaybackTrack>,
        /** 建包时的曲库标识（类型 + 显示名），换了曲库这包就作废，避免播到别的源上。 */
        val sourceKey: String,
        val at: Long,
    )

    private const val MAX_AGE_MS = 10 * 60_000L

    @Volatile
    private var packet: Packet? = null

    private var job: Job? = null

    /** 取走一包（一次性）；曲库变了或放太久就丢弃。 */
    @Synchronized
    fun take(sourceKey: String): Packet? {
        val ready = packet ?: return null
        packet = null
        if (ready.sourceKey != sourceKey) return null
        if (System.currentTimeMillis() - ready.at > MAX_AGE_MS) return null
        return ready
    }

    /** 后台备一包；已经在备/已有备好的就不重复做。换了曲库则丢弃旧包重备。 */
    @Synchronized
    fun prepare(sourceKey: String, scope: CoroutineScope, build: suspend () -> Packet?) {
        if (packet?.sourceKey != null && packet?.sourceKey != sourceKey) packet = null
        if (packet != null || job?.isActive == true) return
        job = scope.launch {
            val built = runCatching { build() }.getOrNull() ?: return@launch
            if (built.queue.isNotEmpty()) packet = built
        }
    }

    @Synchronized
    fun clear() {
        packet = null
        job?.cancel()
        job = null
    }
}
