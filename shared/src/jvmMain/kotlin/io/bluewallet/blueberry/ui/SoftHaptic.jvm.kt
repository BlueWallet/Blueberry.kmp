package io.bluewallet.blueberry.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
internal actual fun rememberSoftHaptic(): SoftHaptic = remember { SoftHaptic { } }
