package io.bluewallet.blueberry.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Dark home tokens from the Blueberry redesign mocks. Shared across
 * Android / iOS / desktop.
 */
object BwColors {
    val Paper = Color(0xFF000000)
    val Card = Color(0xFF181B1D)
    val Section = Color(0xFF1E2224)
    val Ink = Color(0xFFFFFFFF)
    val InkSecondary = Color(0xFF606C73)
    val InkMuted = Color(0xFF606C73)

    val Action = Color(0xFFC7C7CC)
    val Link = Color(0xFF00AAE0)
    val Accent = Color(0xFF00AAE0)
    val AccentSoft = Color(0xFF012234)
    val BarTrack = Color(0xFF2A3033)
    val Success = Color(0xFF32D74B)
    val Danger = Color(0xFFFF453A)
    val Warning = Color(0xFFFF9F0A)
    val Border = Color(0xFF181B1D)
    val OnAccent = Color(0xFF012234)
}

object BwSpace {
    val ScreenX = 16.dp
    val ScreenY = 8.dp
    val Gap = 10.dp
    val Card = 12.dp
    val Radius = 16.dp
    val Hairline = 1.dp
}

val BwFontFamily: FontFamily = FontFamily.SansSerif

/** Weights from the sister dashboard: actions and labels sit above Regular. */
object BwType {
    val Action = FontWeight.SemiBold
    val Label = FontWeight.SemiBold
    val Body = FontWeight.SemiBold
    val Value = FontWeight.Bold
    val Hero = FontWeight.ExtraBold
    val Caption = FontWeight.Normal
    val ActionSize = 16.sp
    val LabelSize = 13.sp
    val BodySize = 15.sp
    val ValueSize = 22.sp
    val HeroSize = 26.sp
    val CaptionSize = 12.sp
}

private val BwColorScheme =
    darkColorScheme(
        primary = BwColors.Accent,
        onPrimary = BwColors.OnAccent,
        primaryContainer = BwColors.AccentSoft,
        onPrimaryContainer = BwColors.Ink,
        secondary = BwColors.Link,
        onSecondary = BwColors.OnAccent,
        background = BwColors.Paper,
        onBackground = BwColors.Ink,
        surface = BwColors.Card,
        onSurface = BwColors.Ink,
        surfaceVariant = BwColors.Section,
        onSurfaceVariant = BwColors.InkSecondary,
        outline = BwColors.BarTrack,
        error = BwColors.Danger,
    )

/**
 * Applies the dark palette and paints the window with [BwColors.Paper].
 */
@Composable
fun BwTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BwColorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            content = content,
        )
    }
}
