package io.bluewallet.blueberry.ui.dither

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

internal actual class PixelSurface actual constructor(
    private val width: Int,
    private val height: Int,
) {
    // Two bitmaps so setPixels never writes the one RenderThread is still drawing.
    private val front = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val back = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private var writeFront = true

    actual fun update(argb: IntArray): ImageBitmap {
        val target = if (writeFront) front else back
        writeFront = !writeFront
        target.setPixels(argb, 0, width, 0, 0, width, height)
        return target.asImageBitmap()
    }
}
