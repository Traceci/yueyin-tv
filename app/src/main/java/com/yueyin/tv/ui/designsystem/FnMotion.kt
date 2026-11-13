package com.yueyin.tv.ui.designsystem

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.integerResource
import com.yueyin.tv.R
import kotlin.math.roundToInt

/**
 * 全局动效规格。页面不再各写 200/1400/3600，统一从这里取，再按档位缩放。
 */
object FnMotion {
    const val ShortMs = 150
    const val StandardMs = 220
    const val LongMs = 320
    val StandardEasing: Easing = FastOutSlowInEasing
}

/** 过渡时长：档位关闭转场时为 0，调用方据此直接跳过动画。 */
fun RenderPolicy.transitionDuration(baseMs: Int = FnMotion.StandardMs): Int =
    if (!pageTransitions || motionScale <= 0f) 0
    else (baseMs * motionScale).roundToInt().coerceAtLeast(1)

/**
 * 常驻动画时长：档位关闭时为 0（调用方不启动 infinite transition）；
 * 均衡档把周期拉长，减少单位时间内的帧更新。
 */
fun RenderPolicy.idleAnimationDuration(baseMs: Int): Int =
    if (!idleAnimations || motionScale <= 0f) 0
    else (baseMs / motionScale.coerceAtLeast(0.5f)).roundToInt().coerceAtLeast(1)

/**
 * 从 res/values/motion.xml 读取的动效参数。
 *
 * 酷狗把 538 个 anim XML 的时长/曲线全部引用资源而不是写死数值，换设备档位或
 * 做 A/B 只改资源。这里保留 [FnMotion] 的常量作为无 Context 环境（单测、预览）的兜底，
 * 运行时统一走 [LocalMotionTokens]。
 */
data class MotionTokens(
    val shortMs: Int,
    val standardMs: Int,
    val longMs: Int,
    val staggerCount: Int,
) {
    companion object {
        val Default = MotionTokens(
            shortMs = FnMotion.ShortMs,
            standardMs = FnMotion.StandardMs,
            longMs = FnMotion.LongMs,
            staggerCount = 8,
        )
    }
}

val LocalMotionTokens = staticCompositionLocalOf { MotionTokens.Default }

@Composable
fun rememberMotionTokens(): MotionTokens = MotionTokens(
    shortMs = integerResource(R.integer.motion_short_ms),
    standardMs = integerResource(R.integer.motion_standard_ms),
    longMs = integerResource(R.integer.motion_long_ms),
    staggerCount = integerResource(R.integer.motion_stagger_count),
)

/** 按资源里的标准时长计算过渡时长，仍受画质档位缩放约束。 */
fun RenderPolicy.transitionDuration(tokens: MotionTokens): Int = transitionDuration(tokens.standardMs)
