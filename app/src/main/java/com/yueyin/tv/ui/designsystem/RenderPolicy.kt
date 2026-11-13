package com.yueyin.tv.ui.designsystem

import androidx.compose.runtime.staticCompositionLocalOf
import com.yueyin.tv.core.model.preferences.QualityTier

/**
 * 渲染策略：由画质档位推导出的**装饰负载**开关集合。
 *
 * 设计约定：这里只降装饰，不降功能。焦点反馈、播放进度、列表滚动、歌词显示
 * 在任何档位下都完整可用；关闭的只是常驻动画、转场、渐变层这类纯视觉效果。
 * 这是低端设备能「功能不缺、画面更轻」的前提。
 */
data class RenderPolicy(
    val tier: QualityTier,
    /** 是否启动常驻无限循环动画（歌词漂浮、唱片呼吸/涟漪、骨架扫光、唱片旋转）。 */
    val idleAnimations: Boolean,
    /** 是否有页面/控件过渡动画。 */
    val pageTransitions: Boolean,
    /** 是否使用多层渐变；关闭时用纯色 + 1px 描边替代。 */
    val gradientLayers: Boolean,
    /** 是否允许毛玻璃等离屏模糊效果。 */
    val glass: Boolean,
    /** 图片加载是否交叉淡入。 */
    val imageCrossfade: Boolean,
    /** 列表首屏入场动画的项数；0 表示不做。 */
    val staggerCount: Int,
    /** 动效时长倍率；0 表示完全不播放。 */
    val motionScale: Float,
    /** 是否启用播放页氛围特效（与已有的 AmbientEffect 开关取交集）。 */
    val ambientEffects: Boolean,
)

/**
 * 纯函数映射，不依赖 Android，便于 JVM 单元测试直接覆盖。
 * Auto 由 [resolveEffectiveTier] 先解析成具体档位；此处兜底按 Balanced 处理。
 */
fun QualityTier.policy(): RenderPolicy = when (this) {
    QualityTier.Smooth -> RenderPolicy(
        tier = this,
        idleAnimations = false,
        pageTransitions = false,
        gradientLayers = false,
        glass = false,
        imageCrossfade = false,
        staggerCount = 0,
        motionScale = 0f,
        ambientEffects = false,
    )
    QualityTier.Balanced -> RenderPolicy(
        tier = this,
        idleAnimations = true,
        pageTransitions = true,
        gradientLayers = true,
        glass = false,
        imageCrossfade = true,
        staggerCount = 4,
        motionScale = 0.75f,
        ambientEffects = true,
    )
    QualityTier.Rich -> RenderPolicy(
        tier = this,
        idleAnimations = true,
        pageTransitions = true,
        gradientLayers = true,
        glass = true,
        imageCrossfade = true,
        staggerCount = 8,
        motionScale = 1f,
        ambientEffects = true,
    )
    QualityTier.Auto -> QualityTier.Balanced.policy().copy(tier = this)
}

val LocalRenderPolicy = staticCompositionLocalOf { QualityTier.Balanced.policy() }

/** 设置页展示名：电视端与手机端共用一份文案。 */
fun QualityTier.tierLabel(): String = when (this) {
    QualityTier.Auto -> "自动"
    QualityTier.Smooth -> "流畅"
    QualityTier.Balanced -> "均衡"
    QualityTier.Rich -> "精美"
}
