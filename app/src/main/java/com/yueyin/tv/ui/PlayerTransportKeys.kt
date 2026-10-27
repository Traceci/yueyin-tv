package com.yueyin.tv.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlin.math.abs
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 方向键单击一次对应的跳转步进。 */
internal const val TRANSPORT_SEEK_STEP_MS = 10_000L

/** 连按两次（同方向）判定窗口。 */
private const val DOUBLE_PRESS_WINDOW_MS = 420L

/** 长按持续快进/快退的节拍。 */
private const val HOLD_SEEK_INTERVAL_MS = 240L

/** 长按多久之后开始加速。 */
private const val HOLD_ACCELERATE_AFTER_MS = 1_200L

/** 长按加速后的最大单步。 */
private const val HOLD_MAX_STEP_MS = 60_000L

internal data class PlayerTransportActions(
    val seekBy: (Long) -> Unit,
    /** 当前曲目是否可 seek；不可 seek 时给出提示而不是静默失败。 */
    val isSeekable: () -> Boolean,
    val next: () -> Unit,
    val previousTrack: () -> Unit,
    val playPause: () -> Unit,
    val revealChrome: () -> Unit,
    val announce: (String) -> Unit,
    val isPlaying: () -> Boolean,
)

/**
 * 遥控器快捷键状态机。
 *
 * 沉浸态（控制层隐藏）下的键位：
 *  - ← 单击 = 快退 10 秒，连按两次 = 上一曲，长按 = 持续快退（自动加速）
 *  - → 单击 = 快进 10 秒，连按两次 = 下一曲，长按 = 持续快进（自动加速）
 *  - 确定 = 播放 / 暂停
 *  - ↑ / ↓ = 呼出控制栏
 *
 * 硬件媒体键（播放/暂停/上一曲/下一曲）在任何界面都生效。
 */
internal class PlayerTransportKeyState(private val scope: CoroutineScope) {
    private var lastLeftDownAt = 0L
    private var lastRightDownAt = 0L
    private var holdJob: Job? = null
    private var holdDirection = 0

    fun stopHold() {
        holdJob?.cancel()
        holdJob = null
        holdDirection = 0
    }

    private fun startHold(direction: Int, actions: PlayerTransportActions) {
        if (holdDirection == direction) return
        stopHold()
        holdDirection = direction
        holdJob = scope.launch {
            var elapsed = 0L
            var step = TRANSPORT_SEEK_STEP_MS
            while (true) {
                delay(HOLD_SEEK_INTERVAL_MS)
                elapsed += HOLD_SEEK_INTERVAL_MS
                if (elapsed >= HOLD_ACCELERATE_AFTER_MS && step < HOLD_MAX_STEP_MS) {
                    step = (step + TRANSPORT_SEEK_STEP_MS).coerceAtMost(HOLD_MAX_STEP_MS)
                }
                actions.seekBy(direction * step)
                actions.announce(seekLabel(direction * step))
            }
        }
    }

    fun handle(
        event: KeyEvent,
        immersive: Boolean,
        actions: PlayerTransportActions,
    ): Boolean {
        val isDown = event.type == KeyEventType.KeyDown
        if (handleMediaKey(event, isDown, actions)) return true
        if (!immersive) {
            if (!isDown && isHoldKey(event.key)) stopHold()
            return false
        }
        when (event.key) {
            Key.DirectionLeft -> {
                if (!isDown) {
                    if (holdDirection == -1) stopHold()
                    return true
                }
                if (!actions.isSeekable()) {
                    // 不可 seek 仍要保留「连按两次切上一曲」，只是不执行跳转
                    val repeat = event.nativeKeyEvent.repeatCount
                    if (repeat == 0) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (lastLeftDownAt > 0L && now - lastLeftDownAt <= DOUBLE_PRESS_WINDOW_MS) {
                            lastLeftDownAt = 0L
                            actions.previousTrack()
                            actions.announce("上一曲")
                        } else {
                            lastLeftDownAt = now
                            actions.announce("当前曲目不支持快退")
                        }
                    }
                    return true
                }
                if (!isDown) {
                    if (holdDirection == -1) stopHold()
                    return true
                }
                val repeat = event.nativeKeyEvent.repeatCount
                if (repeat > 0 || holdDirection == -1) {
                    startHold(-1, actions)
                    return true
                }
                val now = android.os.SystemClock.elapsedRealtime()
                if (lastLeftDownAt > 0L && now - lastLeftDownAt <= DOUBLE_PRESS_WINDOW_MS) {
                    lastLeftDownAt = 0L
                    stopHold()
                    actions.previousTrack()
                    actions.announce("上一曲")
                } else {
                    lastLeftDownAt = now
                    actions.seekBy(-TRANSPORT_SEEK_STEP_MS)
                    actions.announce(seekLabel(-TRANSPORT_SEEK_STEP_MS))
                }
                return true
            }

            Key.DirectionRight -> {
                if (!isDown) {
                    if (holdDirection == 1) stopHold()
                    return true
                }
                if (!actions.isSeekable()) {
                    // 同上：连按两次仍切下一曲
                    val repeat = event.nativeKeyEvent.repeatCount
                    if (repeat == 0) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (lastRightDownAt > 0L && now - lastRightDownAt <= DOUBLE_PRESS_WINDOW_MS) {
                            lastRightDownAt = 0L
                            actions.next()
                            actions.announce("下一曲")
                        } else {
                            lastRightDownAt = now
                            actions.announce("当前曲目不支持快进")
                        }
                    }
                    return true
                }
                if (!isDown) {
                    if (holdDirection == 1) stopHold()
                    return true
                }
                val repeat = event.nativeKeyEvent.repeatCount
                if (repeat > 0 || holdDirection == 1) {
                    startHold(1, actions)
                    return true
                }
                val now = android.os.SystemClock.elapsedRealtime()
                if (lastRightDownAt > 0L && now - lastRightDownAt <= DOUBLE_PRESS_WINDOW_MS) {
                    lastRightDownAt = 0L
                    stopHold()
                    actions.next()
                    actions.announce("下一曲")
                } else {
                    lastRightDownAt = now
                    actions.seekBy(TRANSPORT_SEEK_STEP_MS)
                    actions.announce(seekLabel(TRANSPORT_SEEK_STEP_MS))
                }
                return true
            }

            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                if (isDown) {
                    actions.playPause()
                    actions.announce(if (actions.isPlaying()) "暂停" else "播放")
                }
                return true
            }

            Key.DirectionUp, Key.DirectionDown -> {
                if (isDown) actions.revealChrome()
                return true
            }

            else -> return false
        }
    }

    private fun handleMediaKey(
        event: KeyEvent,
        isDown: Boolean,
        actions: PlayerTransportActions,
    ): Boolean {
        when (event.nativeKeyEvent.keyCode) {
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            AndroidKeyEvent.KEYCODE_HEADSETHOOK,
            -> {
                if (isDown) {
                    actions.playPause()
                    actions.announce(if (actions.isPlaying()) "暂停" else "播放")
                }
                return true
            }

            AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (isDown) {
                    if (!actions.isPlaying()) actions.playPause()
                    actions.announce("播放")
                }
                return true
            }

            AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (isDown) {
                    if (actions.isPlaying()) actions.playPause()
                    actions.announce("暂停")
                }
                return true
            }

            AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (isDown) {
                    actions.next()
                    actions.announce("下一曲")
                }
                return true
            }

            AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (isDown) {
                    actions.previousTrack()
                    actions.announce("上一曲")
                }
                return true
            }

            else -> return false
        }
    }

    private fun isHoldKey(key: Key): Boolean =
        key == Key.DirectionLeft || key == Key.DirectionRight

    private fun seekLabel(deltaMs: Long): String {
        val seconds = kotlin.math.abs(deltaMs) / 1000
        return if (deltaMs >= 0) "快进 ${seconds} 秒" else "快退 ${seconds} 秒"
    }
}

/** 遥控器快捷键的屏幕反馈条。 */
@Composable
internal fun PlayerTransportOsd(message: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = fadeIn(tween(90)) + scaleIn(initialScale = 0.9f, animationSpec = tween(200, easing = LinearEasing)),
        exit = fadeOut(tween(260)),
    ) {
        val shape = RoundedCornerShape(999.dp)
        Box(
            Modifier
                .background(FnColors.Background.copy(alpha = 0.82f), shape)
                .border(1.dp, FnColors.Coral.copy(alpha = 0.55f), shape)
                .padding(horizontal = 22.dp, vertical = 11.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(4.dp)
                        .background(FnColors.Coral, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = message.orEmpty(),
                    color = FnColors.Text,
                    fontSize = 17.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
