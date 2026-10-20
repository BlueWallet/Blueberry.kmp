package io.bluewallet.blueberry.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** iOS 26+ large system button. Full-width primary actions use this height. */
val ProminentButtonHeight = 50.dp

private val TintedPillFillDark = Color(0xFF0F2E44)

/** Solid blue pill — sister-project "+ add". */
@Suppress("LongParameterList")
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 32.dp,
    pressable: Boolean = false,
) {
    if (pressable) {
        PressablePill(
            text = text,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            height = height,
        )
        return
    }
    Button(
        onClick = onClick,
        modifier = modifier.height(height),
        enabled = enabled,
        shape = RoundedCornerShape(50),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = BwColors.Accent,
                contentColor = BwColors.OnAccent,
                disabledContainerColor = BwColors.BarTrack,
                disabledContentColor = BwColors.InkSecondary,
            ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        Text(
            text = text,
            fontFamily = BwFontFamily,
            fontSize = BwType.ActionSize,
            fontWeight = BwType.Action,
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun PressablePill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    height: Dp,
    fill: Color = if (enabled) BwColors.Accent else BwColors.BarTrack,
    label: Color = if (enabled) BwColors.OnAccent else BwColors.InkSecondary,
    icon: Painter? = null,
) {
    Pressable(
        onClick = onClick,
        modifier = modifier.height(height),
        enabled = enabled,
        role = Role.Button,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(fill, RoundedCornerShape(50))
                    .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (icon != null) {
                    Image(
                        painter = icon,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                }
                PillLabel(text = text, color = label)
            }
        }
    }
}

/** Pill with a solid wash fill and a main-blue label. Dark uses `#0F2E44`; light uses AccentSoft. */
@Composable
fun TintedPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
) {
    WashPill(
        text = text,
        onClick = onClick,
        modifier = modifier,
        fill = if (LocalDarkPalette.current) TintedPillFillDark else BwPalette.Light.AccentSoft,
        icon = icon,
    )
}

/** Pill with a transparent fill and a main-blue label. */
@Composable
fun PlainPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WashPill(text = text, onClick = onClick, modifier = modifier, fill = Color.Transparent)
}

@Composable
private fun WashPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    fill: Color,
    icon: Painter? = null,
) {
    PressablePill(
        text = text,
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = text },
        enabled = true,
        height = ProminentButtonHeight,
        fill = fill,
        label = BwColors.Accent,
        icon = icon,
    )
}

@Composable
private fun PillLabel(
    text: String,
    color: Color,
) {
    Text(
        text = text,
        color = color,
        fontFamily = BwFontFamily,
        fontSize = BwType.ActionSize,
        fontWeight = BwType.Action,
    )
}
