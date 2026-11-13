package com.yueyin.tv.ui.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * 统一的常驻动画入口。
 *
 * 低档位（[RenderPolicy.idleAnimations] = false）直接返回一个静态 State，
 * 不创建 `InfiniteTransition`——界面静止时不再有任何动画帧更新，这是低端设备
 * 最直接的收益。返回的始终是 [State]，调用方在 graphicsLayer / Canvas 里
 * 延迟读取即可保持「只走绘制阶段、不产生每帧重组」的既有优化。
 */
@Composable
fun rememberIdleFloat(
    label: String,
    policy: RenderPolicy,
    durationMs: Int,
    initialValue: Float = 0f,
    targetValue: Float = 1f,
    repeatMode: RepeatMode = RepeatMode.Restart,
): State<Float> {
    val duration = policy.idleAnimationDuration(durationMs)
    return if (duration <= 0) {
        remember(label) { mutableStateOf(initialValue) }
    } else {
        val transition = rememberInfiniteTransition(label = label)
        transition.animateFloat(
            initialValue = initialValue,
            targetValue = targetValue,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = duration, easing = LinearEasing),
                repeatMode = repeatMode,
            ),
            label = label,
        )
    }
}
