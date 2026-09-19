package io.bluewallet.blueberry.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import platform.UIKit.UIApplication
import platform.UIKit.UIStatusBarStyleDarkContent
import platform.UIKit.UIStatusBarStyleLightContent

@Composable
internal actual fun ApplySystemBars(dark: Boolean) {
    SideEffect {
        val style = if (dark) UIStatusBarStyleLightContent else UIStatusBarStyleDarkContent
        UIApplication.sharedApplication.setStatusBarStyle(style, animated = false)
    }
}
