package io.bluewallet.blueberry.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * When false, iOS skips UIKit scroll-edge overlays so Compose modal scrims can
 * sit above them. Provide at a common ancestor of [PlatformScrollEdge] and the scrim.
 */
internal val LocalScrollEdgeOverlaysEnabled = staticCompositionLocalOf { true }
