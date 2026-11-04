package io.bluewallet.blueberry.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.NSLineBreakByTruncatingTail
import platform.UIKit.NSTextAlignmentCenter
import platform.UIKit.UIAction
import platform.UIKit.UIButton
import platform.UIKit.UIButtonConfiguration
import platform.UIKit.UIColor
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIFont
import platform.UIKit.UIFontWeightRegular
import platform.UIKit.UIFontWeightSemibold
import platform.UIKit.UIImage
import platform.UIKit.UILabel
import platform.UIKit.UIMenu
import platform.UIKit.UIView

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
    menu: List<HeaderMenuItem>,
    showBack: Boolean,
    onClose: (() -> Unit)?,
    subtitle: String?,
) {
    val spoken = if (subtitle.isNullOrEmpty()) title else "$title, $subtitle"
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(NavigationBarHeight)
                .semantics { contentDescription = spoken },
    ) {
        if (showBarTitle) {
            // UIKit overlay above the scroll-edge blur. Inset so it does not cover side controls.
            IosBarTitle(
                title = title,
                subtitle = subtitle,
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(horizontal = BarSideClearance),
            )
        }
        if (showBack) {
            IosGlassBackButton(
                onBack = onBack,
                enabled = enabled,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
        if (onClose != null) {
            IosGlassCloseButton(
                onClose = onClose,
                enabled = enabled,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        } else if (trailingText != null && onTrailing != null) {
            // Must be a UIKit overlay — Compose siblings sit under the bar-title overlay.
            IosOverlayTextAction(
                text = trailingText,
                onClick = onTrailing,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        } else if (menu.isNotEmpty()) {
            IosOverlayMenuButton(menu = menu, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}

/** "…" button that opens a native pull-down menu. UIKit overlay for the same reason as [IosOverlayTextAction]. */
@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun IosOverlayMenuButton(
    menu: List<HeaderMenuItem>,
    modifier: Modifier = Modifier,
) {
    val glass = rememberSupportsIosLiquidGlass()
    val actionColor = BwColors.Action
    val latestMenu = rememberUpdatedState(menu)

    fun buildMenu(): UIMenu =
        UIMenu.menuWithChildren(
            latestMenu.value.mapIndexed { i, item ->
                UIAction.actionWithTitle(item.title, image = null, identifier = null) { _ ->
                    latestMenu.value
                        .getOrNull(i)
                        ?.onClick
                        ?.invoke()
                }
            },
        )
    UIKitView(
        factory = {
            val configuration =
                if (glass) UIButtonConfiguration.glassButtonConfiguration() else UIButtonConfiguration.plainButtonConfiguration()
            configuration.image = UIImage.systemImageNamed("ellipsis")
            if (!glass) configuration.baseForegroundColor = actionColor.toUiColor()
            val button = UIButton.buttonWithConfiguration(configuration, primaryAction = null)
            button.backgroundColor = UIColor.clearColor
            button.showsMenuAsPrimaryAction = true
            button.menu = buildMenu()
            button
        },
        modifier =
            modifier
                .size(44.dp)
                .semantics {
                    contentDescription = "More"
                    role = Role.Button
                },
        update = { button -> button.menu = buildMenu() },
        properties = UIKitInteropProperties(placedAsOverlay = true),
    )
}

/**
 * Centered navigation-bar title as a UIKit label overlay. Must use
 * [UIKitInteropProperties.placedAsOverlay] so it draws above the edge blur.
 * Needs an explicit size — wrap-content UIKitView collapses to zero height.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun IosBarTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val ink = BwColors.Ink
    val secondary = BwColors.InkSecondary
    UIKitView(
        factory = { BarTitleView() },
        modifier = modifier,
        update = { view ->
            view.titleLabel.text = title
            view.titleLabel.textColor = ink.toUiColor()
            val line = subtitle?.takeIf { it.isNotEmpty() }
            view.subtitleLabel.text = line.orEmpty()
            view.subtitleLabel.hidden = line == null
            view.subtitleLabel.textColor = secondary.toUiColor()
            view.setNeedsLayout()
        },
        properties =
            UIKitInteropProperties(
                placedAsOverlay = true,
                isNativeAccessibilityEnabled = false,
            ),
    )
}

/** Title over an optional subtitle, centered in the navigation bar. */
@OptIn(ExperimentalForeignApi::class)
private class BarTitleView : UIView(CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    val titleLabel = barLabel(17.0, UIFontWeightSemibold)
    val subtitleLabel = barLabel(12.0, UIFontWeightRegular)

    init {
        backgroundColor = UIColor.clearColor
        userInteractionEnabled = false
        addSubview(titleLabel)
        addSubview(subtitleLabel)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        val width = bounds.useContents { size.width }
        val height = bounds.useContents { size.height }
        val titleHeight = titleLabel.sizeThatFits(CGSizeMake(width, height)).useContents { this.height }
        val showSub = !subtitleLabel.hidden
        val subHeight = if (showSub) subtitleLabel.sizeThatFits(CGSizeMake(width, height)).useContents { this.height } else 0.0
        val gap = if (showSub) 1.0 else 0.0
        val top = ((height - titleHeight - gap - subHeight) / 2.0).coerceAtLeast(0.0)
        titleLabel.setFrame(CGRectMake(0.0, top, width, titleHeight))
        subtitleLabel.setFrame(CGRectMake(0.0, top + titleHeight + gap, width, subHeight))
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun barLabel(
    size: Double,
    weight: Double,
): UILabel {
    val label = UILabel()
    label.textAlignment = NSTextAlignmentCenter
    label.font = UIFont.systemFontOfSize(size, weight)
    label.backgroundColor = UIColor.clearColor
    label.numberOfLines = 1L
    label.lineBreakMode = NSLineBreakByTruncatingTail
    label.userInteractionEnabled = false
    return label
}

/**
 * Trailing header text action as a UIKit overlay so it receives taps above the
 * bar-title and edge-blur overlays.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun IosOverlayTextAction(
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

internal fun Color.toUiColor(): UIColor =
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
