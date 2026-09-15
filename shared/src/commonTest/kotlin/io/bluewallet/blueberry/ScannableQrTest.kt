package io.bluewallet.blueberry

import io.bluewallet.blueberry.ui.scannableQrStyle
import kotlin.test.Test
import kotlin.test.assertTrue

class ScannableQrTest {
    @Test
    fun plate_stays_light_so_dark_paper_does_not_hide_modules() {
        val style = scannableQrStyle()
        assertTrue(argbLuminance(style.plateArgb) > 0.8f)
        assertTrue(argbLuminance(style.moduleArgb) < 0.2f)
        assertTrue(argbLuminance(style.plateArgb) - argbLuminance(style.moduleArgb) > 0.6f)
    }
}

private fun argbLuminance(argb: Long): Float {
    val r = ((argb shr 16) and 0xFF).toFloat() / 255f
    val g = ((argb shr 8) and 0xFF).toFloat() / 255f
    val b = (argb and 0xFF).toFloat() / 255f
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}
