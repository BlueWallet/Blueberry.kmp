package io.bluewallet.blueberry

/** Local frame for a camera preview layer hosted in a UIView. Uses bounds origin, not the view frame. */
internal data class PreviewLayerLocalFrame(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

internal fun previewLayerLocalFrame(
    hostWidth: Double,
    hostHeight: Double,
): PreviewLayerLocalFrame = PreviewLayerLocalFrame(x = 0.0, y = 0.0, width = hostWidth, height = hostHeight)
