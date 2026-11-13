package com.yueyin.tv.phone

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yueyin.tv.core.cast.CastSource
import com.yueyin.tv.core.playback.PlaybackUiState
import kotlinx.coroutines.delay

private const val TAG = "YueyinCast"

/**
 * 投播跟随：投播期间只要当前曲目变了，就把新曲目重新投给渲染器；退出投播时把本机接回来。
 *
 * 必须挂在**壳层**（PhoneApp）而不是播放页：用户换歌最常见的路径是回到曲库点另一首，
 * 那时播放页已经不在组合树上，页内的 LaunchedEffect 根本不会运行——表现就是
 * 「手机放手机的、音响放音响的」。这是昨晚调试里反复出现、加了又撤的老问题。
 *
 * 状态机只有三个变量，且与 core:cast 的单一会话状态对齐：
 * - wasActive：区分「刚进入投播」（castTo 已经投过当前曲）与「投播中切歌」；
 * - lastGuid：上一次的 mediaId，避免同一首歌被重复重投；
 * - lastCastPositionMs：持续记住设备进度，供退出投播时本机续播。
 */
@Composable
internal fun PhoneCastFollow(playback: PlaybackUiState) {
    val deps = LocalPhoneDeps.current
    val castState by deps.castController.state.collectAsStateWithLifecycle()
    var lastGuid by remember { mutableStateOf<String?>(null) }
    var wasActive by remember { mutableStateOf(false) }
    var lastCastPositionMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(castState.hasActive, castState.playback.positionMs) {
        if (castState.hasActive) lastCastPositionMs = castState.playback.positionMs
    }

    LaunchedEffect(castState.hasActive, playback.mediaId) {
        if (!castState.hasActive) {
            if (wasActive) {
                wasActive = false
                lastGuid = null
                // 退出投播：先放开抑制，再从音响停下的位置接着放本机，
                // 否则会跳回投播开始的位置，或者干脆按播放没反应。
                deps.playbackController.releaseCastSuppression()
                if (lastCastPositionMs > 0L) {
                    deps.playbackController.seekTo(lastCastPositionMs)
                }
                deps.playbackController.playPause()
                lastCastPositionMs = 0L
            }
            return@LaunchedEffect
        }

        if (!wasActive) {
            // 刚进入投播：castTo 已经投过当前这一首，只有后续换曲才需要重投。
            wasActive = true
            lastGuid = playback.mediaId
            return@LaunchedEffect
        }

        val currentGuid = playback.mediaId
        val previousGuid = lastGuid
        lastGuid = currentGuid
        if (previousGuid == null || currentGuid.isBlank() || currentGuid == previousGuid) {
            return@LaunchedEffect
        }

        // 切歌：抑制位保证本机不会跟着出声，这里再调一次是幂等保险。
        deps.playbackController.pauseForCast()
        // 新曲目的地址可能还没就位（漫游要联网取链），有界等待，避免
        // 「当前曲目还没有可播放的地址」把重投直接打断。
        var target = deps.playbackController.currentCastSource()
        var waited = 0L
        while (waited < 3_000L &&
            (target == null || target.first.guid.value == previousGuid || target.second.isBlank())
        ) {
            delay(200)
            waited += 200
            target = deps.playbackController.currentCastSource()
        }
        val ready = target
        if (ready == null || ready.second.isBlank() || ready.first.guid.value == previousGuid) {
            Log.w(TAG, "follow timeout: no playable uri for $currentGuid")
            return@LaunchedEffect
        }
        val (track, uri, _) = ready
        Log.i(TAG, "follow -> ${track.title} / ${uri.take(64)}")
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
            Log.e(TAG, "follow failed: ${result.exceptionOrNull()?.message}")
        }
    }
}
