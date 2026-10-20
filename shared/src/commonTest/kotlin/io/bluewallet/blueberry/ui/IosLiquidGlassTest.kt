package io.bluewallet.blueberry.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosLiquidGlassTest {
    @Test
    fun liquid_glass_starts_at_ios_26() {
        assertFalse(supportsIosLiquidGlass("18.2"))
        assertFalse(supportsIosLiquidGlass("18"))
        assertFalse(supportsIosLiquidGlass("25.9"))
        assertTrue(supportsIosLiquidGlass("26"))
        assertTrue(supportsIosLiquidGlass("26.0"))
        assertTrue(supportsIosLiquidGlass("27.0.1"))
        assertFalse(supportsIosLiquidGlass(""))
        assertFalse(supportsIosLiquidGlass("abc"))
    }
}
