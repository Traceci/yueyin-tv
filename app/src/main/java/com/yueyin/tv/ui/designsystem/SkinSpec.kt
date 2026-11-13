package com.yueyin.tv.ui.designsystem

import androidx.compose.ui.graphics.Color

/** 服务端可下发的渐变描述（ARGB + 角度）。 */
data class SkinGradient(
    val startArgb: Long,
    val endArgb: Long,
    val angleDegrees: Float = 0f,
)

/** 服务端可下发的模糊/毛玻璃描述。 */
data class SkinBlur(
    val radiusPx: Int,
    val tintArgb: Long,
)

/**
 * 皮肤协议：把「颜色 / 渐变 / 模糊 / 图片」打包成一份可下发的数据。
 *
 * 借鉴酷狗 `SkinThemeProtocol` 的 `ColorInfo / GradientInfo / BlurInfo / ImageInfo` 分层：
 * 换肤不只是换几个色值，渐变与背景图也一起描述。这里只定义数据契约与叠加规则，
 * 不内置任何网络拉取或缓存，避免把项目绑死在某个下发通道上。
 */
data class SkinSpec(
    val id: String,
    val colors: Map<String, Long> = emptyMap(),
    val gradients: List<SkinGradient> = emptyList(),
    val blur: SkinBlur? = null,
    val imageUrl: String? = null,
) {
    companion object {
        const val KEY_BACKGROUND = "background"
        const val KEY_SURFACE = "surface"
        const val KEY_SURFACE_ALT = "surfaceAlt"
        const val KEY_TEXT = "text"
        const val KEY_MUTED = "muted"
        const val KEY_CORAL = "coral"
        const val KEY_FOCUS_SOLID = "focusSolid"
        const val KEY_FOCUS_ON_SOLID = "focusOnSolid"

        val Empty = SkinSpec(id = "default")
    }
}

/**
 * 把下发的颜色覆盖到基准色板上。
 *
 * 未知 key、非法 ARGB 一律忽略并返回原值——皮肤是可选增强，任何一份脏数据
 * 都不该让界面变成全黑或崩溃。渐变/模糊/图片由调用方按需读取，不改写色板。
 */
fun SkinSpec.overlay(base: FnPalette): FnPalette {
    if (colors.isEmpty()) return base
    fun pick(key: String, fallback: Color): Color {
        val value = colors[key] ?: return fallback
        // 只接受无符号 32 位 ARGB；越界值当成脏数据丢弃，避免把界面染成全黑。
        if (value !in 0L..0xFFFF_FFFFL) return fallback
        return runCatching { Color(value) }.getOrDefault(fallback)
    }
    return base.copy(
        background = pick(SkinSpec.KEY_BACKGROUND, base.background),
        surface = pick(SkinSpec.KEY_SURFACE, base.surface),
        surfaceAlt = pick(SkinSpec.KEY_SURFACE_ALT, base.surfaceAlt),
        text = pick(SkinSpec.KEY_TEXT, base.text),
        muted = pick(SkinSpec.KEY_MUTED, base.muted),
        coral = pick(SkinSpec.KEY_CORAL, base.coral),
        focusSolid = pick(SkinSpec.KEY_FOCUS_SOLID, base.focusSolid),
        focusOnSolid = pick(SkinSpec.KEY_FOCUS_ON_SOLID, base.focusOnSolid),
    )
}
