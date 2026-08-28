package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.PillButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ClickMeContent() {
    var vendorResult by remember { mutableStateOf<List<String>?>(null) }
    var torResult by remember { mutableStateOf<List<String>?>(null) }
    var torBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(BwSpace.Gap)) {
        SettingsActionRow(
            result = vendorResult,
            buttonText = "Run self-diagnostics",
            onClick = { vendorResult = vendorLibraryStatus() },
        )
        SettingsActionRow(
            result = torResult,
            busy = torBusy,
            buttonText = if (torBusy) "Testing Tor…" else "Test Tor",
            onClick = {
                if (torBusy) return@SettingsActionRow
                scope.launch {
                    torBusy = true
                    torResult = try {
                        withContext(Dispatchers.Default) { checkTorExit() }
                    } catch (err: Exception) {
                        listOf(err.message ?: err.toString())
                    } finally {
                        torBusy = false
                    }
                }
            },
        )
    }
}

@Composable
private fun SettingsActionRow(
    result: List<String>?,
    buttonText: String,
    onClick: () -> Unit,
    busy: Boolean = false,
) {
    val passed = result?.firstOrNull() == "ok"
    val shape = RoundedCornerShape(BwSpace.Radius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(BwColors.Card, shape)
            .border(BwSpace.Hairline, BwColors.Border, shape)
            .padding(BwSpace.Card),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BwSpace.Gap),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            when {
                busy -> Text(
                    text = "Running…",
                    color = BwColors.InkMuted,
                    fontFamily = BwFontFamily,
                    fontSize = BwType.ValueSize,
                    fontWeight = BwType.Value,
                )
                result == null -> Text(
                    text = "\u00A0",
                    color = Color.Transparent,
                    fontFamily = BwFontFamily,
                    fontSize = BwType.ValueSize,
                    fontWeight = BwType.Value,
                )
                passed -> {
                    Text(
                        text = "OK",
                        color = BwColors.Ink,
                        fontFamily = BwFontFamily,
                        fontSize = BwType.ValueSize,
                        fontWeight = BwType.Value,
                    )
                    result.drop(1).forEach { line ->
                        Text(
                            text = line,
                            color = BwColors.InkMuted,
                            fontFamily = BwFontFamily,
                            fontSize = BwType.CaptionSize,
                            fontWeight = BwType.Caption,
                        )
                    }
                }
                else -> result.forEach { line ->
                    Text(
                        text = line,
                        color = BwColors.Danger,
                        fontFamily = BwFontFamily,
                        fontSize = BwType.CaptionSize,
                        fontWeight = BwType.Caption,
                    )
                }
            }
        }
        PillButton(text = buttonText, onClick = onClick)
    }
}
