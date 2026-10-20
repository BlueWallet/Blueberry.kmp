package io.bluewallet.blueberry.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** iOS uses SF Pro Rounded. Other platforms use the default face. */
@Composable
internal expect fun numberFontFamily(weight: FontWeight): FontFamily
