package com.yueyin.tv.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test fun `display text colors remain legible on the app background`() {
        assertTrue(contrastRatio(FnColors.Text, FnColors.Background) >= 7.0f)
        assertTrue(contrastRatio(FnColors.Muted, FnColors.Background) >= 4.5f)
        assertTrue(contrastRatio(FnColors.Warning, FnColors.Background) >= 4.5f)
        // FocusFill 是半透明叠色，必须先合成到背景上再比较；
        // 直接拿半透明色算 luminance 会失真（这条断言此前一直红着）。
        val focusSurface = composite(FnColors.FocusFill, FnColors.Background)
        assertTrue(contrastRatio(FnColors.Text, focusSurface) >= 4.5f)
    }

    /** 按 source-over 把半透明前景叠到不透明背景上。 */
    private fun composite(foreground: Color, background: Color): Color {
        val alpha = foreground.alpha
        return background.copy(
            red = foreground.red * alpha + background.red * (1f - alpha),
            green = foreground.green * alpha + background.green * (1f - alpha),
            blue = foreground.blue * alpha + background.blue * (1f - alpha),
            alpha = 1f,
        )
    }

    private fun contrastRatio(foreground: Color, background: Color): Float {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
