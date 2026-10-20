package io.bluewallet.blueberry.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * When false, iOS skips UIKit scroll-edge overlays so Compose modal scrims can
 * sit above them. Provide at a common ancestor of [PlatformScrollEdge] and the scrim.
 */
internal val LocalScrollEdgeOverlaysEnabled = staticCompositionLocalOf { true }

/** Disables iOS scroll-edge overlays under a Compose modal scrim. */
@Composable
fun SuppressScrollEdgeOverlays(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalScrollEdgeOverlaysEnabled provides false, content = content)
}
