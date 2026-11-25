package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@Composable
fun QrScanOverlay(
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(BwColors.Paper),
    ) {
        Text(
            text = "Point the camera at a QR code",
            color = BwColors.InkMuted,
            fontFamily = BwFontFamily,
            fontSize = BwType.CaptionSize,
            fontWeight = BwType.Caption,
        )
        QrFrameScanner(
            onText = { text ->
                onResult(text)
                true
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/**
 * Camera preview that hands every decoded QR text to [onText] on the main thread, one frame at a time.
 * Scanning stops once [onText] returns true; returning false keeps reading, as an animated QR needs.
 */
@OptIn(ExperimentalAtomicApi::class)
@Composable
internal fun QrFrameScanner(
    onText: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val busy = remember { AtomicBoolean(false) }
    val finished = remember { AtomicBoolean(false) }
    val latestOnText by rememberUpdatedState(onText)
    Box(modifier = modifier) {
        QrCameraPreview(
            modifier = Modifier.fillMaxSize(),
            onGrayFrame = { width, height, gray ->
                if (finished.load() || !busy.compareAndSet(false, true)) return@QrCameraPreview
                scope.launch(Dispatchers.Default) {
                    try {
                        val text = decodeQrFrame(width, height, gray) ?: return@launch
                        withContext(Dispatchers.Main) {
                            if (!finished.load() && latestOnText(text)) finished.store(true)
                        }
                    } finally {
                        busy.store(false)
                    }
                }
            },
        )
    }
}

@Composable
expect fun QrCameraPreview(
    onGrayFrame: (width: Int, height: Int, gray: ByteArray) -> Unit,
    modifier: Modifier = Modifier,
)
