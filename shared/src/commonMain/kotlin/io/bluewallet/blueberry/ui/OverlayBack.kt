@file:OptIn(ExperimentalComposeUiApi::class)

package io.bluewallet.blueberry.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler

@Composable
internal fun OverlayBackEffects(
    enabled: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    BackHandler(enabled = enabled, onBack = onDismiss)
    val latestDismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val cb: () -> Unit = { latestDismiss() }
        overlayBackStack.push(cb)
        onDispose { overlayBackStack.pop(cb) }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        content()
        IosEdgeSwipeHandle(enabled = enabled, onDismiss = { overlayBackStack.fire() })
    }
}

@Composable
internal expect fun BoxScope.IosEdgeSwipeHandle(
    enabled: Boolean,
    onDismiss: () -> Unit,
)
