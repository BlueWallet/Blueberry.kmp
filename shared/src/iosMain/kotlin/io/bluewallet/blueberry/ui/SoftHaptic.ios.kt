package io.bluewallet.blueberry.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle

@Composable
internal actual fun rememberSoftHaptic(): SoftHaptic = remember { IosSoftHaptic() }

private class IosSoftHaptic : SoftHaptic {
    private val generator =
        UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleSoft).also {
            it.prepare()
        }

    override fun perform() {
        generator.impactOccurred()
        generator.prepare()
    }
}
