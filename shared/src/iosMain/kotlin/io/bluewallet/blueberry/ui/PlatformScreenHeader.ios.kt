package io.bluewallet.blueberry.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.NSLineBreakByTruncatingTail
import platform.UIKit.NSTextAlignmentCenter
import platform.UIKit.UIAction
import platform.UIKit.UIButton
import platform.UIKit.UIButtonConfiguration
import platform.UIKit.UIColor
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIFont
import platform.UIKit.UIFontWeightSemibold
import platform.UIKit.UILabel

private val NavigationBarHeight = 44.dp

/** Matches [IosBottomEdgeFade] — space cleared above the home-indicator scrim. */
internal val IosBottomEdgeFeather = IosBottomEdgeFade

/** Room for the glass back control / trailing text action so the title overlay does not cover them. */
private val BarSideClearance = 72.dp

@Composable
@Suppress("LongParameterList")
actual fun PlatformScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    showBarTitle: Boolean,
    trailingText: String?,
    onTrailing: (() -> Unit)?,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(NavigationBarHeight)
                .semantics { contentDescription = title },
    ) {
        if (showBarTitle) {
            // UIKit overlay above the scroll-edge blur. Inset so it does not cover side controls.
            IosBarTitle(
                title = title,
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(horizontal = BarSideClearance),
            )
        }
        IosGlassBackButton(
            onBack = onBack,
            enabled = enabled,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        if (trailingText != null && onTrailing != null) {
            // Must be a UIKit overlay — Compose siblings sit under the bar-title overlay.
            IosOverlayTextAction(
                text = trailingText,
                onClick = onTrailing,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/**
 * Centered navigation-bar title as a UIKit label overlay. Must use
 * [UIKitInteropProperties.placedAsOverlay] so it draws above the edge blur.
 * Needs an explicit size — wrap-content UIKitView collapses to zero height.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun IosBarTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    val ink = BwColors.Ink
    UIKitView(
        factory = {
            val label = UILabel()
            label.text = title
            label.textAlignment = NSTextAlignmentCenter
            label.font = UIFont.systemFontOfSize(17.0, UIFontWeightSemibold)
            label.textColor = ink.toUiColor()
            label.backgroundColor = UIColor.clearColor
            label.numberOfLines = 1L
            label.lineBreakMode = NSLineBreakByTruncatingTail
            label.userInteractionEnabled = false
            label
        },
        modifier = modifier,
        update = { label ->
            label.text = title
            label.textColor = ink.toUiColor()
            label.userInteractionEnabled = false
        },
        properties =
            UIKitInteropProperties(
                placedAsOverlay = true,
                isNativeAccessibilityEnabled = false,
            ),
    )
}

/**
 * Trailing header text action as a UIKit overlay so it receives taps above the
 * bar-title and edge-blur overlays.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun IosOverlayTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionColor = BwColors.Action
    val latestOnClick = rememberUpdatedState(onClick)
    val action = remember { TrailingAction() }
    action.onClick = { latestOnClick.value() }
    UIKitView(
        factory = {
            val configuration = UIButtonConfiguration.plainButtonConfiguration()
            configuration.title = text
            configuration.baseForegroundColor = actionColor.toUiColor()
            val button =
                UIButton.buttonWithConfiguration(
                    configuration,
                    UIAction.actionWithHandler { action.onClick() },
                )
            button.backgroundColor = UIColor.clearColor
            button
        },
        modifier =
            modifier
                .height(NavigationBarHeight)
                .widthIn(min = 44.dp)
                .semantics {
                    contentDescription = text
                    role = Role.Button
                    onClick {
                        action.onClick()
                        true
                    }
                },
        update = { button ->
            button.setTitle(text, forState = UIControlStateNormal)
            button.setTitleColor(actionColor.toUiColor(), forState = UIControlStateNormal)
            button.configuration?.let { config ->
                config.baseForegroundColor = actionColor.toUiColor()
                button.configuration = config
            }
        },
        properties = UIKitInteropProperties(placedAsOverlay = true),
    )
}

private class TrailingAction {
    var onClick: () -> Unit = {}
}

private fun Color.toUiColor(): UIColor =
    UIColor.colorWithRed(
        red.toDouble(),
        green = green.toDouble(),
        blue = blue.toDouble(),
        alpha = alpha.toDouble(),
    )

@Composable
actual fun platformHeaderClearance(): Dp = NavigationBarHeight

@Composable
actual fun platformBottomEdgeClearance(): Dp = IosBottomEdgeFeather

@Composable
actual fun PlatformLargeTitle(
    title: String,
    modifier: Modifier,
) {
    Text(
        text = title,
        modifier = modifier,
        color = BwColors.Ink,
        // FontFamily.Default is SF Pro on iOS; Bold maps to the system bold face.
        fontFamily = FontFamily.Default,
        fontSize = 34.sp,
        lineHeight = 41.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
actual fun PlatformBodyStyle(): TextStyle =
    TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
        color = BwColors.Ink,
    )
