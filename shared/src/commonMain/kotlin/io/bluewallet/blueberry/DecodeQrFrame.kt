package io.bluewallet.blueberry

import qr.QRDecoder
import qr.QRDecodingException

/** Decode one grayscale camera frame. Null if this frame has no QR. */
fun decodeQrFrame(
    width: Int,
    height: Int,
    gray: ByteArray,
): String? =
    try {
        QRDecoder.decode(width, height, gray)
    } catch (_: QRDecodingException) {
        null
    }
