package io.bluewallet.blueberry.ui

internal const val EDGE_SWIPE_WIDTH_DP = 20
internal const val EDGE_SWIPE_THRESHOLD_DP = 56

internal fun edgeSwipeDismisses(
    startXPx: Float,
    dxPx: Float,
    edgeWidthPx: Float,
    thresholdPx: Float,
): Boolean = startXPx <= edgeWidthPx && dxPx >= thresholdPx
