package com.yueyin.tv.phone

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yueyin.tv.core.cast.CastSource
import com.yueyin.tv.core.playback.PlaybackUiState
import kotlinx.coroutines.delay

/**
 * 投播跟随：投播期间只要「当前曲目」变了，就把新曲目重新投到音响；
 * 退出投播时把手机接回来接着放。
 *
 * 为什么挂在壳层而不是播放页：用户换歌最常见的路径是**回到列表里点另一首**，
 * 那时播放页已经不在组合树上，页内的 `LaunchedEffect` 根本不会运行——
 * 表现就是「手机放手机的、音响放音响的」。放在壳层后，不论从哪个页面换歌都能覆盖。
 */
@Composable
internal fun PhoneCastFollow(playback: PlaybackUiState) {
    val deps = LocalPhoneDeps.current
    val context = LocalContext.current
    val castState by deps.castController.state.collectAsStateWithLifecycle()
    var lastGuid by remember { mutableStateOf<String?>(null) }
    var wasActive by remember { mutableStateOf(false) }
    // 持续记住音响播到哪：stopCast 会把 playback 状态一起清零，退出时就没得用了。
    var lastCastPositionMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(castState.hasActive, castState.playback.positionMs) {
        if (castState.hasActive) lastCastPositionMs = castState.playback.positionMs
    }

    LaunchedEffect(castState.hasActive, playback.mediaId) {
        if (!castState.hasActive) {
            if (wasActive) {
                wasActive = false
                lastGuid = null
                // 退出投播：先放开本机起播限制（否则按播放也没声音），
                // 再从音响停下的位置接着放——直接从本机暂停点续播会跳回投播开始的位置。
                deps.playbackController.releaseCastSuppression()
                if (lastCastPositionMs > 0L) {
                    runCatching { deps.playbackController.seekTo(lastCastPositionMs) }
                }
                runCatching { deps.playbackController.playPause() }
                lastCastPositionMs = 0L
            }
            return@LaunchedEffect
        }

        if (!wasActive) {
            // 刚进入投播：castTo 已经投过当前这一首，接着换曲才需要重投。
            wasActive = true
            lastGuid = playback.mediaId
            return@LaunchedEffect
        }

        val currentGuid = playback.mediaId
        val previousGuid = lastGuid
        if (previousGuid == null) {
            lastGuid = currentGuid
            return@LaunchedEffect
        }
        if (currentGuid == previousGuid) return@LaunchedEffect
        lastGuid = currentGuid

        // 先静音：选歌会让本机立刻起播，不能等换源完成才停。
        deps.playbackController.pauseForCast()

        // 等新曲目的地址真正就位（漫游模式还要联网取链），最多 3 秒。
        var target = deps.playbackController.currentCastSource()
        var waited = 0L
        while (waited < 3_000L && (target == null || target.first.guid.value == previousGuid)) {
            delay(200)
            waited += 200
            target = deps.playbackController.currentCastSource()
        }
        val ready = target
        if (ready == null || ready.first.guid.value == previousGuid) {
            Toast.makeText(context, "换曲超时，没有拿到新的播放地址", Toast.LENGTH_SHORT).show()
            return@LaunchedEffect
        }
        val (track, uri, _) = ready
        android.util.Log.i("YueyinCast", "follow -> ${track.title} / ${uri.take(64)}")
        val result = deps.castController.replaceSource(
            CastSource(
                title = track.title,
                artist = track.artistName,
                album = track.albumName,
                mimeType = castMimeOf(track.audioFormat),
                uri = uri,
                durationMs = track.durationMs,
            ),
        )
        if (result.isFailure) {
            // 静默失败会让用户以为按钮没反应，至少要说清是被设备拒绝还是超时。
            Toast.makeText(
                context,
                "换曲投播失败：${result.exceptionOrNull()?.message.orEmpty()}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
