package com.yueyin.tv.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
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

/**
 * 快捷键反馈里用的图标。
 *
 * 与播放按钮那套 `TransportGlyph` 分开：那套只服务控制栏按钮（4 个图形），
 * 这里要额外区分快进/快退，直接复用会牵动按钮的穷尽 `when`。
 */
internal enum class TransportOsdGlyph { Play, Pause, Previous, Next, Forward, Rewind }

/** 一条快捷键反馈：图标与文字至少给一个，纯文字用于「不支持快进」这类说明。 */
internal data class TransportOsd(
    val glyph: TransportOsdGlyph? = null,
    val text: String? = null,
)

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
    val announce: (TransportOsd) -> Unit,
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
                actions.announce(seekOsd(direction * step))
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
                            actions.announce(TransportOsd(glyph = TransportOsdGlyph.Previous))
                        } else {
                            lastLeftDownAt = now
                            actions.announce(TransportOsd(text = "当前曲目不支持快退"))
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
                    actions.announce(TransportOsd(glyph = TransportOsdGlyph.Previous))
                } else {
                    lastLeftDownAt = now
                    actions.seekBy(-TRANSPORT_SEEK_STEP_MS)
                    actions.announce(seekOsd(-TRANSPORT_SEEK_STEP_MS))
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
                            actions.announce(TransportOsd(glyph = TransportOsdGlyph.Next))
                        } else {
                            lastRightDownAt = now
                            actions.announce(TransportOsd(text = "当前曲目不支持快进"))
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
                    actions.announce(TransportOsd(glyph = TransportOsdGlyph.Next))
                } else {
                    lastRightDownAt = now
                    actions.seekBy(TRANSPORT_SEEK_STEP_MS)
                    actions.announce(seekOsd(TRANSPORT_SEEK_STEP_MS))
                }
                return true
            }

            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                if (isDown) {
                    actions.playPause()
                    actions.announce(TransportOsd(glyph = if (actions.isPlaying()) TransportOsdGlyph.Pause else TransportOsdGlyph.Play))
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
                    actions.announce(TransportOsd(glyph = if (actions.isPlaying()) TransportOsdGlyph.Pause else TransportOsdGlyph.Play))
                }
                return true
            }

            AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (isDown) {
                    if (!actions.isPlaying()) actions.playPause()
                    actions.announce(TransportOsd(glyph = TransportOsdGlyph.Play))
                }
                return true
            }

            AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (isDown) {
                    if (actions.isPlaying()) actions.playPause()
                    actions.announce(TransportOsd(glyph = TransportOsdGlyph.Pause))
                }
                return true
            }

            AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (isDown) {
                    actions.next()
                    actions.announce(TransportOsd(glyph = TransportOsdGlyph.Next))
                }
                return true
            }

            AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (isDown) {
                    actions.previousTrack()
                    actions.announce(TransportOsd(glyph = TransportOsdGlyph.Previous))
                }
                return true
            }

            else -> return false
        }
    }

    private fun isHoldKey(key: Key): Boolean =
        key == Key.DirectionLeft || key == Key.DirectionRight

    /**
     * 快进/快退反馈：只给图标，不带「10 秒」数字。
     *
     * 步长是固定的 [TRANSPORT_SEEK_STEP_MS]，长按时会连续加速，写死数字反而会误导；
     * 图标本身已经能表达方向。
     */
    private fun seekOsd(deltaMs: Long): TransportOsd =
        TransportOsd(glyph = if (deltaMs >= 0) TransportOsdGlyph.Forward else TransportOsdGlyph.Rewind)
}

/**
 * 遥控器快捷键的屏幕反馈。
 *
 * 以前是一条「黑底 + 珊瑚描边」的文字胶囊，左侧还有一条 4dp 竖条；竖条只在左边，
 * 文字因此永远偏右、不在胶囊中间。现在改成纯图标 + 很淡的投影：无底色、无边框，
 * 图标与文字水平居中，浅色封面上靠投影保证轮廓可读。
 */
@Composable
internal fun PlayerTransportOsd(osd: TransportOsd?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = osd != null,
        modifier = modifier,
        enter = fadeIn(tween(90)) + scaleIn(initialScale = 0.9f, animationSpec = tween(200, easing = LinearEasing)),
        exit = fadeOut(tween(260)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            val glyph = osd?.glyph
            if (glyph != null) {
                Canvas(Modifier.size(46.dp)) {
                    // 很淡的暗色投影：同一图形先向下偏移画一遍，浅色封面上海报模式下也能看清。
                    drawOsdGlyph(glyph, Color.Black.copy(alpha = 0.32f), offsetY = 2.dp.toPx())
                    drawOsdGlyph(glyph, Color.White)
                }
                if (!osd.text.isNullOrBlank()) Spacer(Modifier.width(12.dp))
            }
            val label = osd?.text
            if (!label.isNullOrBlank()) {
                Text(
                    text = label,
                    color = Color.White,
                    fontSize = 19.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = TextStyle(
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.42f),
                            offset = Offset(0f, 2f),
                            blurRadius = 6f,
                        ),
                    ),
                )
            }
        }
    }
}

/** 在 [DrawScope] 里画一个反馈图标；[offsetY] 用于叠投影。 */
private fun DrawScope.drawOsdGlyph(
    glyph: TransportOsdGlyph,
    color: Color,
    offsetY: Float = 0f,
) {
    val w = size.width
    val h = size.height
    fun x(fraction: Float) = fraction * w
    fun y(fraction: Float) = fraction * h + offsetY
    when (glyph) {
        TransportOsdGlyph.Play -> drawPath(
            Path().apply {
                moveTo(x(0.30f), y(0.18f))
                lineTo(x(0.76f), y(0.50f))
                lineTo(x(0.30f), y(0.82f))
                close()
            },
            color,
        )
        TransportOsdGlyph.Pause -> {
            val radius = CornerRadius(2.dp.toPx())
            drawRoundRect(
                color = color,
                topLeft = Offset(x(0.26f), y(0.18f)),
                size = Size(w * 0.16f, h * 0.64f),
                cornerRadius = radius,
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(x(0.58f), y(0.18f)),
                size = Size(w * 0.16f, h * 0.64f),
                cornerRadius = radius,
            )
        }
        TransportOsdGlyph.Previous, TransportOsdGlyph.Next -> {
            val reverse = glyph == TransportOsdGlyph.Previous
            val near = if (reverse) 0.70f else 0.30f
            val far = if (reverse) 0.32f else 0.68f
            drawPath(
                Path().apply {
                    moveTo(x(near), y(0.18f))
                    lineTo(x(far), y(0.50f))
                    lineTo(x(near), y(0.82f))
                    close()
                },
                color,
            )
            val lineX = x(if (reverse) 0.24f else 0.76f)
            drawLine(
                color = color,
                start = Offset(lineX, y(0.18f)),
                end = Offset(lineX, y(0.82f)),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        TransportOsdGlyph.Forward, TransportOsdGlyph.Rewind -> {
            val reverse = glyph == TransportOsdGlyph.Rewind
            val starts = if (reverse) floatArrayOf(0.86f, 0.48f) else floatArrayOf(0.14f, 0.52f)
            starts.forEach { start ->
                val tip = if (reverse) start - 0.34f else start + 0.34f
                drawPath(
                    Path().apply {
                        moveTo(x(start), y(0.22f))
                        lineTo(x(tip), y(0.50f))
                        lineTo(x(start), y(0.78f))
                        close()
                    },
                    color,
                )
            }
        }
    }
}
