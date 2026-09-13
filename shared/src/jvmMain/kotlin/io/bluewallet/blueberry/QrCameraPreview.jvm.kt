package io.bluewallet.blueberry

import androidx.compose.foundation.Image
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.github.sarxos.webcam.Webcam
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.awt.image.BufferedImage

@Composable
actual fun QrCameraPreview(
    onGrayFrame: (width: Int, height: Int, gray: ByteArray) -> Unit,
    modifier: Modifier,
) {
    val callback by rememberUpdatedState(onGrayFrame)
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            suspend fun fail(message: String) {
                withContext(Dispatchers.Main) { error = message }
            }
            val cam =
                try {
                    Webcam.getDefault()
                } catch (err: Throwable) {
                    fail(err.message ?: "No camera")
                    return@withContext
                }
            if (cam == null) {
                fail("No camera")
                return@withContext
            }
            try {
                try {
                    cam.viewSize = Dimension(640, 480)
                } catch (_: Throwable) {
                }
                cam.open()
                while (isActive) {
                    val frame = cam.image
                    if (frame != null) {
                        val gray = frame.toGray()
                        val bitmap = frame.toComposeImageBitmap()
                        withContext(Dispatchers.Main) {
                            preview = bitmap
                            callback(frame.width, frame.height, gray)
                        }
                    }
                    delay(80)
                }
            } catch (err: Throwable) {
                fail(err.message ?: "Camera failed")
            } finally {
                if (cam.isOpen) cam.close()
            }
        }
    }
    when {
        error != null ->
            Text(
                text = error!!,
                color = BwColors.InkMuted,
                fontFamily = BwFontFamily,
                fontSize = BwType.BodySize,
                modifier = modifier,
            )
        preview != null ->
            Image(
                bitmap = preview!!,
                contentDescription = "Camera",
                modifier = modifier,
                contentScale = ContentScale.Crop,
            )
        else ->
            Text(
                text = "Opening camera…",
                color = BwColors.InkMuted,
                fontFamily = BwFontFamily,
                fontSize = BwType.BodySize,
                modifier = modifier,
            )
    }
}

private fun BufferedImage.toGray(): ByteArray {
    val w = width
    val h = height
    val rgb = IntArray(w * h)
    getRGB(0, 0, w, h, rgb, 0, w)
    val gray = ByteArray(w * h)
    for (i in rgb.indices) {
        val c = rgb[i]
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        gray[i] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
    }
    return gray
}
