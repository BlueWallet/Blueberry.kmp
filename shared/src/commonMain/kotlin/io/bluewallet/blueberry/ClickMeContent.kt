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

@Composable
fun ClickMeContent() {
    var result by remember { mutableStateOf<List<String>?>(null) }
    val passed = result == listOf("ok")
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
                result == null -> Text(
                    text = "\u00A0",
                    color = Color.Transparent,
                    fontFamily = BwFontFamily,
                    fontSize = BwType.ValueSize,
                    fontWeight = BwType.Value,
                )
                passed -> Text(
                    text = "OK",
                    color = BwColors.Ink,
                    fontFamily = BwFontFamily,
                    fontSize = BwType.ValueSize,
                    fontWeight = BwType.Value,
                )
                else -> result?.forEach { line ->
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
        PillButton(text = "Run self-diagnostics", onClick = { result = vendorLibraryStatus() })
    }
}
