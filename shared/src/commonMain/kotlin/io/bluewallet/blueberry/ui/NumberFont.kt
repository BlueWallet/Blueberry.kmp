package io.bluewallet.blueberry.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** Digits on the year dial. iOS uses SF Pro Rounded; other platforms use the default face. */
@Composable
internal expect fun numberFontFamily(weight: FontWeight): FontFamily
