package com.yueyin.tv.ui.designsystem

import com.yueyin.tv.core.model.preferences.ThemeMode
import com.yueyin.tv.ui.FnColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThemeModeTest {
    @Test
    fun `unknown stored value keeps the dark default`() {
        assertEquals(ThemeMode.Dark, ThemeMode.of(null))
        assertEquals(ThemeMode.Dark, ThemeMode.of("garbage"))
        assertEquals(ThemeMode.Light, ThemeMode.of("Light"))
        assertEquals(ThemeMode.Dark, ThemeMode.of("Dark"))
    }

    @Test
    fun `fn colors follow the active palette`() {
        try {
            FnPaletteHolder.current = FnPalette.Dark
            assertEquals(FnPalette.Dark.background, FnColors.Background)
            assertEquals(FnPalette.Dark.text, FnColors.Text)

            FnPaletteHolder.current = FnPalette.Light
            assertEquals(FnPalette.Light.background, FnColors.Background)
            assertEquals(FnPalette.Light.text, FnColors.Text)
            assertNotEquals(FnPalette.Dark.focusOnSolid, FnColors.FocusOnSolid)
        } finally {
            FnPaletteHolder.current = FnPalette.Dark
        }
    }

    @Test
    fun `light palette keeps readable contrast tokens`() {
        assertNotEquals(FnPalette.Light.background, FnPalette.Light.text)
        assertNotEquals(FnPalette.Light.focusSolid, FnPalette.Light.focusOnSolid)
        assertEquals(FnPalette.Light.focusSolid, FnPalette.Light.coral)
    }
}
