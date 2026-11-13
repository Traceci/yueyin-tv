package com.yueyin.tv.ui.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.yueyin.tv.ui.FnColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 沉浸页文字对比度回归。
 *
 * 这类缺陷单靠「文字 vs 纯色主题背景」的测试抓不到：播放页背景是封面模糊位图，
 * 文字是叠在图片上的。这里用压暗后的典型底色做最坏情况，锁住 OnMedia 语义。
 */
class MediaContrastTest {
    /** 封面模糊 + 固定黑 scrim 之后的典型深底（比纯黑略亮）。 */
    private val mediaBackdrop = Color(0xFF2A2A2A)

    private fun contrast(foreground: Color, background: Color): Float {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    @Test
    fun `media text is legible on the blurred artwork backdrop`() {
        assertTrue(contrast(FnMediaColors.Text, mediaBackdrop) >= 7.0f)
        assertTrue(contrast(FnMediaColors.Muted, mediaBackdrop) >= 4.5f)
        assertTrue(contrast(FnMediaColors.Coral, mediaBackdrop) >= 3.0f)
        assertTrue(contrast(FnMediaColors.Warning, mediaBackdrop) >= 4.5f)
    }

    @Test
    fun `media colors never follow the global theme`() {
        try {
            FnPaletteHolder.current = FnPalette.Dark
            val darkText = FnMediaColors.Text
            val darkMuted = FnMediaColors.Muted

            FnPaletteHolder.current = FnPalette.Light
            assertEquals(darkText, FnMediaColors.Text)
            assertEquals(darkMuted, FnMediaColors.Muted)
            assertEquals(FnPalette.Dark.text, FnMediaColors.Text)
            // 浅色模式下主题文字变深，媒体文字保持浅色，两者必须分道扬镳。
            assertNotEquals(FnColors.Text, FnMediaColors.Text)
        } finally {
            FnPaletteHolder.current = FnPalette.Dark
        }
    }

    @Test
    fun `dark theme appearance is unchanged`() {
        FnPaletteHolder.current = FnPalette.Dark
        assertEquals(FnColors.Text, FnMediaColors.Text)
        assertEquals(FnColors.Muted, FnMediaColors.Muted)
        assertEquals(FnColors.Coral, FnMediaColors.Coral)
    }
}
