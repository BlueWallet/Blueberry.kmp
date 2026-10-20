package io.bluewallet.blueberry.ui

import androidx.compose.runtime.Composable

/** True when the system asks apps for less motion. */
@Composable
internal expect fun rememberReducedMotion(): Boolean
