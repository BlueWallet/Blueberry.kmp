package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import io.bluewallet.blueberry.bus.BroadcastCancelPayload
import io.bluewallet.blueberry.bus.BroadcastRequestPayload
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.parse.PickUtxos
import io.bluewallet.blueberry.parse.SendBuildParams
import io.bluewallet.blueberry.parse.buildActiveSendTx
import io.bluewallet.blueberry.parse.savePaymentLabel
import io.bluewallet.blueberry.parse.utxoValuePercent
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwCheckbox
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.HorizontalProgressBar
import io.bluewallet.blueberry.ui.PillButton
import io.bluewallet.blueberry.ui.ScreenHeader
import io.bluewallet.blueberry.wallet.BuildSendResult
import io.bluewallet.blueberry.wallet.PsbtSendResult
import io.bluewallet.blueberry.wallet.SendInputUtxo
import io.bluewallet.blueberry.wallet.SignedSendResult
import kotlinx.coroutines.launch

private enum class SendStep { Utxos, Details, FeeRate, Preview }

@Composable
fun SendScreen(
    runtime: PeersRuntime,
    db: Database,
    onBack: () -> Unit,
) {
    val wallet = runtime.wallet
    var step by remember { mutableStateOf(SendStep.Utxos) }
    var selectedKeys by remember { mutableStateOf(setOf<String>()) }
    var details by remember { mutableStateOf<SendDetails?>(null) }
    var preview by remember { mutableStateOf<BuildSendResult?>(null) }
    var previewInputSum by remember { mutableStateOf(0L) }
    var snap by remember { mutableStateOf(runtime.walletTxsStore.get()) }
    var broadcast by remember { mutableStateOf(runtime.broadcastStore.get()) }
    var renameKey by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var invalidField by remember { mutableStateOf<SendField?>(null) }
    var feeRate by remember { mutableStateOf("") }
    var feeError by remember { mutableStateOf<String?>(null) }
    var cancelArmedForId by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(runtime.walletTxsStore) {
        val off = runtime.walletTxsStore.subscribe { scope.launch { snap = runtime.walletTxsStore.get() } }
        onDispose { off() }
    }
    DisposableEffect(runtime.broadcastStore) {
        val off = runtime.broadcastStore.subscribe { scope.launch { broadcast = runtime.broadcastStore.get() } }
        onDispose { off() }
    }
    val selectedSum = snap.utxos.filter { it.key in selectedKeys }.sumOf { it.valueSats }

    fun goBack() {
        val previewTxHex = (preview as? SignedSendResult)?.txHex
        val ownsJob = previewTxHex != null && broadcast.txHex == previewTxHex
        if (step == SendStep.Preview && ownsJob) {
            when (inFlightBroadcastEscape(broadcast.phase, broadcast.id, cancelArmedForId)) {
                BroadcastEscape.Cancel -> {
                    runtime.bus.emit(Event.BroadcastCancel, BroadcastCancelPayload(broadcast.id!!))
                    cancelArmedForId = broadcast.id
                    return
                }
                BroadcastEscape.ForceClose -> {
                    onBack()
                    return
                }
                BroadcastEscape.Ignore -> {}
            }
        }
        if (broadcast.phase == "success" || broadcast.phase == "error") {
            runtime.broadcastStore.reset()
        }
        if (renameKey != null) {
            renameKey = null
            return
        }
        if (scanning) {
            scanning = false
            return
        }
        when (step) {
            SendStep.Utxos -> onBack()
            SendStep.Details -> step = SendStep.Utxos
            SendStep.FeeRate -> step = SendStep.Details
            SendStep.Preview -> {
                preview = null
                step = SendStep.FeeRate
            }
        }
    }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(BwColors.Paper)
                .safeDrawingPadding()
                .padding(horizontal = BwSpace.ScreenX, vertical = BwSpace.ScreenY),
        verticalArrangement = Arrangement.spacedBy(BwSpace.Gap),
    ) {
        ScreenHeader(title = "Send", onBack = { goBack() })
        when (step) {
            SendStep.Utxos ->
                UtxoStep(
                    modifier = Modifier.weight(1f),
                    utxos = snap.utxos,
                    selectedKeys = selectedKeys,
                    selectedSum = selectedSum,
                    renameKey = renameKey,
                    renameDraft = renameDraft,
                    onRenameDraft = { renameDraft = it },
                    onToggle = { key ->
                        selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
                    },
                    onBeginRename = { row ->
                        renameKey = row.key
                        renameDraft = row.name.orEmpty()
                    },
                    onSaveRename = {
                        val key = renameKey ?: return@UtxoStep
                        val w = wallet ?: return@UtxoStep
                        setUtxoName(db, w, runtime.walletTxsStore, key, renameDraft)
                        renameKey = null
                    },
                    onContinue = {
                        if (selectedKeys.isNotEmpty()) step = SendStep.Details
                    },
                )
            SendStep.Details ->
                if (scanning) {
                    QrScanOverlay(
                        modifier = Modifier.weight(1f),
                        onResult = { payload ->
                            address = payload
                            if (invalidField == SendField.Address) invalidField = null
                            scanning = false
                        },
                    )
                } else {
                    DetailsStep(
                        selectedSum = selectedSum,
                        address = address,
                        amount = amount,
                        label = label,
                        invalid = invalidField,
                        onAddress = {
                            address = it
                            if (invalidField == SendField.Address) invalidField = null
                        },
                        onAmount = {
                            amount = it
                            if (invalidField == SendField.Amount) invalidField = null
                        },
                        onLabel = {
                            label = it
                            if (invalidField == SendField.Label) invalidField = null
                        },
                        onScan = { scanning = true },
                        onContinue = {
                            when (val result = validateSendDetails(address, amount, label, selectedSum)) {
                                is SendDetailsValidation.Ok -> {
                                    details = result.details
                                    invalidField = null
                                    step = SendStep.FeeRate
                                }
                                is SendDetailsValidation.Invalid -> invalidField = result.field
                            }
                        },
                    )
                }
            SendStep.FeeRate ->
                FeeRateStep(
                    feeRate = feeRate,
                    error = feeError,
                    onFeeRate = {
                        feeRate = it
                        feeError = null
                    },
                    onContinue = {
                        val rate = parseFeeRateSatPerVb(feeRate)
                        val d = details
                        val w = wallet
                        if (rate == null) {
                            feeError = "fee rate must be positive"
                            return@FeeRateStep
                        }
                        if (d == null || w == null) {
                            feeError = "missing send details"
                            return@FeeRateStep
                        }
                        val picked = pickSelected(snap.utxos, selectedKeys)
                        if (picked is PickUtxos.Error) {
                            feeError = picked.error
                            return@FeeRateStep
                        }
                        val selected = (picked as PickUtxos.Ok).selected
                        try {
                            val result =
                                buildActiveSendTx(
                                    db,
                                    w,
                                    SendBuildParams(
                                        utxos =
                                            selected.map {
                                                SendInputUtxo(it.txid, it.vout, it.valueSats, it.scriptPubKey)
                                            },
                                        toAddress = d.toAddress,
                                        amountSats = d.amountSats,
                                        feeRateSatPerVb = rate,
                                    ),
                                )
                            val txid =
                                when (result) {
                                    is SignedSendResult -> result.txid
                                    is PsbtSendResult -> result.txid
                                }
                            val changeVouts =
                                when (result) {
                                    is SignedSendResult -> result.changeVouts
                                    is PsbtSendResult -> result.changeVouts
                                }
                            savePaymentLabel(db, txid, d.paymentLabel, changeVouts)
                            preview = result
                            previewInputSum = selected.sumOf { it.valueSats }
                            feeError = null
                            cancelArmedForId = null
                            step = SendStep.Preview
                        } catch (err: Throwable) {
                            feeError = err.message ?: err.toString()
                        }
                    },
                )
            SendStep.Preview ->
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    PreviewStep(
                        preview = preview,
                        details = details,
                        inputSum = previewInputSum,
                        broadcast = broadcast,
                        onBroadcast = {
                            val signed = preview as? SignedSendResult ?: return@PreviewStep
                            val id = prepareUiBroadcast(runtime.broadcastStore, signed.txHex) ?: return@PreviewStep
                            runtime.bus.emit(Event.BroadcastRequest, BroadcastRequestPayload(id, signed.txHex))
                        },
                    )
                }
        }
    }
}

private fun pickSelected(
    utxos: List<WalletUtxoRow>,
    keys: Set<String>,
): PickUtxos<WalletUtxoRow> =
    io.bluewallet.blueberry.parse
        .pickUtxosByKeys(utxos, keys.toList()) { it.key }

@Composable
private fun UtxoStep(
    modifier: Modifier = Modifier,
    utxos: List<WalletUtxoRow>,
    selectedKeys: Set<String>,
    selectedSum: Long,
    renameKey: String?,
    renameDraft: String,
    onRenameDraft: (String) -> Unit,
    onToggle: (String) -> Unit,
    onBeginRename: (WalletUtxoRow) -> Unit,
    onSaveRename: () -> Unit,
    onContinue: () -> Unit,
) {
    Text(
        text = "Select UTXOs · rename on a row",
        color = BwColors.InkMuted,
        fontFamily = BwFontFamily,
        fontSize = BwType.CaptionSize,
        fontWeight = BwType.Caption,
    )
    BtcAmountText(
        sats = selectedSum,
        color = BwColors.Ink,
        modifier = Modifier.alpha(if (selectedKeys.isNotEmpty()) 1f else 0f),
    )
    if (renameKey != null) {
        OutlinedTextField(
            value = renameDraft,
            onValueChange = onRenameDraft,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("UTXO name") },
        )
        PillButton(text = "Save name", onClick = onSaveRename)
    }
    if (utxos.isEmpty()) {
        Text(
            text = "No UTXOs",
            color = BwColors.InkMuted,
            fontFamily = BwFontFamily,
            fontSize = BwType.BodySize,
        )
    } else {
        val maxValue = utxos.maxOf { it.valueSats }
        LazyColumn(modifier = modifier.fillMaxWidth()) {
            items(utxos, key = { it.key }) { u ->
                val checked = u.key in selectedKeys
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(u.key) }
                            .padding(vertical = BwSpace.Gap),
                    horizontalArrangement = Arrangement.spacedBy(BwSpace.Gap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BwCheckbox(
                        checked = checked,
                        onCheckedChange = { onToggle(u.key) },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        BtcAmountText(sats = u.valueSats, color = BwColors.Ink)
                        Text(
                            text = listOfNotNull(u.outpointShort, u.ageLabel.trim(), u.name).joinToString("  "),
                            color = BwColors.InkMuted,
                            fontFamily = BwFontFamily,
                            fontSize = BwType.CaptionSize,
                        )
                        HorizontalProgressBar(percent = utxoValuePercent(u.valueSats, maxValue))
                    }
                    Text(
                        text = "Rename",
                        color = BwColors.Link,
                        fontFamily = BwFontFamily,
                        fontSize = BwType.CaptionSize,
                        modifier = Modifier.clickable { onBeginRename(u) },
                    )
                }
            }
        }
    }
    PillButton(
        text = "Continue",
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DetailsStep(
    selectedSum: Long,
    address: String,
    amount: String,
    label: String,
    invalid: SendField?,
    onAddress: (String) -> Unit,
    onAmount: (String) -> Unit,
    onLabel: (String) -> Unit,
    onScan: () -> Unit,
    onContinue: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(BwSpace.Gap)) {
        Text("Selected", color = BwColors.InkMuted, fontFamily = BwFontFamily, fontSize = BwType.CaptionSize)
        BtcAmountText(sats = selectedSum, color = BwColors.Ink)
    }
    OutlinedTextField(
        value = address,
        onValueChange = onAddress,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = invalid == SendField.Address,
        label = { Text("Address") },
        placeholder = { Text("bc1…") },
        trailingIcon = {
            Text(
                text = "Scan",
                color = BwColors.Link,
                fontFamily = BwFontFamily,
                fontSize = BwType.CaptionSize,
                modifier = Modifier.clickable(onClick = onScan),
            )
        },
    )
    OutlinedTextField(
        value = amount,
        onValueChange = onAmount,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = invalid == SendField.Amount,
        label = { Text("Amount") },
        placeholder = { Text("0.00000000 or MAX") },
    )
    OutlinedTextField(
        value = label,
        onValueChange = onLabel,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = invalid == SendField.Label,
        label = { Text("Payment label") },
        placeholder = { Text("groceries") },
    )
    PillButton(text = "Continue", onClick = onContinue, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun FeeRateStep(
    feeRate: String,
    error: String?,
    onFeeRate: (String) -> Unit,
    onContinue: () -> Unit,
) {
    OutlinedTextField(
        value = feeRate,
        onValueChange = onFeeRate,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = error != null,
        label = { Text("Fee rate (sat/vB)") },
        placeholder = { Text("1") },
    )
    if (error != null) {
        Text(error, color = BwColors.Danger, fontFamily = BwFontFamily, fontSize = BwType.CaptionSize)
    }
    PillButton(text = "Continue", onClick = onContinue, modifier = Modifier.fillMaxWidth())
}
