package io.bluewallet.blueberry

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.bluewallet.blueberry.bus.BroadcastCancelPayload
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.parse.SendBuildParams
import io.bluewallet.blueberry.parse.buildActiveSendTx
import io.bluewallet.blueberry.parse.savePaymentLabel
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.PrivateSendRow
import io.bluewallet.blueberry.wallet.AddressScriptType
import io.bluewallet.blueberry.wallet.PsbtSendResult
import io.bluewallet.blueberry.wallet.SendAmount
import io.bluewallet.blueberry.wallet.SendInputUtxo
import io.bluewallet.blueberry.wallet.SignedSendResult
import io.bluewallet.blueberry.wallet.Wallet
import io.bluewallet.blueberry.wallet.loadWalletSecret
import io.bluewallet.blueberry.wallet.outputScriptFromAddress
import io.bluewallet.blueberry.wallet.parseWalletSecret
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

internal enum class SendStep { Address, Amount, Coins, Review, PrivateSend, Psbt, Broadcast }

/** Coins chosen in coin control before Send skip the coin step while they still cover the payment. */
internal fun stepAfterAmount(
    selectedKeys: Set<String>,
    covered: Boolean,
): SendStep = if (selectedKeys.isNotEmpty() && covered) SendStep.Review else SendStep.Coins

private const val FROZEN_OR_GONE_ERROR = "Some selected coins are frozen or no longer available. Review the selection."

internal class SentSend(
    val signed: SignedSendResult,
    val inputs: List<SendInputUtxo>,
    val receipt: SendReceipt,
)

/**
 * State and actions for one send. Review builds the real transaction; [seed] keeps the
 * change type and index the same across rebuilds so editing the fee never burns HD indexes.
 */
@Suppress("TooManyFunctions")
internal class SendFlow(
    private val runtime: PeersRuntime,
    val db: Database,
    private val scope: CoroutineScope,
    private val close: () -> Unit,
    initialSelectedKeys: Set<String> = emptySet(),
) {
    val wallet: Wallet? get() = runtime.wallet
    val seed = Random.nextLong()
    var step by mutableStateOf(SendStep.Address)
    var address by mutableStateOf("")
    var label by mutableStateOf("")
    var labelError by mutableStateOf(false)
    var amount by mutableStateOf("")
    var selectedKeys by mutableStateOf(initialSelectedKeys)

    /** True when Review was reached straight from Amount, so Back skips the coin step too. */
    private var skippedCoins = false
    var feeRate by mutableStateOf(loadSendFeeRate(db))
    var feeSheetOpen by mutableStateOf(false)
    var feeDraft by mutableStateOf("")
    var build by mutableStateOf<ReviewBuild>(ReviewBuild.Idle)
    var sent by mutableStateOf<SentSend?>(null)
    var scanning by mutableStateOf(false)
    var cancelArmedForId by mutableStateOf<String?>(null)
    var privateSession by mutableStateOf<PrivateSendSession?>(null)
    var privateSigned by mutableStateOf<SignedSendResult?>(null)
    var snap by mutableStateOf(runtime.walletTxsStore.get())
    var broadcast by mutableStateOf(runtime.broadcastStore.get())
    var changeScript by mutableStateOf(placeholderOutputScript(AddressScriptType.P2WPKH))

    val privateSendBlock: String? =
        runCatching { privateSendSigningError(parseWalletSecret(loadWalletSecret(db)).kind) }
            .getOrElse { "no wallet" }
            ?.let { "Needs a wallet that can sign." }

    val recipient: SendRecipient get() = SendRecipient(address.trim(), label.trim())
    val spendableRows: List<WalletUtxoRow> get() = snap.utxos.filter { !it.isFrozen }
    val walletTotalSats: Long get() = spendableRows.sumOf { it.valueSats }
    val amountSats: SendAmount? get() = (checkSendAmount(amount, walletTotalSats) as? SendAmountCheck.Ok)?.amount
    val selectedRows: List<WalletUtxoRow> get() = spendableRows.filter { it.key in selectedKeys }
    val selectedInputs: List<SendInputUtxo>
        get() = selectedRows.map { SendInputUtxo(it.txid, it.vout, it.valueSats, it.scriptPubKey) }
    private val toScript: ByteArray? get() = runCatching { outputScriptFromAddress(recipient.address) }.getOrNull()

    fun coverage(): SendCoverage {
        val to = toScript
        val amt = amountSats
        if (to == null || amt == null) return SendCoverage(0, 0, false)
        return sendCoverage(selectedInputs, amt, feeRate, to, changeScript)
    }

    fun feeCheck(): FeeDraftCheck {
        val to = toScript
        val amt = amountSats
        return checkFeeDraft(feeDraft) { rate ->
            if (to == null || amt == null) SendFeeEstimate.Short(0) else sendFeeEstimate(selectedInputs, amt, rate, to, changeScript)
        }
    }

    suspend fun loadChangeScript() {
        val w = runtime.wallet ?: return
        changeScript = withContext(Dispatchers.Default) { sendChangeScript(db, w, seed) }
    }

    /** Builds and signs (or makes a PSBT) off the main thread. Cancelling the caller drops the result. */
    suspend fun rebuild() {
        val rate = feeRate
        val w = runtime.wallet
        val amt = amountSats
        if (rate == null || amt == null || w == null) {
            build = if (rate == null || amt == null) ReviewBuild.Idle else ReviewBuild.Failed("Wallet isn't loaded yet.")
            return
        }
        if (!selectionSpendable()) {
            build = ReviewBuild.Failed(FROZEN_OR_GONE_ERROR)
            return
        }
        val params = SendBuildParams(selectedInputs, recipient.address, amt, rate)
        build = ReviewBuild.Building
        build =
            runCatching {
                ReviewBuild.Built(withContext(Dispatchers.Default) { buildActiveSendTx(db, w, params, Random(seed)) })
            }.getOrElse {
                if (it is CancellationException) throw it
                ReviewBuild.Failed(it.message ?: it.toString())
            }
    }

    /** The persisted freeze list wins over a stale snapshot row. */
    private suspend fun selectionSpendable(): Boolean {
        val storedFrozen = withContext(Dispatchers.Default) { db.utxoFrozen.list().toSet() }
        return spendableSelectedKeys(snap.utxos, selectedKeys, storedFrozen).size == selectedKeys.size
    }

    fun applyPayload(payload: String) {
        val next = applyBip21Payload(payload, SendFormFields(address = address, amount = amount, label = label))
        address = next.address
        amount = next.amount
        label = next.label
        if (label.isNotBlank()) labelError = false
    }

    fun continueFromAddress() {
        when (validateSendAddress(address, label)) {
            SendField.Address -> {}
            SendField.Label -> labelError = true
            null -> {
                labelError = false
                step = SendStep.Amount
            }
        }
    }

    fun continueFromAmount() {
        if (amountSats == null) return
        val next = stepAfterAmount(selectedKeys, coverage().covered)
        skippedCoins = next == SendStep.Review
        step = next
    }

    /** Keeps the selection to coins the wallet still holds and can spend. */
    fun updateSnap(next: WalletTxsSnapshot) {
        snap = next
        val spendable = spendableRows.map { it.key }.toSet()
        val kept = selectedKeys.intersect(spendable)
        if (kept != selectedKeys) selectedKeys = kept
    }

    fun toggleCoin(key: String) {
        if (spendableRows.none { it.key == key }) return
        selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
    }

    fun openFeeSheet() {
        feeDraft = feeRate?.let(::formatFeeRate).orEmpty()
        feeSheetOpen = true
    }

    fun applyFee(rate: Double) {
        feeRate = rate
        feeSheetOpen = false
    }

    fun addCoins() {
        feeSheetOpen = false
        skippedCoins = false
        step = SendStep.Coins
    }

    /** The one commit point: label, fee memory, then broadcast or PSBT. */
    fun send() {
        val result = (build as? ReviewBuild.Built)?.result
        val rate = feeRate
        val amt = amountSats
        if (result == null || rate == null || amt == null) return
        val built = builtSend(result)
        val to = recipient
        editLabels(db) { savePaymentLabel(db, built.txid, to.label, built.changeVouts) }
        uploadLabels(scope, db)
        saveSendFeeRate(db, rate)
        when (result) {
            is PsbtSendResult -> step = SendStep.Psbt
            is SignedSendResult -> {
                val inputs = selectedInputs
                val paid = sendPaidSats(amt, inputs.sumOf { it.valueSats }, built.feeSats, built.changeSats)
                sent = SentSend(result, inputs, SendReceipt(to, paid, built.feeSats, built.txid))
                cancelArmedForId = null
                step = SendStep.Broadcast
                broadcastSent()
            }
        }
    }

    fun broadcastSent() {
        val s = sent ?: return
        val req =
            enqueueArmedBroadcast(runtime.broadcastStore, s.signed.txHex) {
                runtime.armSendPersist(sendRecord(s.receipt.to.address, s.inputs, s.signed))
            } ?: return
        runtime.bus.emit(Event.BroadcastRequest, req)
    }

    fun finish() {
        runtime.broadcastStore.reset()
        close()
    }

    fun openPrivateSend() {
        val rate = feeRate
        val amt = amountSats
        val refund = currentReceiveAddress(runtime, db)
        when {
            rate == null -> openFeeSheet()
            amt == null || refund.isNullOrEmpty() || privateSendBlock != null -> {}
            else -> {
                privateSession = PrivateSendSession(recipient.address, recipient.label, rate, amt, selectedInputs, refund)
                privateSigned = null
                step = SendStep.PrivateSend
            }
        }
    }

    fun privateBroadcast(
        signed: SignedSendResult,
        row: PrivateSendRow,
    ) {
        feeRate?.let { saveSendFeeRate(db, it) }
        val req = enqueueArmedBroadcast(runtime.broadcastStore, signed.txHex) { runtime.armPrivateSendPersist(row) } ?: return
        runtime.bus.emit(Event.BroadcastRequest, req)
    }

    fun privateRetry() {
        val hex = privateSigned?.txHex ?: return
        val req = enqueueBroadcast(runtime.broadcastStore, hex)
        if (req != null) runtime.bus.emit(Event.BroadcastRequest, req)
    }

    fun cancelling(): Boolean = cancelArmedForId != null && cancelArmedForId == broadcast.id && broadcastJobInFlight(broadcast.phase)

    /** Back, swipe or Cancel. While our broadcast runs: first press cancels, second closes. */
    fun goBack() {
        val ownedHex =
            when (step) {
                SendStep.Broadcast -> sent?.signed?.txHex
                SendStep.PrivateSend -> privateSigned?.txHex
                else -> null
            }
        if (broadcastOwns(broadcast, ownedHex) && escapeBroadcast()) return
        val finished = broadcast.phase == "success" || broadcast.phase == "error"
        if (finished) runtime.broadcastStore.reset()
        when {
            scanning -> scanning = false
            feeSheetOpen -> feeSheetOpen = false
            step == SendStep.Broadcast && finished && broadcastOwns(broadcast, ownedHex) -> close()
            else -> stepBack()
        }
    }

    private fun escapeBroadcast(): Boolean =
        when (inFlightBroadcastEscape(broadcast.phase, broadcast.id, cancelArmedForId)) {
            BroadcastEscape.Cancel -> {
                runtime.bus.emit(Event.BroadcastCancel, BroadcastCancelPayload(broadcast.id!!))
                cancelArmedForId = broadcast.id
                true
            }
            BroadcastEscape.ForceClose -> {
                close()
                true
            }
            BroadcastEscape.Ignore -> false
        }

    private fun stepBack() {
        when (step) {
            SendStep.Address -> close()
            SendStep.Amount -> step = SendStep.Address
            SendStep.Coins -> step = SendStep.Amount
            SendStep.Review -> step = if (skippedCoins) SendStep.Amount else SendStep.Coins
            SendStep.PrivateSend -> {
                privateSigned = null
                step = SendStep.Review
            }
            SendStep.Psbt -> step = SendStep.Review
            SendStep.Broadcast -> {
                sent = null
                step = SendStep.Review
            }
        }
    }
}
