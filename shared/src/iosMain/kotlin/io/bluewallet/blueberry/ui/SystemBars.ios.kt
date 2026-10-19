package io.bluewallet.blueberry.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.uikit.LocalUIViewController
import platform.UIKit.UIStatusBarStyle
import platform.UIKit.UIStatusBarStyleDarkContent
import platform.UIKit.UIStatusBarStyleLightContent

/**
 * Dark artwork uses light status-bar glyphs. [MainViewController] reads this from
 * [androidx.compose.ui.uikit.ComposeUIViewControllerDelegate.preferredStatusBarStyle].
 */
internal object IosStatusBar {
    var darkBackground: Boolean = true
}

internal fun iosPreferredStatusBarStyle(): UIStatusBarStyle {
    val dark = IosStatusBar.darkBackground
    return if (dark) UIStatusBarStyleLightContent else UIStatusBarStyleDarkContent
}

@Composable
internal actual fun ApplySystemBars(dark: Boolean) {
    val controller = LocalUIViewController.current
    SideEffect {
        IosStatusBar.darkBackground = dark
        controller.setNeedsStatusBarAppearanceUpdate()
    }
}
