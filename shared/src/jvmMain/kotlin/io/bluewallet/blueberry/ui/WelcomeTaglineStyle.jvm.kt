package io.bluewallet.blueberry.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

@Composable
actual fun PlatformWelcomeTaglineStyle(): TextStyle = MaterialBodyStyle().copy(fontWeight = FontWeight.Medium)
