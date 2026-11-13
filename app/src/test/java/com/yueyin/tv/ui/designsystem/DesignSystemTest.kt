package com.yueyin.tv.ui.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.yueyin.tv.core.model.preferences.QualityTier
import com.yueyin.tv.ui.FnColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画质档位与统一 token 的回归测试。
 * 这里只覆盖纯 JVM 可判定的部分；帧率/掉帧等真机指标不在此列，也不做模拟断言。
 */
class DesignSystemTest {

    @Test
    fun `smooth tier disables every decorative load`() {
        val p = QualityTier.Smooth.policy()
        assertFalse(p.idleAnimations)
        assertFalse(p.pageTransitions)
        assertFalse(p.gradientLayers)
        assertFalse(p.glass)
        assertFalse(p.imageCrossfade)
        assertFalse(p.ambientEffects)
        assertEquals(0, p.staggerCount)
        assertTrue(p.motionScale == 0f)
        assertEquals(0, p.transitionDuration())
        assertEquals(0, p.idleAnimationDuration(3_600))
    }

    @Test
    fun `rich tier keeps full effects and balanced sits between`() {
        val rich = QualityTier.Rich.policy()
        val balanced = QualityTier.Balanced.policy()
        assertTrue(rich.idleAnimations && rich.pageTransitions && rich.glass)
        assertTrue(rich.gradientLayers && rich.imageCrossfade && rich.ambientEffects)
        assertEquals(8, rich.staggerCount)
        assertTrue(balanced.staggerCount in 1 until rich.staggerCount)
        assertFalse(balanced.glass)
        assertTrue(balanced.idleAnimations)
        assertTrue(balanced.transitionDuration() in 1 until FnMotion.StandardMs)
    }

    @Test
    fun `auto resolves to balanced as a safe fallback`() {
        val auto = QualityTier.Auto.policy()
        val balanced = QualityTier.Balanced.policy()
        assertEquals(balanced.copy(tier = QualityTier.Auto), auto)
    }

    @Test
    fun `balanced stretches idle animation period instead of dropping it`() {
        val p = QualityTier.Balanced.policy()
        assertTrue(p.idleAnimationDuration(3_600) > 3_600)
        assertEquals(3_600, QualityTier.Rich.policy().idleAnimationDuration(3_600))
    }

    @Test
    fun `tier labels are stable and distinct`() {
        val labels = QualityTier.entries.map { it.tierLabel() }
        assertEquals(QualityTier.entries.size, labels.toSet().size)
        assertEquals("流畅", QualityTier.Smooth.tierLabel())
    }

    @Test
    fun `dark palette delegation keeps existing colors byte for byte`() {
        assertEquals(FnPalette.Dark.background, FnColors.Background)
        assertEquals(FnPalette.Dark.surface, FnColors.Surface)
        assertEquals(FnPalette.Dark.surfaceAlt, FnColors.SurfaceAlt)
        assertEquals(FnPalette.Dark.text, FnColors.Text)
        assertEquals(FnPalette.Dark.muted, FnColors.Muted)
        assertEquals(FnPalette.Dark.coral, FnColors.Coral)
        assertEquals(FnPalette.Dark.amber, FnColors.Amber)
        assertEquals(FnPalette.Dark.teal, FnColors.Teal)
        assertEquals(FnPalette.Dark.cyan, FnColors.Cyan)
        assertEquals(FnPalette.Dark.violet, FnColors.Violet)
        assertEquals(FnPalette.Dark.warning, FnColors.Warning)
        assertEquals(FnPalette.Dark.focusFill, FnColors.FocusFill)
        assertEquals(FnPalette.Dark.focusSolid, FnColors.FocusSolid)
        assertEquals(FnPalette.Dark.focusOnSolid, FnColors.FocusOnSolid)
        assertEquals(FnPalette.Dark.selectedSoft, FnColors.SelectedSoft)
    }

    @Test
    fun `light palette meets the same contrast thresholds`() {
        val p = FnPalette.Light
        assertTrue(contrast(p.text, p.background) >= 7.0f)
        assertTrue(contrast(p.muted, p.background) >= 4.5f)
        assertTrue(contrast(p.warning, p.background) >= 4.5f)
        assertTrue(contrast(p.text, p.surface) >= 7.0f)
        assertTrue(contrast(p.muted, p.surface) >= 4.5f)
        assertTrue(contrast(p.focusOnSolid, p.focusSolid) >= 4.5f)
    }

    @Test
    fun `brush color tables are stable shared constants`() {
        assertEquals(3, FnBrushes.SkeletonHighlight.size)
        assertEquals(2, FnBrushes.SurfaceSheen.size)
        assertEquals(2, FnBrushes.ScrimBottom.size)
        // remember 缓存是组合期行为，JVM 单测无法断言；只验证色表来源唯一。
        assertEquals(FnBrushes.SkeletonHighlight.first(), Color.Transparent)
    }

    private fun contrast(foreground: Color, background: Color): Float {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
