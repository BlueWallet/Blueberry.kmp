@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.bluewallet.blueberry

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.bluewallet.blueberry.parse.SendBuildParams
import io.bluewallet.blueberry.parse.buildActiveSendTx
import io.bluewallet.blueberry.parse.savePaymentLabel
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.PrivateSendRow
import io.bluewallet.blueberry.wallet.AddressScriptType
import io.bluewallet.blueberry.wallet.BuildSendResult
import io.bluewallet.blueberry.wallet.PsbtSendResult
import io.bluewallet.blueberry.wallet.SendAmount
import io.bluewallet.blueberry.wallet.SendInputUtxo
import io.bluewallet.blueberry.wallet.SignedSendResult
import io.bluewallet.blueberry.wallet.Wallet
import io.bluewallet.blueberry.wallet.loadWalletSecret
import io.bluewallet.blueberry.wallet.outpointKey
import io.bluewallet.blueberry.wallet.outputScriptFromAddress
import io.bluewallet.blueberry.wallet.parseWalletSecret
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

internal enum class SendStep { Address, Amount, Coins, Review, PrivateSend, Psbt, Broadcast }

/** Coins chosen in coin control before Send skip the coin step while they still cover the payment. */
internal fun stepAfterAmount(
    selectedKeys: Set<String>,
    covered: Boolean,
): SendStep = if (selectedKeys.isNotEmpty() && covered) SendStep.Review else SendStep.Coins

internal const val SELECTION_CHANGED = "Some selected coins are frozen or no longer available. Review the selection."
internal const val SELECTION_HELD = "Some selected coins are in another payment that is still sending. Review the selection."
internal const val WALLET_NOT_LOADED = "Wallet isn't loaded yet."
internal const val HOLDS_LOADING = "Checking which coins other payments are using…"
internal const val BROADCAST_BUSY = "A broadcast is already running. Wait for it to finish."
internal const val DRAFT_MISMATCH = "This transaction no longer matches the coins, amount, or fee on screen."

private class ReadySend(
    val built: ReviewBuild.Built,
    val rate: Double,
    val amount: SendAmount,
    val stamp: SendDraftStamp,
)

internal class SentSend(
    val signed: SignedSendResult,
    val inputs: List<SendInputUtxo>,
    val receipt: SendReceipt,
)

/** One published build. The ticket rejects a result that finished after a newer edit. */
private class BuildCell(
    val ticket: Int,
    val value: ReviewBuild,
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
    var navForward by mutableStateOf(true)
        private set
    var address by mutableStateOf("")
    var label by mutableStateOf("")
    var labelError by mutableStateOf(false)
    var amount by mutableStateOf("")
    var selectedKeys by mutableStateOf(initialSelectedKeys)
    var feeRate by mutableStateOf(loadSendFeeRate(db))
    var feeSheetOpen by mutableStateOf(false)
    var feeDraft by mutableStateOf("")
    var build by mutableStateOf<ReviewBuild>(ReviewBuild.Idle)
    var sent by mutableStateOf<SentSend?>(null)
    var scanning by mutableStateOf(false)
    private var broadcastId: String? = null
    private var privateBroadcastId: String? = null
    var privateSession by mutableStateOf<PrivateSendSession?>(null)
    var privateSigned by mutableStateOf<SignedSendResult?>(null)
    var snap by mutableStateOf(runtime.walletTxsStore.get())
    private val heldAtOpen = runtime.reservedOutpointsNow()
    var heldOutpoints by mutableStateOf(heldAtOpen.keys)
        private set

    /** False until stored sends were read; until then [heldOutpoints] may miss coins, so nothing commits. */
    var heldReady by mutableStateOf(heldAtOpen.complete)
        private set
    var broadcast by mutableStateOf(BroadcastSnapshot())
    var changeScript by mutableStateOf(placeholderOutputScript(AddressScriptType.P2WPKH))
    var selectionNeedsAck by mutableStateOf(false)
        private set

    /** Why the selection shrank: another payment took coins, or they were frozen or spent. */
    var selectionNotice by mutableStateOf(SELECTION_CHANGED)
        private set
    var sendError by mutableStateOf<String?>(null)
        private set

    /** Bumps when the snapshot changes what Review can pay, without a fee or selection edit. */
    var reviewEpoch by mutableStateOf(0)
        private set

    /** Tests substitute this so rebuild can run without signing. [accept] is false once this build was dropped. */
    internal var buildTx: suspend (Wallet, SendBuildParams, () -> Boolean) -> BuildSendResult = { w, params, accept ->
        buildActiveSendTx(db, w, params, Random(seed), accept)
    }

    private val backStack = ArrayDeque<SendStep>()
    private val buildMutex = Mutex()

    @OptIn(ExperimentalAtomicApi::class)
    private val buildCell = AtomicReference(BuildCell(0, ReviewBuild.Idle))

    val privateSendBlock: String? =
        runCatching { privateSendSigningError(parseWalletSecret(loadWalletSecret(db)).kind) }
            .getOrElse { "no wallet" }
            ?.let { "Needs a wallet that can sign." }

    /** The PSBT on screen. Its coins stay reserved until the PSBT step is left. */
    private var psbtTxid: String? = null

    /** The transaction this flow signed or exported. Its own coin hold is not a coin leaving the selection. */
    val ownTxid: String? get() = privateSigned?.txid ?: sent?.signed?.txid ?: psbtTxid

    val recipient: SendRecipient get() = SendRecipient(address.trim(), label.trim())
    val spendableRows: List<WalletUtxoRow>
        get() = snap.utxos.filter { !it.isFrozen && it.key !in heldOutpoints }
    val walletTotalSats: Long get() = spendableRows.sumOf { it.valueSats }
    val coinAvailability: CoinAvailability get() = coinAvailability(snap.utxos, heldOutpoints)

    /** Unfrozen value held by other payments that are still sending. */
    val sendingSats: Long get() = snap.utxos.filter { !it.isFrozen && it.key in heldOutpoints }.sumOf { it.valueSats }
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

    /** Coins, address, amount and fee Review would sign right now. */
    internal fun draftStamp(): SendDraftStamp {
        val rate = feeRate ?: error("fee rate")
        val paying = amountSats ?: error("amount")
        val outpoints =
            selectedInputs
                .map { OutpointStamp(it.txid, it.vout, it.valueSats) }
                .sortedWith(compareBy({ it.txid }, { it.vout }))
        return SendDraftStamp(outpoints, recipient.address, paying, rate)
    }

    private fun stampOrNull(): SendDraftStamp? = if (feeRate == null || amountSats == null) null else draftStamp()

    suspend fun loadChangeScript() {
        val w = runtime.wallet ?: return
        changeScript = withContext(Dispatchers.Default) { sendChangeScript(db, w, seed) }
    }

    /**
     * Builds and signs (or makes a PSBT) off the main thread. [build] is Building before the
     * first suspend, and only one build touches the wallet at a time. A result whose ticket
     * lost to a later edit is dropped.
     */
    suspend fun rebuild() {
        if (selectionNeedsAck) {
            dropBuild()
            return
        }
        val ticket = beginBuild()
        buildMutex.withLock {
            publishBuild(ticket, produceBuild(ticket))
        }
    }

    /** RocketX signs under the same lock as Review, so the two cannot write the wallet together. */
    internal suspend fun signPrivate(params: SendBuildParams): BuildSendResult {
        val w = runtime.wallet ?: error("missing wallet")
        return buildMutex.withLock {
            withContext(Dispatchers.Default) {
                buildActiveSendTx(db, w, params, Random(seed))
            }
        }
    }

    private suspend fun produceBuild(ticket: Int): ReviewBuild {
        val rate = feeRate
        val w = runtime.wallet
        val amt = amountSats
        return if (rate != null && amt != null && w != null) {
            if (selectionSpendable()) buildSigned(w, amt, rate, ticket) else ReviewBuild.Failed(SELECTION_CHANGED)
        } else if (rate == null || amt == null) {
            ReviewBuild.Idle
        } else {
            ReviewBuild.Failed(WALLET_NOT_LOADED)
        }
    }

    private suspend fun buildSigned(
        w: Wallet,
        amt: SendAmount,
        rate: Double,
        ticket: Int,
    ): ReviewBuild {
        val stamp = draftStamp()
        val params = SendBuildParams(selectedInputs, recipient.address, amt, rate)
        return runCatching {
            val signed =
                withContext(Dispatchers.Default) {
                    coroutineContext.ensureActive()
                    buildTx(w, params) { ticketCurrent(ticket) && coroutineContext.isActive }
                }
            ReviewBuild.Built(signed, stamp)
        }.getOrElse {
            if (it is CancellationException) throw it
            ReviewBuild.Failed(it.message ?: it.toString())
        }
    }

    private fun ticketCurrent(ticket: Int): Boolean = buildCell.load().ticket == ticket

    /** The persisted freeze list wins over a stale snapshot row. */
    private suspend fun selectionSpendable(): Boolean {
        if (selectedKeys.size != selectedRows.size) return false
        val storedFrozen = withContext(Dispatchers.Default) { db.utxoFrozen.list().toSet() }
        return spendableSelectedKeys(snap.utxos, selectedKeys, storedFrozen).size == selectedKeys.size
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun dropBuild() {
        while (true) {
            val cur = buildCell.load()
            if (buildCell.compareAndSet(cur, BuildCell(cur.ticket + 1, ReviewBuild.Idle))) {
                build = ReviewBuild.Idle
                return
            }
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun beginBuild(): Int {
        while (true) {
            val cur = buildCell.load()
            val ticket = cur.ticket + 1
            if (buildCell.compareAndSet(cur, BuildCell(ticket, ReviewBuild.Building))) {
                build = ReviewBuild.Building
                return ticket
            }
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun publishBuild(
        ticket: Int,
        next: ReviewBuild,
    ) {
        while (true) {
            val cur = buildCell.load()
            if (cur.ticket != ticket) return
            if (buildCell.compareAndSet(cur, BuildCell(ticket, next))) {
                build = next
                if (next is ReviewBuild.Built || next is ReviewBuild.Failed) sendError = null
                return
            }
        }
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
                pushStep(SendStep.Amount)
            }
        }
    }

    fun continueFromAmount() {
        if (amountSats == null || !heldReady) return
        trimToSpendable()
        pushStep(stepAfterAmount(selectedKeys, coverage().covered))
    }

    fun continueFromCoins() {
        if (amountSats == null || !coverage().covered || !heldReady) return
        selectionNeedsAck = false
        if (backStack.lastOrNull() == SendStep.Review) {
            navForward = false
            step = backStack.removeLast()
        } else {
            pushStep(SendStep.Review)
        }
    }

    fun refreshHeldOutpoints() {
        heldOutpoints = runtime.reservedOutpoints(ownTxid)
        heldReady = true
    }

    /** Keeps the selection to coins the wallet still holds and can spend. The screen reads [held] off the UI thread. */
    fun updateSnap(
        next: WalletTxsSnapshot,
        held: Set<String> = runtime.reservedOutpoints(ownTxid),
    ) {
        heldOutpoints = held
        heldReady = true
        val payable = amountSats
        val before = stampOrNull()
        snap = next
        if (!trimToSpendable() && (amountSats != payable || stampOrNull() != before)) {
            dropBuild()
            reviewEpoch++
        }
    }

    /** Applies a reservation read off the UI thread, then drops a signature that no longer matches. */
    fun applyHeldOutpoints(held: Set<String>) {
        heldOutpoints = held
        heldReady = true
        trimToSpendable()
    }

    /**
     * Last check before a broadcast claims coins: live jobs from memory plus the stored holds already read.
     * Refuses while the stored read is pending, or when another payment holds an input.
     */
    private fun inputsFree(
        inputs: List<SendInputUtxo>,
        ownTxid: String?,
    ): Boolean {
        val now = runtime.reservedOutpointsNow(ownTxid)
        val reserved = heldOutpoints + now.keys
        val taken = inputs.any { outpointKey(it.txid, it.vout) in reserved }
        val known = heldReady && now.complete
        when {
            !known -> {
                sendError = HOLDS_LOADING
                scope.launch { reloadHeld() }
            }
            taken -> {
                applyHeldOutpoints(reserved)
                sendError = SELECTION_HELD
            }
        }
        return known && !taken
    }

    private var holdsGen = 0

    /**
     * Reads holds off the UI thread, then applies them with the latest wallet snapshot. A read overtaken by a
     * later one is dropped, so an older hold set never replaces a newer one.
     */
    suspend fun reloadHeld() {
        val gen = ++holdsGen
        val own = ownTxid
        val held = withContext(Dispatchers.Default) { runtime.reservedOutpoints(own) }
        if (gen != holdsGen) return
        updateSnap(runtime.walletTxsStore.get(), held)
        if (sendError == HOLDS_LOADING) sendError = null
    }

    private fun trimToSpendable(): Boolean {
        val kept = selectedKeys.intersect(spendableRows.map { it.key }.toSet())
        if (kept == selectedKeys) return false
        selectionNotice = if ((selectedKeys - kept).any { it in heldOutpoints }) SELECTION_HELD else SELECTION_CHANGED
        selectedKeys = kept
        selectionNeedsAck = true
        dropBuild()
        return true
    }

    fun toggleCoin(key: String) {
        if (key !in selectedKeys && (!heldReady || spendableRows.none { it.key == key })) return
        selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
    }

    /** Filter and snapshot trimming. A filter only hides rows; the selection stays. */
    fun replaceSelection(keys: Set<String>) {
        if (keys == selectedKeys) return
        selectedKeys = keys
    }

    fun openFeeSheet() {
        feeDraft = feeRate?.let(::formatFeeRate).orEmpty()
        feeSheetOpen = true
    }

    fun applyFee(rate: Double) {
        feeSheetOpen = false
        sendError = null
        if (feeRate == rate) return
        feeRate = rate
        dropBuild()
    }

    fun addCoins() {
        sendError = null
        feeSheetOpen = false
        dropBuild()
        pushStep(SendStep.Coins)
    }

    /** The one commit point: label and fee memory, then broadcast or PSBT. Refuses a stale signature. */
    fun send() {
        val ready = readySend() ?: return
        if (ready.built.draft != ready.stamp) {
            sendError = DRAFT_MISMATCH
            dropBuild()
            reviewEpoch++
            return
        }
        when (val result = ready.built.result) {
            is PsbtSendResult -> sendPsbt(result, ready.rate)
            is SignedSendResult -> sendSigned(result, ready.amount, ready.rate)
        }
    }

    /** Signing happens elsewhere, so the coins are held here while the PSBT is on screen. */
    private fun sendPsbt(
        result: PsbtSendResult,
        rate: Double,
    ) {
        val inputs = selectedInputs
        if (!inputsFree(inputs, result.txid)) return
        runtime.psbtHolds.hold(result.txid, inputs.map { outpointKey(it.txid, it.vout) }.toSet())
        psbtTxid = result.txid
        commit(result, rate)
        sendError = null
        pushStep(SendStep.Psbt)
    }

    private fun readySend(): ReadySend? {
        val built = build as? ReviewBuild.Built
        val rate = feeRate
        val amt = amountSats
        return if (built != null && rate != null && amt != null) {
            if (selectionNeedsAck) null else ReadySend(built, rate, amt, draftStamp())
        } else {
            null
        }
    }

    private fun sendSigned(
        result: SignedSendResult,
        amt: SendAmount,
        rate: Double,
    ) {
        val inputs = selectedInputs
        if (!inputsFree(inputs, result.txid)) return
        val priced = builtSend(result)
        val paid = sendPaidSats(amt, inputs.sumOf { it.valueSats }, priced.feeSats, priced.changeSats)
        val receipt = SendReceipt(recipient, paid, priced.feeSats, priced.txid)
        val req =
            enqueueArmedBroadcast(runtime.broadcastStore, result.txHex, receipt.asBroadcast()) { id ->
                runtime.armSendPersist(id, sendRecord(receipt.to.address, inputs, result))
            } ?: run {
                sendError = BROADCAST_BUSY
                return
            }
        commit(result, rate)
        sent = SentSend(result, inputs, receipt)
        broadcastId = req.id
        sendError = null
        pushStep(SendStep.Broadcast)
        requestBroadcast(runtime, req)
        refreshBroadcast()
    }

    private fun commit(
        result: BuildSendResult,
        rate: Double,
    ) {
        val built = builtSend(result)
        editLabels(db) { savePaymentLabel(db, built.txid, recipient.label, built.changeVouts) }
        uploadLabels(scope, db)
        saveSendFeeRate(db, rate)
    }

    fun broadcastSent() {
        val id = broadcastId
        val blocked = id?.let { retryBlock(it) }
        if (blocked != null) {
            sendError = blocked
            return
        }
        val req = if (id == null) null else rebroadcast(runtime.broadcastStore, id)
        if (req == null) {
            refreshBroadcast()
            if (id != null && broadcast.phase == null) sendError = BROADCAST_BUSY
            return
        }
        sendError = null
        requestBroadcast(runtime, req)
        refreshBroadcast()
    }

    /** Frees the PSBT's coins. Called when the PSBT step is left or the send screen goes away. */
    fun releasePsbt() {
        psbtTxid?.let { runtime.psbtHolds.release(it) }
        psbtTxid = null
    }

    fun finish() {
        releasePsbt()
        leaveBroadcast(if (step == SendStep.PrivateSend) privateBroadcastId else broadcastId)
        if (step == SendStep.PrivateSend) dropBuild()
        close()
    }

    /** Stops this screen's broadcast while no node has it. It then fails, freeing its coins for Retry or Forget. */
    fun stopSending() {
        broadcast.id?.let { stopBroadcast(runtime, it) }
        refreshBroadcast()
    }

    /** Drops a finished failure from the list. A live or sent job stays. */
    fun forgetFailed() {
        val id = broadcast.id
        if (id == null || !broadcastCanBeForgotten(broadcast.phase)) return
        forgetBroadcast(runtime, id)
        dropBuild()
        close()
    }

    private fun retryBlock(id: String): String? = retryRefusalFor(runtime, id, snap)

    fun openPrivateSend() {
        val rate = feeRate
        val amt = amountSats
        val refund = currentReceiveAddress(runtime, db)
        trimToSpendable()
        val held = selectionNeedsAck || amt == null || privateSendBlock != null
        when {
            rate == null -> openFeeSheet()
            held || refund.isNullOrEmpty() -> {}
            else -> {
                privateSession = PrivateSendSession(recipient.address, recipient.label, rate, amt, selectedInputs, refund)
                privateSigned = null
                pushStep(SendStep.PrivateSend)
                refreshBroadcast()
            }
        }
    }

    fun privateBroadcast(
        signed: SignedSendResult,
        row: PrivateSendRow,
    ) {
        val session = privateSession?.takeIf { inputsFree(it.utxos, signed.txid) } ?: return
        val priced = builtSend(signed)
        val paid = sendPaidSats(session.amountSats, session.utxos.sumOf { it.valueSats }, priced.feeSats, priced.changeSats)
        val receipt = SendReceipt(SendRecipient(session.destination, session.label), paid, priced.feeSats, priced.txid)
        val req =
            enqueueArmedBroadcast(runtime.broadcastStore, signed.txHex, receipt.asBroadcast()) { id ->
                runtime.armPrivateSendPersist(id, row)
            }
        if (req == null) {
            privateBroadcastId = sessionId(signed.txHex) ?: privateBroadcastId
            refreshBroadcast()
            return
        }
        privateBroadcastId = req.id
        feeRate?.let { saveSendFeeRate(db, it) }
        sendError = null
        requestBroadcast(runtime, req)
        refreshBroadcast()
    }

    fun privateRetry() {
        val id = privateBroadcastId ?: sessionId(privateSigned?.txHex)
        val blocked = id?.let { retryBlock(it) }
        if (blocked != null) {
            sendError = blocked
            return
        }
        val req = if (id == null) null else rebroadcast(runtime.broadcastStore, id)
        if (req == null) {
            refreshBroadcast()
            if (privateSigned != null && broadcast.phase == null) sendError = BROADCAST_BUSY
            return
        }
        privateBroadcastId = req.id
        sendError = null
        requestBroadcast(runtime, req)
        refreshBroadcast()
    }

    /** Back or swipe. A live broadcast or private send closes and leaves its job running. */
    fun goBack() {
        if (step == SendStep.Broadcast) {
            leaveBroadcast(broadcastId)
            close()
        } else if (!dismissOverlay() && (step != SendStep.PrivateSend || !closeLivePrivateSend())) {
            popStep()
        }
    }

    /** A private payment that is sending or sent: leaving closes the flow and the job keeps going. */
    val privateSendLive: Boolean
        get() {
            val hex = privateSigned?.txHex
            val phase = broadcast.phase
            return step == SendStep.PrivateSend && hex != null && broadcast.txHex == hex && phase != null && phase != "error"
        }

    /** An in-flight or sent private payment closes. A failed one returns to Review. */
    private fun closeLivePrivateSend(): Boolean {
        refreshBroadcast()
        val id = privateBroadcastId ?: sessionId(ownedHex())
        val phase = if (id == null) null else runtime.broadcastStore.get(id)?.phase
        val backToReview = phase == null || phase == "error"
        if (phase == "error") releaseFailedPrivateSelection()
        if (backToReview) return false
        leaveBroadcast(id)
        dropBuild()
        close()
        return true
    }

    /** A failed private payment is no longer signed. A smaller set still needs a look before Send. */
    private fun releaseFailedPrivateSelection() {
        refreshHeldOutpoints()
        dropBuild()
        trimToSpendable()
    }

    fun refreshBroadcast() {
        val id = if (step == SendStep.PrivateSend) privateBroadcastId ?: sessionId(ownedHex()) else broadcastId
        broadcast = id?.let { runtime.broadcastStore.get(it) } ?: BroadcastSnapshot()
    }

    private fun sessionId(txHex: String?): String? =
        txHex?.let { hex ->
            runtime.broadcastStore
                .sessions()
                .find { it.snapshot.txHex == hex }
                ?.snapshot
                ?.id
        }

    /** A successful job leaves the list once its transaction is stored. Closing sooner drops it. */
    private fun leaveBroadcast(id: String?) {
        if (id != null && runtime.broadcastStore.get(id)?.phase == "success") runtime.broadcastStore.dismiss(id)
    }

    private fun ownedHex(): String? =
        when (step) {
            SendStep.Broadcast -> sent?.signed?.txHex
            SendStep.PrivateSend -> privateSigned?.txHex
            else -> null
        }

    private fun dismissOverlay(): Boolean =
        when {
            scanning -> {
                scanning = false
                true
            }
            feeSheetOpen -> {
                feeSheetOpen = false
                true
            }
            else -> false
        }

    private fun pushStep(next: SendStep) {
        navForward = true
        backStack.addLast(step)
        step = next
    }

    init {
        if (snap.utxosReady) trimToSpendable()
    }

    private fun popStep() {
        navForward = false
        if (backStack.isEmpty()) {
            close()
            return
        }
        if (step == SendStep.PrivateSend) privateSigned = null
        if (step == SendStep.Broadcast) sent = null
        if (step == SendStep.Psbt) releasePsbt()
        step = backStack.removeLast()
    }
}

private fun SendReceipt.asBroadcast(): BroadcastReceipt = BroadcastReceipt(txid, to.address, to.label, paidSats, feeSats)
