package io.bluewallet.blueberry.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIAction
import platform.UIKit.UIButton
import platform.UIKit.UIButtonConfiguration
import platform.UIKit.UIColor
import platform.UIKit.UIDevice
import platform.UIKit.UIImage

/**
 * iOS back control for [PlatformScreenHeader]. iOS 26 and later use a liquid-glass button.
 * Earlier systems use [HeaderBackButton], the same control as Android and desktop.
 */
@Composable
internal fun IosGlassBackButton(
    onBack: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (rememberSupportsIosLiquidGlass()) {
        IosGlassSymbolButton(
            symbol = "chevron.backward",
            accessibilityLabel = "Back",
            onClick = onBack,
            enabled = enabled,
            modifier = modifier,
        )
    } else {
        HeaderBackButton(onClick = onBack, enabled = enabled, modifier = modifier)
    }
}

/** iOS close control. iOS 26 and later use a liquid-glass xmark; earlier systems use [HeaderCloseButton]. */
@Composable
internal fun IosGlassCloseButton(
    onClose: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (rememberSupportsIosLiquidGlass()) {
        IosGlassSymbolButton(
            symbol = "xmark",
            accessibilityLabel = "Close",
            onClick = onClose,
            enabled = enabled,
            modifier = modifier,
        )
    } else {
        HeaderCloseButton(onClick = onClose, enabled = enabled, modifier = modifier)
    }
}

/** True on iOS 26 and later, where `glassButtonConfiguration` exists. */
@Composable
internal fun rememberSupportsIosLiquidGlass(): Boolean = remember { supportsIosLiquidGlass(UIDevice.currentDevice.systemVersion) }

/**
 * Symbol-only liquid-glass button. iOS 26 and later only: check
 * [rememberSupportsIosLiquidGlass] first. [placedAsOverlay] lets the material sample Compose
 * content drawn behind it.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun IosGlassSymbolButton(
    symbol: String,
    accessibilityLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val latestOnClick by rememberUpdatedState(onClick)
    val latestEnabled by rememberUpdatedState(enabled)
    val action = remember { BackAction() }
    action.fire = {
        if (latestEnabled) latestOnClick()
    }
    UIKitView(
        factory = {
            val configuration = UIButtonConfiguration.glassButtonConfiguration()
            configuration.image = UIImage.systemImageNamed(symbol)
            val button =
                UIButton.buttonWithConfiguration(
                    configuration,
                    UIAction.actionWithHandler { action.fire() },
                )
            button.backgroundColor = UIColor.clearColor
            button
        },
        modifier =
            modifier
                .size(44.dp)
                .semantics {
                    // Same node shape as the Compose IconButton fallback.
                    contentDescription = accessibilityLabel
                    role = Role.Button
                    if (!enabled) disabled()
                    onClick {
                        action.fire()
                        enabled
                    }
                },
        update = { button ->
            button.enabled = enabled
        },
        properties = UIKitInteropProperties(placedAsOverlay = true),
    )
}

private class BackAction {
    var fire: () -> Unit = {}
}
