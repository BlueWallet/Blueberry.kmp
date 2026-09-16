package io.bluewallet.blueberry

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
import androidx.compose.ui.viewinterop.UIKitView
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwType
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.plus
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPreset640x480
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreGraphics.CGRectZero
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreVideo.CVPixelBufferGetBaseAddressOfPlane
import platform.CoreVideo.CVPixelBufferGetBytesPerRowOfPlane
import platform.CoreVideo.CVPixelBufferGetHeightOfPlane
import platform.CoreVideo.CVPixelBufferGetWidthOfPlane
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
import platform.Foundation.NSNumber
import platform.QuartzCore.CATransaction
import platform.QuartzCore.kCATransactionDisableActions
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun QrCameraPreview(
    onGrayFrame: (width: Int, height: Int, gray: ByteArray) -> Unit,
    modifier: Modifier,
) {
    val callback by rememberUpdatedState(onGrayFrame)
    var granted by remember {
        mutableStateOf(AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) == AVAuthorizationStatusAuthorized)
    }
    var denied by remember {
        mutableStateOf(
            AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) == AVAuthorizationStatusDenied ||
                AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) == AVAuthorizationStatusRestricted,
        )
    }
    LaunchedEffect(Unit) {
        if (!granted && !denied) {
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { ok ->
                dispatch_async(dispatch_get_main_queue()) {
                    granted = ok
                    denied = !ok
                }
            }
        }
    }
    when {
        denied ->
            Text(
                text = "Camera permission required",
                color = BwColors.InkMuted,
                fontFamily = BwFontFamily,
                fontSize = BwType.BodySize,
                modifier = modifier,
            )
        !granted ->
            Text(
                text = "Opening camera…",
                color = BwColors.InkMuted,
                fontFamily = BwFontFamily,
                fontSize = BwType.BodySize,
                modifier = modifier,
            )
        else -> IosCameraPreview(callback, modifier)
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
private fun IosCameraPreview(
    onGrayFrame: (width: Int, height: Int, gray: ByteArray) -> Unit,
    modifier: Modifier,
) {
    val session = remember { AVCaptureSession() }
    val previewLayer = remember { AVCaptureVideoPreviewLayer(session = session) }
    val delegate = remember { IosFrameDelegate(onGrayFrame) }
    DisposableEffect(session) {
        val queue = dispatch_queue_create("io.bluewallet.blueberry.qr", null)
        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
        val input = device?.let { AVCaptureDeviceInput.deviceInputWithDevice(it, null) }
        val output = AVCaptureVideoDataOutput()
        output.alwaysDiscardsLateVideoFrames = true
        output.videoSettings =
            mapOf(
                kCVPixelBufferPixelFormatTypeKey to NSNumber(unsignedInt = kCVPixelFormatType_420YpCbCr8BiPlanarFullRange),
            )
        output.setSampleBufferDelegate(delegate, queue)
        session.sessionPreset = AVCaptureSessionPreset640x480
        if (input != null && session.canAddInput(input)) session.addInput(input)
        if (session.canAddOutput(output)) session.addOutput(output)
        previewLayer.videoGravity = AVLayerVideoGravityResizeAspectFill
        dispatch_async(queue) {
            session.startRunning()
        }
        onDispose {
            dispatch_async(queue) {
                session.stopRunning()
                output.setSampleBufferDelegate(null, null)
            }
        }
    }
    UIKitView(
        factory = {
            object : UIView(frame = CGRectZero.readValue()) {
                override fun layoutSubviews() {
                    super.layoutSubviews()
                    CATransaction.begin()
                    CATransaction.setValue(true, kCATransactionDisableActions)
                    previewLayer.setFrame(bounds)
                    CATransaction.commit()
                }
            }.apply {
                backgroundColor = UIColor.blackColor
                clipsToBounds = true
                layer.addSublayer(previewLayer)
            }
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalForeignApi::class)
private class IosFrameDelegate(
    private val onGrayFrame: (width: Int, height: Int, gray: ByteArray) -> Unit,
) : NSObject(),
    AVCaptureVideoDataOutputSampleBufferDelegateProtocol {
    override fun captureOutput(
        output: platform.AVFoundation.AVCaptureOutput,
        didOutputSampleBuffer: CMSampleBufferRef?,
        fromConnection: platform.AVFoundation.AVCaptureConnection,
    ) {
        val sample = didOutputSampleBuffer ?: return
        val pixelBuffer = CMSampleBufferGetImageBuffer(sample) ?: return
        CVPixelBufferLockBaseAddress(pixelBuffer, 0u)
        try {
            val width = CVPixelBufferGetWidthOfPlane(pixelBuffer, 0u).toInt()
            val height = CVPixelBufferGetHeightOfPlane(pixelBuffer, 0u).toInt()
            val stride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0u).toInt()
            val base = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0u) ?: return
            val gray = ByteArray(width * height)
            val src = base.reinterpret<ByteVar>()
            gray.usePinned { pinned ->
                for (row in 0 until height) {
                    memcpy(pinned.addressOf(row * width), src + row * stride, width.toULong())
                }
            }
            onGrayFrame(width, height, gray)
        } finally {
            CVPixelBufferUnlockBaseAddress(pixelBuffer, 0u)
        }
    }
}
