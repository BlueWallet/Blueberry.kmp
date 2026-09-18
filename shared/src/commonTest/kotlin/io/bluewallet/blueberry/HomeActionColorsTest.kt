package io.bluewallet.blueberry

import io.bluewallet.blueberry.ui.BwPalette
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeActionColorsTest {
    @Test
    fun dark_uses_navy_fill_and_cyan_label() {
        val colors = homeActionColors(dark = true)
        assertEquals(BwPalette.Dark.AccentSoft, colors.fill)
        assertEquals(BwPalette.Dark.Accent, colors.label)
        assertEquals(BwPalette.Dark.Accent, colors.disc)
        assertEquals(BwPalette.Dark.AccentSoft, colors.glyph)
    }

    @Test
    fun light_uses_solid_accent_and_white_label() {
        val colors = homeActionColors(dark = false)
        assertEquals(BwPalette.Light.Accent, colors.fill)
        assertEquals(BwPalette.Light.OnAccent, colors.label)
        assertEquals(BwPalette.Light.OnAccent, colors.disc)
        assertEquals(BwPalette.Light.Accent, colors.glyph)
    }
}
