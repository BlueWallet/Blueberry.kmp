package io.bluewallet.blueberry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** iOS 26+ large system button. Full-width primary actions use this height. */
val ProminentButtonHeight = 50.dp

private val TintedPillFill = Color(0xFF0F2E44)
private val PlainPillFill = Color.Transparent

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

@Composable
private fun PressablePill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    height: Dp,
) {
    val container = if (enabled) BwColors.Accent else BwColors.BarTrack
    val label = if (enabled) BwColors.OnAccent else BwColors.InkSecondary
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
                    .background(container, RoundedCornerShape(50))
                    .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            PillLabel(text = text, color = label)
        }
    }
}

/** Pill with a solid `#0F2E44` fill and a main-blue label. */
@Composable
fun TintedPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = ProminentButtonHeight,
) {
    WashPill(
        text = text,
        onClick = onClick,
        modifier = modifier,
        height = height,
        fill = TintedPillFill,
    )
}

/** Pill with a transparent fill and a main-blue label. */
@Composable
fun PlainPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = ProminentButtonHeight,
) {
    WashPill(
        text = text,
        onClick = onClick,
        modifier = modifier,
        height = height,
        fill = PlainPillFill,
    )
}

@Composable
private fun WashPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    height: Dp,
    fill: Color,
) {
    val label = BwColors.Accent
    Pressable(
        onClick = onClick,
        modifier =
            modifier
                .height(height)
                .semantics { contentDescription = text },
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
            PillLabel(text = text, color = label)
        }
    }
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
