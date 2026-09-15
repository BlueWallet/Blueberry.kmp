package io.bluewallet.blueberry

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.PillButton

@Composable
fun CoinsUtxoSheet(
    row: CoinsRowModel,
    draft: String,
    onDraft: (String) -> Unit,
    onCopy: (String) -> Unit,
    onDone: (save: Boolean) -> Unit,
) {
    var copiedField by remember(row.key) { mutableStateOf<String?>(null) }
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .clip(shape)
                .background(BwColors.Card, shape)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CoinsUtxoSheetHeader(row = row, onDismiss = { onDone(false) })
        row.address?.let { address ->
            CoinsSheetFact(
                label = "Address",
                value = address,
                copied = copiedField == "Address",
                onClick = {
                    onCopy(address)
                    copiedField = "Address"
                },
            )
        }
        CoinsSheetFact(
            label = "Outpoint",
            value = row.key,
            copied = copiedField == "Outpoint",
            onClick = {
                onCopy(row.key)
                copiedField = "Outpoint"
            },
        )
        row.path?.let { path ->
            CoinsSheetFact(
                label = "Path",
                value = path,
                copied = copiedField == "Path",
                onClick = {
                    onCopy(path)
                    copiedField = "Path"
                },
            )
        }
        CoinsUtxoSheetLabel(draft = draft, onDraft = onDraft)
        PillButton(text = "Save", onClick = { onDone(true) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun CoinsUtxoSheetHeader(
    row: CoinsRowModel,
    onDismiss: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetBackArrow(onClick = onDismiss)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(row.circleArgb)),
            )
            BtcAmountText(
                sats = row.valueSats,
                color = BwColors.Ink,
                fontSize = BwType.BodySize,
                fontWeight = BwType.Body,
            )
        }
    }
}

@Composable
private fun CoinsUtxoSheetLabel(
    draft: String,
    onDraft: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Label",
            color = BwColors.InkSecondary,
            fontFamily = BwFontFamily,
            fontSize = BwType.CaptionSize,
            fontWeight = BwType.Caption,
        )
        OutlinedTextField(
            value = draft,
            onValueChange = onDraft,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("My first coin") },
        )
    }
}

@Composable
private fun CoinsSheetFact(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    copied: Boolean = false,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            color = BwColors.InkSecondary,
            fontFamily = BwFontFamily,
            fontSize = BwType.CaptionSize,
            fontWeight = BwType.Caption,
        )
        Text(
            text = value,
            color = BwColors.Ink,
            fontFamily = BwFontFamily,
            fontSize = BwType.CaptionSize,
            fontWeight = BwType.Caption,
        )
        if (copied) {
            Text(
                text = "Copied",
                color = BwColors.Success,
                fontFamily = BwFontFamily,
                fontSize = BwType.CaptionSize,
                fontWeight = BwType.Caption,
            )
        }
    }
}

@Composable
private fun SheetBackArrow(onClick: () -> Unit) {
    Canvas(
        modifier =
            Modifier
                .size(36.dp)
                .clickable(onClick = onClick),
    ) {
        val path = Path()
        path.moveTo(size.width * 0.58f, size.height * 0.28f)
        path.lineTo(size.width * 0.38f, size.height * 0.50f)
        path.lineTo(size.width * 0.58f, size.height * 0.72f)
        drawPath(
            path,
            BwColors.Ink,
            style =
                Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
        )
    }
}
