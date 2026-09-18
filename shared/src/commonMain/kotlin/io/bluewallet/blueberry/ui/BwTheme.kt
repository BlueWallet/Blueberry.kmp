package io.bluewallet.blueberry.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object BwPalette {
    object Dark {
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

    object Light {
        val Paper = Color(0xFFF7F8FA)
        val Card = Color(0xFFFFFFFF)
        val Section = Color(0xFFF9F9F9)
        val Ink = Color(0xFF0C2550)
        val InkSecondary = Color(0xFF81868E)
        val InkMuted = Color(0xFF9AA0AA)
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
}

internal val LocalDarkPalette = staticCompositionLocalOf { true }

object BwColors {
    val Paper: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalDarkPalette.current) BwPalette.Dark.Paper else BwPalette.Light.Paper

    val Card: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalDarkPalette.current) BwPalette.Dark.Card else BwPalette.Light.Card

    val Section: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalDarkPalette.current) BwPalette.Dark.Section else BwPalette.Light.Section

    val Ink: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalDarkPalette.current) BwPalette.Dark.Ink else BwPalette.Light.Ink

    val InkSecondary: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalDarkPalette.current) BwPalette.Dark.InkSecondary else BwPalette.Light.InkSecondary

    val InkMuted: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalDarkPalette.current) BwPalette.Dark.InkMuted else BwPalette.Light.InkMuted

    val Action: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalDarkPalette.current) BwPalette.Dark.Action else BwPalette.Light.Action

    val Link: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalDarkPalette.current) BwPalette.Dark.Link else BwPalette.Light.Link

    val Accent: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalDarkPalette.current) BwPalette.Dark.Accent else BwPalette.Light.Accent

    val AccentSoft: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalDarkPalette.current) BwPalette.Dark.AccentSoft else BwPalette.Light.AccentSoft

    val BarTrack: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalDarkPalette.current) BwPalette.Dark.BarTrack else BwPalette.Light.BarTrack

    val Success: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalDarkPalette.current) BwPalette.Dark.Success else BwPalette.Light.Success

    val Danger: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalDarkPalette.current) BwPalette.Dark.Danger else BwPalette.Light.Danger

    val Warning: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalDarkPalette.current) BwPalette.Dark.Warning else BwPalette.Light.Warning

    val Border: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalDarkPalette.current) BwPalette.Dark.Border else BwPalette.Light.Border

    val OnAccent: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalDarkPalette.current) BwPalette.Dark.OnAccent else BwPalette.Light.OnAccent
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

private fun bwColorScheme(dark: Boolean) =
    if (dark) {
        darkColorScheme(
            primary = BwPalette.Dark.Accent,
            onPrimary = BwPalette.Dark.OnAccent,
            primaryContainer = BwPalette.Dark.AccentSoft,
            onPrimaryContainer = BwPalette.Dark.Ink,
            secondary = BwPalette.Dark.Link,
            onSecondary = BwPalette.Dark.OnAccent,
            background = BwPalette.Dark.Paper,
            onBackground = BwPalette.Dark.Ink,
            surface = BwPalette.Dark.Card,
            onSurface = BwPalette.Dark.Ink,
            surfaceVariant = BwPalette.Dark.Section,
            onSurfaceVariant = BwPalette.Dark.InkSecondary,
            outline = BwPalette.Dark.BarTrack,
            error = BwPalette.Dark.Danger,
        )
    } else {
        lightColorScheme(
            primary = BwPalette.Light.Accent,
            onPrimary = BwPalette.Light.OnAccent,
            primaryContainer = BwPalette.Light.AccentSoft,
            onPrimaryContainer = BwPalette.Light.Ink,
            secondary = BwPalette.Light.Link,
            onSecondary = BwPalette.Light.OnAccent,
            background = BwPalette.Light.Paper,
            onBackground = BwPalette.Light.Ink,
            surface = BwPalette.Light.Card,
            onSurface = BwPalette.Light.Ink,
            surfaceVariant = BwPalette.Light.Section,
            onSurfaceVariant = BwPalette.Light.InkSecondary,
            outline = BwPalette.Light.Border,
            error = BwPalette.Light.Danger,
        )
    }

/**
 * Applies the selected palette and paints the window with Paper.
 */
@Composable
fun BwTheme(
    dark: Boolean = true,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalDarkPalette provides dark) {
        ApplySystemBars(dark)
        MaterialTheme(colorScheme = bwColorScheme(dark)) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                content = content,
            )
        }
    }
}
