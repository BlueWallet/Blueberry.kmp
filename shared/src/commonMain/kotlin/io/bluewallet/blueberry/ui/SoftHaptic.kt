package io.bluewallet.blueberry.ui

import androidx.compose.runtime.Composable

internal fun interface SoftHaptic {
    fun perform()
}

@Composable
internal expect fun rememberSoftHaptic(): SoftHaptic
