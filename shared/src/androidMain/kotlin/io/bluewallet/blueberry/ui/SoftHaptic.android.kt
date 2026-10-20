package io.bluewallet.blueberry.ui

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

@Composable
internal actual fun rememberSoftHaptic(): SoftHaptic {
    val view = LocalView.current
    return remember(view) {
        SoftHaptic {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
}
