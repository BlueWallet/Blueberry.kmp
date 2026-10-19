package io.bluewallet.blueberry.ui.dither

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Turns an ARGB pixel array into an [ImageBitmap] each frame.
 * Android reuses one bitmap; Skia targets wrap a fresh raster image.
 */
internal expect class PixelSurface(
    width: Int,
    height: Int,
) {
    fun update(argb: IntArray): ImageBitmap
}
