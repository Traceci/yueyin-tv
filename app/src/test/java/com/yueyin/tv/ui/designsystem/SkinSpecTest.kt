package com.yueyin.tv.ui.designsystem

import androidx.compose.ui.graphics.Color
import com.yueyin.tv.core.model.preferences.QualityTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SkinSpecTest {
    @Test
    fun `empty skin keeps the base palette untouched`() {
        assertSame(FnPalette.Dark, SkinSpec.Empty.overlay(FnPalette.Dark))
    }

    @Test
    fun `known color keys override only their own slot`() {
        val spec = SkinSpec(
            id = "warm",
            colors = mapOf(SkinSpec.KEY_BACKGROUND to 0xFF112233L),
            gradients = listOf(SkinGradient(0xFFFF0000L, 0xFF00FF00L, 45f)),
            blur = SkinBlur(radiusPx = 24, tintArgb = 0x33000000L),
            imageUrl = "https://example.invalid/skin.webp",
        )
        val result = spec.overlay(FnPalette.Dark)
        assertEquals(Color(0xFF112233L), result.background)
        assertEquals(FnPalette.Dark.text, result.text)
        assertEquals(FnPalette.Dark.surface, result.surface)
        assertEquals(1, spec.gradients.size)
        assertEquals(24, spec.blur?.radiusPx)
    }

    @Test
    fun `unknown keys are ignored`() {
        val result = SkinSpec(id = "x", colors = mapOf("not_a_token" to 0xFF000000L))
            .overlay(FnPalette.Light)
        assertEquals(FnPalette.Light, result)
    }

    @Test
    fun `invalid argb does not corrupt the palette`() {
        val result = SkinSpec(id = "bad", colors = mapOf(SkinSpec.KEY_TEXT to Long.MIN_VALUE))
            .overlay(FnPalette.Dark)
        assertEquals(FnPalette.Dark.text, result.text)
    }

    @Test
    fun `dark and light palettes differ on key surfaces`() {
        assertNotEquals(FnPalette.Dark.background, FnPalette.Light.background)
        assertNotEquals(FnPalette.Dark.text, FnPalette.Light.text)
        assertNotEquals(FnPalette.Dark.focusOnSolid, FnPalette.Light.focusOnSolid)
    }

    @Test
    fun `motion tokens fall back to the documented constants`() {
        assertEquals(FnMotion.ShortMs, MotionTokens.Default.shortMs)
        assertEquals(FnMotion.StandardMs, MotionTokens.Default.standardMs)
        assertEquals(FnMotion.LongMs, MotionTokens.Default.longMs)
    }

    @Test
    fun `transition duration honours both resource token and quality tier`() {
        val tokens = MotionTokens(shortMs = 100, standardMs = 240, longMs = 400, staggerCount = 6)
        assertEquals(240, QualityTier.Rich.policy().transitionDuration(tokens))
        assertEquals(180, QualityTier.Balanced.policy().transitionDuration(tokens))
        assertEquals(0, QualityTier.Smooth.policy().transitionDuration(tokens))
    }
}
