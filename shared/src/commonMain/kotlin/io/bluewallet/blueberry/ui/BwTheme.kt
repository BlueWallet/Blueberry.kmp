package io.bluewallet.blueberry.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tokens from the sister BlueWallet dashboard (`bw_ext.png`) plus
 * BlueWallet `themes.ts` light palette. Shared across Android / iOS / desktop.
 */
object BwColors {
    val Paper = Color(0xFFF7F8FA)
    val Card = Color(0xFFFFFFFF)
    val Section = Color(0xFFF9F9F9)
    val Ink = Color(0xFF0C2550)
    val InkSecondary = Color(0xFF81868E)
    val InkMuted = Color(0xFF9AA0AA)
    /** Sister "Refresh" / "Sidebar" — BlueWallet `secondButtonTextColor`. */
    val Action = Color(0xFF50555C)
    val Link = Color(0xFF0F5CC0)
    val Accent = Color(0xFF007AFF)
    val AccentSoft = Color(0xFFCCDDF9)
    val BarTrack = Color(0xFFE4EEFB)
    val Success = Color(0xFF37C0A1)
    val Danger = Color(0xFFD0021B)
    val Warning = Color(0xFFF38C47)
    val Border = Color(0xFFEDEDED)
    val OnAccent = Color(0xFFFFFFFF)
}

object BwSpace {
    val ScreenX = 10.dp
    val ScreenY = 6.dp
    val Gap = 6.dp
    val Card = 8.dp
    val Radius = 12.dp
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
    val ActionSize = 15.sp
    val LabelSize = 11.sp
    val BodySize = 13.sp
    val ValueSize = 18.sp
    val HeroSize = 26.sp
    val CaptionSize = 11.sp
}

private val BwColorScheme = lightColorScheme(
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
    outline = BwColors.Border,
    error = BwColors.Danger,
)

/**
 * Applies the light palette and paints the window with [BwColors.Paper]. The
 * app has no dark palette; without this Surface the host window's own color
 * (black when the OS is in dark mode) bleeds through screens that never set a
 * background, leaving [BwColors.Ink] text invisible.
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
