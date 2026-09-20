@file:OptIn(ExperimentalComposeUiApi::class)

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import io.bluewallet.blueberry.bus.BroadcastCancelPayload
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
import io.bluewallet.blueberry.ui.overlayBackStack
import io.bluewallet.blueberry.wallet.BuildSendResult
import io.bluewallet.blueberry.wallet.PsbtSendResult
import io.bluewallet.blueberry.wallet.SendInputUtxo
import io.bluewallet.blueberry.wallet.SignedSendResult
import io.bluewallet.blueberry.wallet.loadWalletSecret
import io.bluewallet.blueberry.wallet.parseWalletSecret
import kotlinx.coroutines.launch

private enum class SendStep { Utxos, Details, PrivateSend, Preview }

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
    var previewUtxos by remember { mutableStateOf<List<SendInputUtxo>>(emptyList()) }
    var snap by remember { mutableStateOf(runtime.walletTxsStore.get()) }
    var broadcast by remember { mutableStateOf(runtime.broadcastStore.get()) }
    var address by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var invalidField by remember { mutableStateOf<SendField?>(null) }
    var feeRate by remember { mutableStateOf("") }
    var feeError by remember { mutableStateOf<String?>(null) }
    var cancelArmedForId by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var privateSend by remember { mutableStateOf(false) }
    var privateSession by remember { mutableStateOf<PrivateSendSession?>(null) }
    var privateSigned by remember { mutableStateOf<SignedSendResult?>(null) }
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

    fun applySendPayload(payload: String) {
        val next =
            applyBip21Payload(
                payload,
                SendFormFields(address = address, amount = amount, label = label, privateSend = privateSend),
            )
        address = next.address
        amount = next.amount
        label = next.label
        if (invalidField == SendField.Address ||
            invalidField == SendField.Amount ||
            invalidField == SendField.Label
        ) {
            invalidField = null
        }
    }

    fun goBack() {
        val previewTxHex = (preview as? SignedSendResult)?.txHex ?: privateSigned?.txHex
        val ownsJob = previewTxHex != null && broadcast.txHex == previewTxHex
        val onBroadcastStep = step == SendStep.Preview || step == SendStep.PrivateSend
        if (onBroadcastStep && ownsJob) {
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
        if (scanning) {
            scanning = false
            return
        }
        when (step) {
            SendStep.Utxos -> onBack()
            SendStep.Details -> step = SendStep.Utxos
            SendStep.PrivateSend -> {
                privateSigned = null
                step = SendStep.Details
            }
            SendStep.Preview -> {
                preview = null
                step = SendStep.Details
            }
        }
    }
    BackHandler { goBack() }
    val latestGoBack by rememberUpdatedState { goBack() }
    DisposableEffect(Unit) {
        val cb: () -> Unit = { latestGoBack() }
        overlayBackStack.push(cb)
        onDispose { overlayBackStack.pop(cb) }
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
        ScreenHeader(
            title = if (step == SendStep.PrivateSend) "Private Send" else "Send",
            onBack = { goBack() },
        )
        when (step) {
            SendStep.Utxos ->
                UtxoStep(
                    modifier = Modifier.weight(1f),
                    utxos = snap.utxos,
                    selectedKeys = selectedKeys,
                    onToggle = { key ->
                        selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
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
                            applySendPayload(payload)
                            scanning = false
                        },
                    )
                } else {
                    DetailsStep(
                        form =
                            DetailsForm(
                                selectedSum = selectedSum,
                                address = address,
                                amount = amount,
                                label = label,
                                feeRate = feeRate,
                                privateSend = privateSend,
                                invalid = invalidField,
                                feeError = feeError,
                            ),
                        onEvent = handler@{ event ->
                            when (event) {
                                is DetailsEvent.Address -> applySendPayload(event.value)
                                is DetailsEvent.Amount -> {
                                    amount = event.value
                                    if (invalidField == SendField.Amount) invalidField = null
                                }
                                is DetailsEvent.Label -> {
                                    label = event.value
                                    if (invalidField == SendField.Label) invalidField = null
                                }
                                is DetailsEvent.FeeRate -> {
                                    feeRate = event.value
                                    feeError = null
                                    if (invalidField == SendField.FeeRate) invalidField = null
                                }
                                is DetailsEvent.PrivateSend -> privateSend = event.checked
                                DetailsEvent.Scan -> scanning = true
                                DetailsEvent.Max -> {
                                    amount = "MAX"
                                    if (invalidField == SendField.Amount) invalidField = null
                                }
                                DetailsEvent.Continue -> {
                                    when (val result = validateSendDetails(address, amount, label, selectedSum, feeRate)) {
                                        is SendDetailsValidation.Ok -> {
                                            details = result.details
                                            invalidField = null
                                            if (sendContinueTarget(privateSend) is SendContinueTarget.PrivateSend) {
                                                val signErr =
                                                    runCatching {
                                                        privateSendSigningError(parseWalletSecret(loadWalletSecret(db)).kind)
                                                    }.getOrElse { "Private send needs a signing wallet" }
                                                if (signErr != null) {
                                                    feeError = signErr
                                                    return@handler
                                                }
                                                val refund = currentReceiveAddress(runtime, db)
                                                if (refund.isNullOrEmpty()) {
                                                    feeError = "missing refund address"
                                                    return@handler
                                                }
                                                val pickedPrivate = pickSelected(snap.utxos, selectedKeys)
                                                if (pickedPrivate is PickUtxos.Error) {
                                                    feeError = pickedPrivate.error
                                                    return@handler
                                                }
                                                val selectedPrivate = (pickedPrivate as PickUtxos.Ok).selected
                                                privateSession =
                                                    PrivateSendSession(
                                                        destination = result.details.toAddress,
                                                        label = result.details.paymentLabel,
                                                        feeRateSatPerVb = result.feeRateSatPerVb,
                                                        amountSats = result.details.amountSats,
                                                        utxos =
                                                            selectedPrivate.map {
                                                                SendInputUtxo(it.txid, it.vout, it.valueSats, it.scriptPubKey)
                                                            },
                                                        refundAddress = refund,
                                                    )
                                                feeError = null
                                                privateSigned = null
                                                step = SendStep.PrivateSend
                                                return@handler
                                            }
                                            val w = wallet
                                            if (w == null) {
                                                feeError = "missing send details"
                                                return@handler
                                            }
                                            val picked = pickSelected(snap.utxos, selectedKeys)
                                            if (picked is PickUtxos.Error) {
                                                feeError = picked.error
                                                return@handler
                                            }
                                            val selected = (picked as PickUtxos.Ok).selected
                                            val d = result.details
                                            try {
                                                val built =
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
                                                            feeRateSatPerVb = result.feeRateSatPerVb,
                                                        ),
                                                    )
                                                val txid =
                                                    when (built) {
                                                        is SignedSendResult -> built.txid
                                                        is PsbtSendResult -> built.txid
                                                    }
                                                val changeVouts =
                                                    when (built) {
                                                        is SignedSendResult -> built.changeVouts
                                                        is PsbtSendResult -> built.changeVouts
                                                    }
                                                savePaymentLabel(db, txid, d.paymentLabel, changeVouts)
                                                preview = built
                                                previewUtxos =
                                                    selected.map {
                                                        SendInputUtxo(it.txid, it.vout, it.valueSats, it.scriptPubKey)
                                                    }
                                                previewInputSum = selected.sumOf { it.valueSats }
                                                feeError = null
                                                cancelArmedForId = null
                                                step = SendStep.Preview
                                            } catch (err: Throwable) {
                                                feeError = err.message ?: err.toString()
                                            }
                                        }
                                        is SendDetailsValidation.Invalid -> invalidField = result.field
                                    }
                                }
                            }
                        },
                    )
                }
            SendStep.PrivateSend -> {
                val session = privateSession
                if (session == null) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth())
                } else {
                    PrivateSendStep(
                        session = session,
                        host =
                            PrivateSendHost(
                                db = db,
                                wallet = wallet,
                                refundAddress = session.refundAddress,
                                broadcast = broadcast,
                                callbacks =
                                    PrivateSendCallbacks(
                                        onSigned = { privateSigned = it },
                                        onBroadcast = { signed, row ->
                                            val req =
                                                enqueueArmedBroadcast(runtime.broadcastStore, signed.txHex) {
                                                    runtime.armPrivateSendPersist(row)
                                                } ?: return@PrivateSendCallbacks
                                            runtime.bus.emit(Event.BroadcastRequest, req)
                                        },
                                        finish =
                                            BroadcastFinishActions(
                                                onDone = {
                                                    runtime.broadcastStore.reset()
                                                    onBack()
                                                },
                                                onRetry = {
                                                    val hex = privateSigned?.txHex ?: return@BroadcastFinishActions
                                                    val req = enqueueBroadcast(runtime.broadcastStore, hex)
                                                    if (req != null) runtime.bus.emit(Event.BroadcastRequest, req)
                                                },
                                            ),
                                    ),
                            ),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
            SendStep.Preview ->
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    PreviewStep(
                        preview = preview,
                        details = details,
                        inputSum = previewInputSum,
                        broadcast = broadcast,
                        actions =
                            PreviewActions(
                                onBroadcast = {
                                    val signed = preview as? SignedSendResult ?: return@PreviewActions
                                    val dest = details?.toAddress ?: return@PreviewActions
                                    val req =
                                        enqueueArmedBroadcast(runtime.broadcastStore, signed.txHex) {
                                            runtime.armSendPersist(sendRecord(dest, previewUtxos, signed))
                                        } ?: return@PreviewActions
                                    runtime.bus.emit(Event.BroadcastRequest, req)
                                },
                                finish =
                                    BroadcastFinishActions(
                                        onDone = {
                                            runtime.broadcastStore.reset()
                                            onBack()
                                        },
                                        onRetry = {
                                            val signed = preview as? SignedSendResult ?: return@BroadcastFinishActions
                                            val dest = details?.toAddress ?: return@BroadcastFinishActions
                                            val req =
                                                enqueueArmedBroadcast(runtime.broadcastStore, signed.txHex) {
                                                    runtime.armSendPersist(sendRecord(dest, previewUtxos, signed))
                                                } ?: return@BroadcastFinishActions
                                            runtime.bus.emit(Event.BroadcastRequest, req)
                                        },
                                    ),
                            ),
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
    onToggle: (String) -> Unit,
    onContinue: () -> Unit,
) {
    val selectedSum = utxos.filter { it.key in selectedKeys }.sumOf { it.valueSats }
    Text(
        text = "Select UTXOs",
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

private sealed class DetailsEvent {
    data class Address(
        val value: String,
    ) : DetailsEvent()

    data class Amount(
        val value: String,
    ) : DetailsEvent()

    data class Label(
        val value: String,
    ) : DetailsEvent()

    data class FeeRate(
        val value: String,
    ) : DetailsEvent()

    data class PrivateSend(
        val checked: Boolean,
    ) : DetailsEvent()

    data object Scan : DetailsEvent()

    data object Max : DetailsEvent()

    data object Continue : DetailsEvent()
}

private data class DetailsForm(
    val selectedSum: Long,
    val address: String,
    val amount: String,
    val label: String,
    val feeRate: String,
    val privateSend: Boolean,
    val invalid: SendField?,
    val feeError: String?,
)

@Composable
private fun DetailsStep(
    form: DetailsForm,
    onEvent: (DetailsEvent) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(BwSpace.Gap)) {
        Text("Selected", color = BwColors.InkMuted, fontFamily = BwFontFamily, fontSize = BwType.CaptionSize)
        BtcAmountText(sats = form.selectedSum, color = BwColors.Ink)
    }
    OutlinedTextField(
        value = form.address,
        onValueChange = { onEvent(DetailsEvent.Address(it)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = form.invalid == SendField.Address,
        label = { Text("Address") },
        placeholder = { Text("bc1…") },
        trailingIcon = { FieldTrailingAction("Scan") { onEvent(DetailsEvent.Scan) } },
    )
    AmountField(form = form, onEvent = onEvent)
    OutlinedTextField(
        value = form.label,
        onValueChange = { onEvent(DetailsEvent.Label(it)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = form.invalid == SendField.Label,
        label = { Text("Payment label") },
        placeholder = { Text("groceries") },
    )
    OutlinedTextField(
        value = form.feeRate,
        onValueChange = { onEvent(DetailsEvent.FeeRate(it)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = form.invalid == SendField.FeeRate || form.feeError != null,
        label = { Text("Fee rate (sat/vB)") },
        placeholder = { Text("1") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
    if (form.feeError != null) {
        Text(form.feeError, color = BwColors.Danger, fontFamily = BwFontFamily, fontSize = BwType.CaptionSize)
    }
    PrivateSendRow(checked = form.privateSend, onChecked = { onEvent(DetailsEvent.PrivateSend(it)) })
    PillButton(text = "Continue", onClick = { onEvent(DetailsEvent.Continue) }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun FieldTrailingAction(
    text: String,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        color = BwColors.Link,
        fontFamily = BwFontFamily,
        fontSize = BwType.CaptionSize,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun AmountField(
    form: DetailsForm,
    onEvent: (DetailsEvent) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = form.amount,
        onValueChange = { onEvent(DetailsEvent.Amount(it)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = form.invalid == SendField.Amount,
        label = { Text("Amount") },
        placeholder = { Text("0.00000000 or MAX") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        trailingIcon = {
            FieldTrailingAction("MAX") {
                onEvent(DetailsEvent.Max)
                focusManager.clearFocus()
                keyboard?.hide()
            }
        },
    )
}

@Composable
private fun PrivateSendRow(
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onChecked(!checked) },
        horizontalArrangement = Arrangement.spacedBy(BwSpace.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BwCheckbox(checked = checked, onCheckedChange = onChecked)
        Text(
            text = "Private Send (costs extra)",
            color = BwColors.Ink,
            fontFamily = BwFontFamily,
            fontSize = BwType.BodySize,
        )
    }
}
