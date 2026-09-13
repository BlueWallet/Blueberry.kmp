package io.bluewallet.blueberry

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwType
import java.util.concurrent.Executors

@Composable
actual fun QrCameraPreview(
    onGrayFrame: (width: Int, height: Int, gray: ByteArray) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onGrayFrame)
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = it
        }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val message =
        when {
            !granted -> "Camera permission required"
            cameraError != null -> cameraError
            else -> null
        }
    if (message != null) {
        Text(
            text = message,
            color = BwColors.InkMuted,
            fontFamily = BwFontFamily,
            fontSize = BwType.BodySize,
            modifier = modifier,
        )
        return
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val providerRef = remember { arrayOfNulls<ProcessCameraProvider>(1) }
    DisposableEffect(Unit) {
        onDispose {
            providerRef[0]?.unbindAll()
            executor.shutdown()
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener(
                {
                    val provider =
                        try {
                            future.get()
                        } catch (_: Exception) {
                            cameraError = "No camera"
                            return@addListener
                        }
                    if (executor.isShutdown) return@addListener
                    providerRef[0] = provider
                    val preview =
                        Preview.Builder().build().also { useCase ->
                            useCase.surfaceProvider = previewView.surfaceProvider
                        }
                    val analysis =
                        ImageAnalysis
                            .Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                            .build()
                    analysis.setAnalyzer(executor) { image ->
                        try {
                            callback(image.width, image.height, yPlane(image))
                        } finally {
                            image.close()
                        }
                    }
                    val selector =
                        when {
                            provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
                                CameraSelector.DEFAULT_BACK_CAMERA
                            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
                                CameraSelector.DEFAULT_FRONT_CAMERA
                            else -> {
                                cameraError = "No camera"
                                return@addListener
                            }
                        }
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                    } catch (_: Exception) {
                        cameraError = "No camera"
                    }
                },
                ContextCompat.getMainExecutor(ctx),
            )
            previewView
        },
    )
}

private fun yPlane(image: ImageProxy): ByteArray {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val width = image.width
    val height = image.height
    val out = ByteArray(width * height)
    buffer.rewind()
    if (pixelStride == 1 && rowStride == width) {
        buffer.get(out, 0, width * height)
        return out
    }
    for (row in 0 until height) {
        buffer.position(row * rowStride)
        if (pixelStride == 1) {
            buffer.get(out, row * width, width)
        } else {
            val packed = ByteArray(rowStride)
            val n = minOf(rowStride, buffer.remaining())
            buffer.get(packed, 0, n)
            var src = 0
            var dst = row * width
            repeat(width) {
                out[dst++] = packed[src]
                src += pixelStride
            }
        }
    }
    return out
}
