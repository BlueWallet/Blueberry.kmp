package io.bluewallet.blueberry.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle

/** Welcome-screen tagline. iOS uses SF Pro with −0.45pt tracking. */
@Composable
expect fun PlatformWelcomeTaglineStyle(): TextStyle
