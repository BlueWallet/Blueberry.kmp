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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import io.bluewallet.blueberry.bus.BroadcastCancelPayload
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.parse.PickUtxos
import io.bluewallet.blueberry.parse.SendBuildParams
import io.bluewallet.blueberry.parse.buildActiveSendTx
import io.bluewallet.blueberry.parse.savePaymentLabel
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwCheckbox
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
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
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
fun SendScreen(
    runtime: PeersRuntime,
    db: Database,
    onBack: () -> Unit,
    initialSelectedKeys: Set<String> = emptySet(),
    onChooseCoins: () -> Unit = {},
    onSelectedKeysChange: (Set<String>) -> Unit = {},
    onFeeRateChange: (Double?) -> Unit = {},
) {
    val wallet = runtime.wallet
    // Form fields use rememberSaveable so the Send back-stack entry keeps them across the
    // coin picker; popping Send discards the draft for the next payment.
    var stepName by rememberSaveable {
        mutableStateOf(if (initialSelectedKeys.isNotEmpty()) SendStep.Details.name else SendStep.Utxos.name)
    }
    val step = SendStep.entries.firstOrNull { it.name == stepName } ?: SendStep.Utxos
    var selectedKeys by remember { mutableStateOf(initialSelectedKeys) }
    var details by remember { mutableStateOf<SendDetails?>(null) }
    var preview by remember { mutableStateOf<BuildSendResult?>(null) }
    var previewInputSum by remember { mutableStateOf(0L) }
    var previewUtxos by remember { mutableStateOf<List<SendInputUtxo>>(emptyList()) }
    var snap by remember { mutableStateOf(runtime.walletTxsStore.get()) }
    var broadcast by remember { mutableStateOf(runtime.broadcastStore.get()) }
    var address by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var invalidField by remember { mutableStateOf<SendField?>(null) }
    var feeRate by rememberSaveable { mutableStateOf("") }
    var feeError by remember { mutableStateOf<String?>(null) }
    var selectionNeedsAck by remember { mutableStateOf(false) }
    var cancelArmedForId by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var privateSend by rememberSaveable { mutableStateOf(false) }
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
    val selectedSum =
        snap.utxos.filter { it.key in selectedKeys && !it.isFrozen }.sumOf { it.valueSats }
    LaunchedEffect(selectedKeys) {
        onSelectedKeysChange(selectedKeys)
        if (isInsufficientSelectionError(feeError)) feeError = null
    }
    LaunchedEffect(snap.utxos) {
        val live = snap.utxos.map { it.key }.toSet()
        val frozen =
            snap.utxos
                .filter { it.isFrozen }
                .map { it.key }
                .toSet()
        val cleaned = selectedKeys.intersect(live) - frozen
        if (cleaned != selectedKeys) {
            val vanished = selectedKeys - live
            if (vanished.isNotEmpty()) selectionNeedsAck = true
            selectedKeys = cleaned
        }
    }
    LaunchedEffect(feeRate) { onFeeRateChange(parseFeeRateSatPerVb(feeRate)) }
    LaunchedEffect(initialSelectedKeys) {
        if (initialSelectedKeys.isNotEmpty() && selectedKeys != initialSelectedKeys) {
            selectedKeys = initialSelectedKeys
            if (step == SendStep.Utxos) stepName = SendStep.Details.name
        }
    }

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
            SendStep.Details -> stepName = SendStep.Utxos.name
            SendStep.PrivateSend -> {
                privateSigned = null
                stepName = SendStep.Details.name
            }
            SendStep.Preview -> {
                preview = null
                stepName = SendStep.Details.name
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
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(BwSpace.Gap),
                ) {
                    Text(
                        text = "Choose which coins this payment spends.",
                        color = BwColors.InkSecondary,
                        fontFamily = BwFontFamily,
                        fontSize = BwType.BodySize,
                    )
                    if (selectedKeys.isNotEmpty()) {
                        Text(
                            text = "${selectedKeys.size} coin(s) selected",
                            color = BwColors.Ink,
                            fontFamily = BwFontFamily,
                            fontSize = BwType.BodySize,
                        )
                    }
                    if (selectionNeedsAck) {
                        Text(
                            text = "Some selected coins are no longer available. Review the selection, then continue.",
                            color = BwColors.Warning,
                            fontFamily = BwFontFamily,
                            fontSize = BwType.BodySize,
                        )
                    }
                    PillButton(
                        text = "Choose coins",
                        onClick = onChooseCoins,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PillButton(
                        text = "Continue",
                        onClick = {
                            if (selectionNeedsAck) {
                                selectionNeedsAck = false
                                return@PillButton
                            }
                            if (selectedKeys.isNotEmpty()) stepName = SendStep.Details.name
                        },
                        enabled = selectedKeys.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
                        modifier = Modifier.weight(1f).fillMaxWidth(),
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
                                costLine = selectionCostLine(snap.utxos, selectedKeys, parseFeeRateSatPerVb(feeRate)),
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
                                DetailsEvent.ChooseCoins -> onChooseCoins()
                                DetailsEvent.Max -> {
                                    amount = "MAX"
                                    if (invalidField == SendField.Amount) invalidField = null
                                }
                                DetailsEvent.Continue -> {
                                    if (selectionNeedsAck) {
                                        selectionNeedsAck = false
                                        feeError = "Selection changed — some coins are gone. Continue again to proceed."
                                        return@handler
                                    }
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
                                                val pickedPrivate = pickSelected(db, snap.utxos, selectedKeys)
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
                                                stepName = SendStep.PrivateSend.name
                                                return@handler
                                            }
                                            val w = wallet
                                            if (w == null) {
                                                feeError = "missing send details"
                                                return@handler
                                            }
                                            val picked = pickSelected(db, snap.utxos, selectedKeys)
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
                                                editLabels(db) {
                                                    savePaymentLabel(db, txid, d.paymentLabel, changeVouts)
                                                }
                                                uploadLabels(scope, db)
                                                preview = built
                                                previewUtxos =
                                                    selected.map {
                                                        SendInputUtxo(it.txid, it.vout, it.valueSats, it.scriptPubKey)
                                                    }
                                                previewInputSum = selected.sumOf { it.valueSats }
                                                feeError = null
                                                cancelArmedForId = null
                                                stepName = SendStep.Preview.name
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

/** [storedFrozen] is the persisted freeze list; it wins even if the snapshot row is stale. */
internal fun spendableSelectedKeys(
    utxos: List<WalletUtxoRow>,
    keys: Set<String>,
    storedFrozen: Set<String> = emptySet(),
): List<String> = utxos.filter { it.key in keys && !it.isFrozen && it.key !in storedFrozen }.map { it.key }

private fun pickSelected(
    db: Database,
    utxos: List<WalletUtxoRow>,
    keys: Set<String>,
): PickUtxos<WalletUtxoRow> {
    val spendable = spendableSelectedKeys(utxos, keys, db.utxoFrozen.list().toSet())
    if (spendable.size != keys.size) return PickUtxos.Error(FROZEN_OR_GONE_ERROR)
    return io.bluewallet.blueberry.parse
        .pickUtxosByKeys(utxos, spendable) { it.key }
}

private const val FROZEN_OR_GONE_ERROR = "Some selected coins are frozen or no longer available. Review the selection."

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

    data object ChooseCoins : DetailsEvent()
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
    val costLine: String? = null,
)

@Composable
private fun DetailsStep(
    form: DetailsForm,
    onEvent: (DetailsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .imePadding()
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(BwSpace.Gap),
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
        FeeRateField(form = form, onEvent = onEvent)
        if (form.feeError != null) {
            Text(form.feeError, color = BwColors.Danger, fontFamily = BwFontFamily, fontSize = BwType.CaptionSize)
            if (isInsufficientSelectionError(form.feeError)) {
                PillButton(
                    text = "Choose more coins",
                    onClick = { onEvent(DetailsEvent.ChooseCoins) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        PrivateSendRow(checked = form.privateSend, onChecked = { onEvent(DetailsEvent.PrivateSend(it)) })
        PillButton(text = "Choose coins", onClick = { onEvent(DetailsEvent.ChooseCoins) }, modifier = Modifier.fillMaxWidth())
        PillButton(text = "Continue", onClick = { onEvent(DetailsEvent.Continue) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FeeRateField(
    form: DetailsForm,
    onEvent: (DetailsEvent) -> Unit,
) {
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
    if (form.costLine != null) {
        Text(form.costLine, color = BwColors.Warning, fontFamily = BwFontFamily, fontSize = BwType.CaptionSize)
    }
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
