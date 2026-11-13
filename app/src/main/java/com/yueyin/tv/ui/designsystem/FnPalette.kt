package com.yueyin.tv.ui.designsystem

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 语义色板：把散在页面里的 141 处 `Color(0x...)` 收敛成有名字的 token。
 *
 * 深色板与改造前的「午夜流光」逐值一致，所以接入后**视觉零变化**；
 * 浅色板按 WCAG 2.x 对比度挑选，供后续深浅色切换使用。
 * 页面仍通过 `FnColors` 读取，因此这一步不需要改任何页面代码。
 */
data class FnPalette(
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val text: Color,
    val muted: Color,
    val coral: Color,
    val amber: Color,
    val teal: Color,
    val cyan: Color,
    val violet: Color,
    val warning: Color,
    val focusFill: Color,
    val focusSolid: Color,
    val focusOnSolid: Color,
    val selectedSoft: Color,
) {
    companion object {
        val Dark = FnPalette(
            background = Color(0xFF070A10),
            surface = Color(0xFF10161F),
            surfaceAlt = Color(0xFF161D29),
            text = Color(0xFFF2F4F8),
            muted = Color(0xFF98A2B3),
            coral = Color(0xFFFF7A5C),
            amber = Color(0xFFFFB46B),
            teal = Color(0xFF4FD1A5),
            cyan = Color(0xFF5FC8FF),
            violet = Color(0xFF9B8CFF),
            warning = Color(0xFFE8C36A),
            focusFill = Color.White.copy(alpha = 0.06f),
            focusSolid = Color(0xFFFF7A5C),
            focusOnSolid = Color(0xFF070A10),
            selectedSoft = Color(0xFFFF7A5C).copy(alpha = 0.16f),
        )

        /**
         * 浅色板对比度（WCAG 2.x 本地计算）：
         * 文字/背景 16.89:1、次要文字/背景 5.59:1、警示/背景 5.52:1、
         * 背景/焦点实心 5.42:1，均满足现有 ThemeContrastTest 的阈值。
         */
        val Light = FnPalette(
            background = Color(0xFFF5F7FA),
            surface = Color(0xFFFFFFFF),
            surfaceAlt = Color(0xFFEDF1F6),
            text = Color(0xFF12161D),
            muted = Color(0xFF5A6472),
            coral = Color(0xFFB33D24),
            amber = Color(0xFF9A5B14),
            teal = Color(0xFF0F7355),
            cyan = Color(0xFF1C6392),
            violet = Color(0xFF5A45B5),
            warning = Color(0xFF8A5A00),
            focusFill = Color.Black.copy(alpha = 0.05f),
            focusSolid = Color(0xFFB33D24),
            focusOnSolid = Color(0xFFF5F7FA),
            selectedSoft = Color(0xFFB33D24).copy(alpha = 0.12f),
        )
    }
}

val LocalFnPalette = staticCompositionLocalOf { FnPalette.Dark }

/**
 * 当前生效的色板（进程级）。
 *
 * 为什么不全用 [LocalFnPalette]：`FnColors` / `FnGradients` 是普通 object，
 * 项目里 135 处引用里有不少在非 Composable 上下文（静态渐变、工具函数、测试），
 * 直接改成 CompositionLocal 读取会编译不过。这里由 [YueYinTheme] 在组合期同步当前值，
 * 并用 `key(darkTheme)` 保证切换时整棵内容树重组一次，视觉结果与纯 CompositionLocal 一致。
 *
 * 电视端恒为 Dark，因此这个全局值在电视上永远是深色板，不存在两端串色问题。
 */
object FnPaletteHolder {
    @Volatile
    var current: FnPalette = FnPalette.Dark
}

/**
 * 媒体沉浸层专用色（OnMedia），与全局外观主题**解耦**。
 *
 * 播放页 / 全屏歌词页的背景恒为「封面模糊位图 + 深色压暗」，不是主题表面色。
 * 若让这里的文字读 [FnColors]，浅色模式下文字会变成深灰压在深底上，完全看不清
 * （用户实测截图：歌名、歌手、歌词与背景糊成一片）。因此这一层恒取深色板的值，
 * 无论外观切到浅色还是跟随系统，沉浸页都保持「深底浅字」。
 *
 * 取值与 [FnPalette.Dark] 逐项一致，所以深色模式下观感零变化。
 * 列表页、设置页仍用 [FnColors] 跟随主题，两套互不干扰。
 */
object FnMediaColors {
    private val base = FnPalette.Dark

    val Text: Color = base.text
    val Muted: Color = base.muted
    val Coral: Color = base.coral
    val Warning: Color = base.warning
    val Violet: Color = base.violet
    val Surface: Color = base.surface
    val Background: Color = base.background
}
