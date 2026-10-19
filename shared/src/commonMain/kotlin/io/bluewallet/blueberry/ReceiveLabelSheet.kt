package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.PillButton
import io.bluewallet.blueberry.ui.TextAction

/** Bottom sheet asking for a receive address label. Save stays disabled while the draft is blank. */
@Composable
internal fun ReceiveLabelSheet(
    draft: String,
    onDraft: (String) -> Unit,
    dismissText: String,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(BwColors.Card, shape)
                .navigationBarsPadding()
                .imePadding()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
            placeholder = { Text("Savings") },
        )
        PillButton(
            text = "Save",
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            enabled = canSaveAddressLabel(draft),
        )
        TextAction(
            text = dismissText,
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}
