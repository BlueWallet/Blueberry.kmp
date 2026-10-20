package io.bluewallet.blueberry.ui

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView

@Composable
internal actual fun ApplySystemBars(dark: Boolean) {
    val view = LocalView.current
    // enableEdgeToEdge replays its first call's style (MainActivity's) on every configuration
    // change. Reading the configuration re-runs this after each one so our style wins.
    LocalConfiguration.current
    SideEffect {
        val activity = view.context as? ComponentActivity ?: return@SideEffect
        val style =
            if (dark) {
                SystemBarStyle.dark(Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            }
        activity.enableEdgeToEdge(
            statusBarStyle = style,
            navigationBarStyle = style,
        )
    }
}
