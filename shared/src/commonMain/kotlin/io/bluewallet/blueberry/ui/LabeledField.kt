package io.bluewallet.blueberry.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val FieldShape = RoundedCornerShape(14.dp)
private val FieldTextSize = 17.sp

/** Room kept below a focused field for the action bar that rides on the keyboard. */
private val KeyboardBarClearance = 96.dp

/**
 * Scroll containers here don't shrink for the keyboard, so a focused field asks to be
 * revealed with the keyboard height added below it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.keepAboveKeyboard(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    var focused by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(focused, imeBottom) {
        if (focused && imeBottom > 0) {
            val below = imeBottom + with(density) { KeyboardBarClearance.toPx() }
            requester.bringIntoView(Rect(0f, 0f, size.width.toFloat(), size.height + below))
        }
    }
    return this
        .bringIntoViewRequester(requester)
        .onSizeChanged { size = it }
        .onFocusChanged { focused = it.hasFocus }
}

/** Label above a filled input with a placeholder. No floating label, no outline. */
@Suppress("LongParameterList")
@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth().keepAboveKeyboard(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = label,
            color = BwColors.InkQuiet,
            fontFamily = BwFontFamily,
            fontSize = BwType.BodySize,
            fontWeight = BwType.Label,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = keyboardOptions,
            cursorBrush = SolidColor(BwColors.Accent),
            textStyle = TextStyle(color = BwColors.Ink, fontFamily = BwFontFamily, fontSize = FieldTextSize),
            decorationBox = { inner ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .background(BwColors.InputFill, FieldShape)
                            .border(1.dp, if (isError) BwColors.Danger else Color.Transparent, FieldShape)
                            .padding(start = 16.dp, end = if (trailing == null) 16.dp else 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                color = BwColors.InkMuted,
                                fontFamily = BwFontFamily,
                                fontSize = FieldTextSize,
                                maxLines = 1,
                            )
                        }
                        inner()
                    }
                    if (trailing != null) trailing()
                }
            },
        )
    }
}
