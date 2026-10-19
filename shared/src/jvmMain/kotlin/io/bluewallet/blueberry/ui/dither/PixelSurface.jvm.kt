package io.bluewallet.blueberry.ui.dither

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

internal actual class PixelSurface actual constructor(
    private val width: Int,
    height: Int,
) {
    private val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
    private val bytes = ByteArray(width * height * 4)

    actual fun update(argb: IntArray): ImageBitmap {
        var j = 0
        for (c in argb) {
            bytes[j] = (c shr 16).toByte()
            bytes[j + 1] = (c shr 8).toByte()
            bytes[j + 2] = c.toByte()
            bytes[j + 3] = (c ushr 24).toByte()
            j += 4
        }
        // makeRaster copies the bytes, so the buffer is safe to reuse.
        return Image.makeRaster(info, bytes, width * 4).toComposeImageBitmap()
    }
}
