package io.bluewallet.blueberry.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class BwPaletteTest {
    @Test
    fun dark_palette_matches_current_home_tokens() {
        assertEquals(Color(0xFF000000), BwPalette.Dark.Paper)
        assertEquals(Color(0xFF181B1D), BwPalette.Dark.Card)
        assertEquals(Color(0xFF1E2224), BwPalette.Dark.Section)
        assertEquals(Color(0xFFFFFFFF), BwPalette.Dark.Ink)
        assertEquals(Color(0xFF606C73), BwPalette.Dark.InkSecondary)
        assertEquals(Color(0xFF606C73), BwPalette.Dark.InkMuted)
        assertEquals(Color(0xFFC7C7CC), BwPalette.Dark.Action)
        assertEquals(Color(0xFF00AAE0), BwPalette.Dark.Link)
        assertEquals(Color(0xFF00AAE0), BwPalette.Dark.Accent)
        assertEquals(Color(0xFF012234), BwPalette.Dark.AccentSoft)
        assertEquals(Color(0xFF2A3033), BwPalette.Dark.BarTrack)
        assertEquals(Color(0xFF32D74B), BwPalette.Dark.Success)
        assertEquals(Color(0xFFFF453A), BwPalette.Dark.Danger)
        assertEquals(Color(0xFFFF9F0A), BwPalette.Dark.Warning)
        assertEquals(Color(0xFF181B1D), BwPalette.Dark.Border)
        assertEquals(Color(0xFF012234), BwPalette.Dark.OnAccent)
    }

    @Test
    fun light_palette_matches_pre_dark_reskin() {
        assertEquals(Color(0xFFF7F8FA), BwPalette.Light.Paper)
        assertEquals(Color(0xFFFFFFFF), BwPalette.Light.Card)
        assertEquals(Color(0xFFF9F9F9), BwPalette.Light.Section)
        assertEquals(Color(0xFF0C2550), BwPalette.Light.Ink)
        assertEquals(Color(0xFF81868E), BwPalette.Light.InkSecondary)
        assertEquals(Color(0xFF9AA0AA), BwPalette.Light.InkMuted)
        assertEquals(Color(0xFF50555C), BwPalette.Light.Action)
        assertEquals(Color(0xFF0F5CC0), BwPalette.Light.Link)
        assertEquals(Color(0xFF007AFF), BwPalette.Light.Accent)
        assertEquals(Color(0xFFCCDDF9), BwPalette.Light.AccentSoft)
        assertEquals(Color(0xFFE4EEFB), BwPalette.Light.BarTrack)
        assertEquals(Color(0xFF37C0A1), BwPalette.Light.Success)
        assertEquals(Color(0xFFD0021B), BwPalette.Light.Danger)
        assertEquals(Color(0xFFF38C47), BwPalette.Light.Warning)
        assertEquals(Color(0xFFEDEDED), BwPalette.Light.Border)
        assertEquals(Color(0xFFFFFFFF), BwPalette.Light.OnAccent)
    }
}
