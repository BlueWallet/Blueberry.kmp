package io.bluewallet.blueberry.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import blueberry.shared.generated.resources.Res
import blueberry.shared.generated.resources.blueberry_mark
import org.jetbrains.compose.resources.painterResource

private class LogoColors(
    val word: Color,
    val byline: Color,
    val pillText: Color,
    val pillFill: Color,
    val pillBorder: Color,
)

private val DarkLogo =
    LogoColors(
        word = Color(0xFFE8EEFA),
        byline = Color(0xFF8C98B3),
        pillText = Color(0xFFA9C6F5),
        pillFill = Color(0xB310204A),
        pillBorder = Color(0x66A9C6F5),
    )

private val LightLogo =
    LogoColors(
        word = Color(0xFF0C2550),
        byline = Color(0xFF5E6A80),
        pillText = Color(0xFF0F5CC0),
        pillFill = Color(0xFFE4EEFB),
        pillBorder = Color(0x660F5CC0),
    )

/**
 * Blueberry lockup from the website header: berry mark, "Blueberry" wordmark
 * with "by BlueWallet" underneath, and an optional BETA pill on the right.
 * Follows the app's light/dark palette.
 */
@Composable
fun BlueberryLogo(
    modifier: Modifier = Modifier,
    showBeta: Boolean = true,
) {
    val colors = if (LocalDarkPalette.current) DarkLogo else LightLogo
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(Res.drawable.blueberry_mark),
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            contentScale = ContentScale.Fit,
        )
        Column {
            Text(
                text = "Blueberry",
                style =
                    TextStyle(
                        fontFamily = BwFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        lineHeight = 24.sp,
                        letterSpacing = (-0.02).em,
                        color = colors.word,
                    ),
            )
            Text(
                text = "by BlueWallet",
                modifier = Modifier.padding(top = 2.dp),
                style =
                    TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        color = colors.byline,
                    ),
            )
        }
        if (showBeta) BetaPill(colors)
    }
}

@Composable
private fun BetaPill(colors: LogoColors) {
    val shape = RoundedCornerShape(percent = 50)
    Text(
        text = "BETA",
        modifier =
            Modifier
                .background(colors.pillFill, shape)
                .border(1.dp, colors.pillBorder, shape)
                .padding(horizontal = 9.dp, vertical = 3.dp),
        style =
            TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                letterSpacing = 0.08.em,
                color = colors.pillText,
            ),
    )
}
