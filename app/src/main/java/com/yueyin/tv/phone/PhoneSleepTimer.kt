package com.yueyin.tv.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.yueyin.tv.ui.FnColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 定时停止播放（睡前定时）。
 *
 * 状态放在进程级单例里，而不是 `remember` 在播放页：用户设完 30 分钟常常退出播放页去翻别的
 * 列表，如果状态跟着页面走，一离开播放页计时就没了。放在这里也只活在进程内——App 被杀、
 * 手机重启就失效。睡觉场景够用，也不必为它写数据库。
 *
 * 到点的动作是「暂停」，不是「停止并清空队列」：醒来还能接着播，队列与进度都留着。
 */
internal object PhoneSleepTimer {
    /** 可选档位（分钟）。 */
    val OPTIONS = listOf(15, 30, 45, 60, 120)

    data class State(
        val endsAtMs: Long = 0L,
        val totalMinutes: Int = 0,
        val leftMs: Long = 0L,
    ) {
        val running: Boolean get() = endsAtMs > 0L

        /** 剩余时间向上取整：剩 30 秒时显示「1 分钟」，比显示「0 分钟」更像还在计时。 */
        val leftMinutes: Int get() = if (running) ((leftMs + 59_999L) / 60_000L).toInt() else 0
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // 自己的作用域：不随任何 Composable 离开组合而取消。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    fun start(minutes: Int, onExpire: () -> Unit) {
        if (minutes <= 0) return
        job?.cancel()
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _state.value = State(endsAtMs = endsAt, totalMinutes = minutes, leftMs = minutes * 60_000L)
        job = scope.launch {
            while (true) {
                val left = endsAt - System.currentTimeMillis()
                if (left <= 0L) break
                _state.value = _state.value.copy(leftMs = left)
                // 最多睡 1 秒：剩余时间每秒走一格，到点误差也不超过一秒。
                delay(1_000L.coerceAtMost(left))
            }
            _state.value = State()
            job = null
            onExpire()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = State()
    }
}

/** 档位文案：60 / 120 分钟说「1 小时 / 2 小时」，比「60 分钟」顺口。 */
private fun sleepOptionLabel(minutes: Int): String = when (minutes) {
    60 -> "1 小时"
    120 -> "2 小时"
    else -> "$minutes 分钟"
}

/**
 * 定时弹层。
 *
 * 正在计时的档位打高亮，底部给一个「关闭定时」——用户半夜想取消时不用去别的页面找开关。
 */
@Composable
internal fun PhoneSleepSheet(
    visible: Boolean,
    state: PhoneSleepTimer.State,
    onStart: (Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    PhoneSheet(
        visible = visible,
        title = "定时停止播放",
        onDismiss = onDismiss,
        heightFraction = 0.36f,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PhoneSpace.screenHorizontal),
        ) {
            Text(
                if (state.running) {
                    "还有 ${state.leftMinutes} 分钟停止播放"
                } else {
                    "选一个时长，到点自动暂停播放"
                },
                color = if (state.running) FnColors.Coral else FnColors.Muted,
                fontSize = PhoneType.Caption,
                maxLines = 2,
            )
            Spacer(Modifier.height(PhoneSpace.lg))

            // 三列网格：5 档一屏摆得下，不用滚动，拇指也够到最下一行。
            PhoneSleepTimer.OPTIONS.chunked(3).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = PhoneSpace.sm),
                    horizontalArrangement = Arrangement.spacedBy(PhoneSpace.sm),
                ) {
                    row.forEach { minutes ->
                        SleepChip(
                            label = sleepOptionLabel(minutes),
                            active = state.running && state.totalMinutes == minutes,
                            modifier = Modifier.weight(1f),
                            onClick = { onStart(minutes) },
                        )
                    }
                    // 补齐最后一行的空位，避免剩下的格子被拉宽
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            if (state.running) {
                Spacer(Modifier.height(PhoneSpace.sm))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(FnColors.Surface, RoundedCornerShape(PhoneRadius.md))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(PhoneRadius.md))
                        .phoneTap(onCancel)
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "关闭定时",
                        color = FnColors.Muted,
                        fontSize = PhoneType.Body,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun SleepChip(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(PhoneRadius.md)
    Box(
        modifier
            .background(if (active) FnColors.Coral.copy(alpha = 0.18f) else FnColors.Surface, shape)
            .border(
                1.dp,
                if (active) FnColors.Coral.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.08f),
                shape,
            )
            .phoneTap(onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) FnColors.Coral else FnColors.Text,
            fontSize = PhoneType.Body,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}
