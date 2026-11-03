package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.LocalDarkPalette

/** Coin sheet colors. One instance per theme; read the current one through [SheetPalette]. */
@Suppress("LongParameterList")
internal class SheetColors(
    val sheet: Color,
    val card: Color,
    val tile: Color,
    val text: Color,
    val soft: Color,
    val secondary: Color,
    val glyph: Color,
    val blue: Color,
    val blueFill: Color,
    val green: Color,
    val greenFill: Color,
    val amber: Color,
    val amberFill: Color,
    val accent: Color,
    val accentFill: Color,
    val grey: Color,
    val otherGroup: Color,
    val selectedBorder: Color,
)

private val DarkSheetColors =
    SheetColors(
        sheet = Color(0xFF121416),
        card = Color(0xFF1B1E21),
        tile = Color(0xFF23272B),
        text = Color(0xFFFFFFFF),
        soft = Color(0xFFC9CED3),
        secondary = Color(0xFF8E959C),
        glyph = Color(0xFF6E757C),
        blue = Color(0xFF9CC8F0),
        blueFill = Color(0xFF1C2D3D),
        green = Color(0xFF7FC8A9),
        greenFill = Color(0xFF16302A),
        amber = Color(0xFFE0A24A),
        amberFill = Color(0xFF3A2C17),
        accent = Color(0xFF4A9BEA),
        accentFill = Color(0xFF0E2F4B),
        grey = Color(0xFF3A3F44),
        otherGroup = Color(0xFF3E4A4C),
        selectedBorder = Color(0xFF4A5056),
    )

private val LightSheetColors =
    SheetColors(
        sheet = Color(0xFFF2F2F7),
        card = Color(0xFFFFFFFF),
        tile = Color(0xFFE9ECEF),
        text = Color(0xFF000000),
        soft = Color(0xFF3C4249),
        secondary = Color(0xFF6B7178),
        glyph = Color(0xFF8E959C),
        blue = Color(0xFF2F7CC4),
        blueFill = Color(0xFFDDEBF8),
        green = Color(0xFF1E8F69),
        greenFill = Color(0xFFDCF2E9),
        amber = Color(0xFFA9620C),
        amberFill = Color(0xFFFBEBD3),
        accent = Color(0xFF3788E0),
        accentFill = Color(0xFFDCEAFB),
        grey = Color(0xFFC5CAD3),
        otherGroup = Color(0xFFAFBBBD),
        selectedBorder = Color(0xFFB7BDC6),
    )

/** The coin sheet palette for the current app theme. */
internal val SheetPalette: SheetColors
    @Composable
    @ReadOnlyComposable
    get() = if (LocalDarkPalette.current) DarkSheetColors else LightSheetColors

@Composable
internal fun SheetText(
    text: String,
    size: TextUnit = 13.sp,
    color: Color = SheetPalette.secondary,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
) {
    Text(text = text, modifier = modifier, color = color, fontFamily = BwFontFamily, fontSize = size, fontWeight = weight)
}

@Composable
internal fun SheetCard(
    modifier: Modifier = Modifier,
    radius: Dp = 22.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier = modifier.fillMaxWidth().clip(shape).background(SheetPalette.card, shape),
        content = content,
    )
}

@Composable
internal fun SheetSectionHeader(text: String) {
    SheetText(
        text = text.uppercase(),
        size = 13.sp,
        weight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
internal fun SheetChipView(chip: SheetChip) {
    val (fg, bg) =
        when (chip.kind) {
            SheetChipKind.Neutral -> SheetPalette.soft to SheetPalette.tile
            SheetChipKind.Group, SheetChipKind.Frozen -> SheetPalette.blue to SheetPalette.blueFill
            SheetChipKind.Solo -> SheetPalette.green to SheetPalette.greenFill
            SheetChipKind.Dust -> SheetPalette.amber to SheetPalette.amberFill
        }
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier =
            Modifier
                .heightIn(min = 28.dp)
                .clip(shape)
                .background(bg, shape)
                .padding(horizontal = 11.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        SheetText(chip.text, size = 13.sp, color = fg, weight = FontWeight.Bold)
    }
}

/** Icon in a tinted circle, used by the spend and action rows. */
@Composable
internal fun SheetIconCircle(
    glyph: SheetGlyph,
    tint: Color,
    fill: Color,
    diameter: Dp,
) {
    Box(
        modifier = Modifier.size(diameter).clip(CircleShape).background(fill, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        SheetGlyphView(glyph, tint, Modifier.size(diameter * 0.5f))
    }
}

/** Splits [text] into 4-character groups for addresses and txids. */
internal fun quadGroups(text: String): List<String> = text.chunked(4)
