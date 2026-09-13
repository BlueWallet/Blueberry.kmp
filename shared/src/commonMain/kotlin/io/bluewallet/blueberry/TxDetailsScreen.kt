package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.StoredTx
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.PillButton
import io.bluewallet.blueberry.ui.ScreenHeader
import io.bluewallet.blueberry.ui.TextAction
import kotlinx.coroutines.launch

@Composable
fun TxDetailsScreen(
    runtime: PeersRuntime,
    db: Database,
    txid: String,
    onBack: () -> Unit,
) {
    var snap by remember { mutableStateOf(runtime.walletTxsStore.get()) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var copiedTxid by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    DisposableEffect(runtime.walletTxsStore) {
        val off = runtime.walletTxsStore.subscribe { scope.launch { snap = runtime.walletTxsStore.get() } }
        onDispose { off() }
    }
    val row = snap.txs.find { it.txid == txid }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(BwColors.Paper)
                .safeDrawingPadding()
                .padding(horizontal = BwSpace.ScreenX, vertical = BwSpace.ScreenY),
        verticalArrangement = Arrangement.spacedBy(BwSpace.Gap),
    ) {
        ScreenHeader(title = "Transaction", onBack = onBack)
        if (row == null) {
            Text(
                text = "Transaction not found",
                color = BwColors.InkMuted,
                fontFamily = BwFontFamily,
                fontSize = BwType.BodySize,
            )
        } else {
            Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                TxDetailsBody(
                    row = row,
                    stored = db.transactions.get(txid),
                    copiedTxid = copiedTxid,
                    onCopyTxid = {
                        clipboard.setText(AnnotatedString(it))
                        copiedTxid = true
                    },
                    onEditNote = {
                        draft = row.paymentLabel.orEmpty()
                        editing = true
                    },
                )
            }
        }
    }
    if (editing) {
        TxNoteDialog(
            draft = draft,
            onDraft = { draft = it },
            onSave = {
                setTxNote(db, runtime.wallet, runtime.walletTxsStore, txid, draft)
                editing = false
            },
            onCancel = { editing = false },
        )
    }
}

@Composable
private fun TxDetailsBody(
    row: WalletTxRow,
    stored: StoredTx?,
    copiedTxid: Boolean,
    onCopyTxid: (String) -> Unit,
    onEditNote: () -> Unit,
) {
    val fields = txDetailFields(row, stored, row.fee)
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(BwSpace.Gap),
    ) {
        for (field in fields) {
            TxDetailFieldRow(
                row = row,
                field = field,
                copiedTxid = copiedTxid,
                onCopyTxid = onCopyTxid,
                onEditNote = onEditNote,
            )
        }
    }
}

@Composable
private fun TxDetailFieldRow(
    row: WalletTxRow,
    field: TxDetailField,
    copiedTxid: Boolean,
    onCopyTxid: (String) -> Unit,
    onEditNote: () -> Unit,
) {
    val isNote = field.label == "Note"
    val isTxid = field.label == "Transaction ID"
    val incoming = row.netDeltaSats >= 0
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    when {
                        isNote -> Modifier.clickable(onClick = onEditNote)
                        isTxid -> Modifier.clickable { onCopyTxid(field.value) }
                        else -> Modifier
                    },
                ).padding(vertical = BwSpace.Gap),
    ) {
        Text(
            text = field.label,
            color = BwColors.InkSecondary,
            fontFamily = BwFontFamily,
            fontSize = BwType.LabelSize,
            fontWeight = BwType.Label,
        )
        when {
            field.label == "Amount" ->
                BtcAmountText(
                    sats = row.netDeltaSats,
                    plus = true,
                    color = if (incoming) BwColors.Success else BwColors.Danger,
                )
            isNote && field.value.isEmpty() ->
                Text(
                    text = "Add note",
                    color = BwColors.InkSecondary,
                    fontFamily = BwFontFamily,
                    fontSize = BwType.BodySize,
                )
            else ->
                Text(
                    text = field.value,
                    color = BwColors.Ink,
                    fontFamily = BwFontFamily,
                    fontSize = BwType.BodySize,
                    fontWeight = BwType.Body,
                )
        }
        if (isTxid && copiedTxid) {
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
private fun TxNoteDialog(
    draft: String,
    onDraft: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                text = "Note",
                color = BwColors.Ink,
                fontFamily = BwFontFamily,
                fontWeight = BwType.Value,
            )
        },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Note") },
            )
        },
        confirmButton = { PillButton(text = "Save", onClick = onSave) },
        dismissButton = { TextAction(text = "Cancel", onClick = onCancel) },
        containerColor = BwColors.Card,
    )
}
