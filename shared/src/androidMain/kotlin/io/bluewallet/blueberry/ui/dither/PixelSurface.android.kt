package io.bluewallet.blueberry.ui.dither

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

internal actual class PixelSurface actual constructor(
    private val width: Int,
    private val height: Int,
) {
    private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val image = bitmap.asImageBitmap()

    actual fun update(argb: IntArray): ImageBitmap {
        bitmap.setPixels(argb, 0, width, 0, 0, width, height)
        return image
    }
}
