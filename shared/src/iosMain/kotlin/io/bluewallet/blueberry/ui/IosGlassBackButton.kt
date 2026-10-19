package io.bluewallet.blueberry.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIAction
import platform.UIKit.UIButton
import platform.UIKit.UIButtonConfiguration
import platform.UIKit.UIColor
import platform.UIKit.UIImage

/**
 * Native iOS 26 liquid-glass back control. Compose draws behind it; the button is a UIKit
 * glass button placed above the canvas so the material can sample that content.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun IosGlassBackButton(
    onBack: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val action = remember { BackAction() }
    UIKitView(
        factory = {
            val configuration = UIButtonConfiguration.glassButtonConfiguration()
            configuration.image = UIImage.systemImageNamed("chevron.backward")
            val button =
                UIButton.buttonWithConfiguration(
                    configuration,
                    UIAction.actionWithHandler { action.onClick() },
                )
            button.backgroundColor = UIColor.clearColor
            button
        },
        modifier = modifier.size(44.dp),
        update = { button ->
            action.onClick = onBack
            button.enabled = enabled
        },
        properties = UIKitInteropProperties(placedAsOverlay = true),
    )
}

private class BackAction {
    var onClick: () -> Unit = {}
}
