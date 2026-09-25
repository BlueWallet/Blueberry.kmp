package io.bluewallet.blueberry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.bluewallet.blueberry.parse.SendBuildParams
import io.bluewallet.blueberry.parse.buildActiveSendTx
import io.bluewallet.blueberry.parse.savePaymentLabel
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.PrivateSendRow
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.ProgressMetricCard
import io.bluewallet.blueberry.wallet.SendAmount
import io.bluewallet.blueberry.wallet.SignedSendResult
import io.bluewallet.blueberry.wallet.Wallet
import io.bluewallet.blueberry.wallet.estimateSendFeeSats
import io.bluewallet.blueberry.wallet.outputScriptFromAddress
import io.bluewallet.echalote.FetchProgressListener
import kotlinx.coroutines.launch

class PrivateSendCallbacks(
    val onSigned: (SignedSendResult) -> Unit,
    val onBroadcast: (SignedSendResult, PrivateSendRow) -> Unit,
    val finish: BroadcastFinishActions,
)

class PrivateSendHost(
    val db: Database,
    val wallet: Wallet?,
    val refundAddress: String,
    val broadcast: BroadcastSnapshot,
    val callbacks: PrivateSendCallbacks,
)

@Composable
fun PrivateSendStep(
    session: PrivateSendSession,
    host: PrivateSendHost,
    modifier: Modifier = Modifier,
) {
    val client = remember { RocketxClient(BuildSecrets.ROCKETX_API_KEY) }
    var ui by remember { mutableStateOf<PrivateSendUi>(PrivateSendUi.Loading()) }
    val scope = rememberCoroutineScope()
    val onProgress =
        remember {
            FetchProgressListener { percent, stage ->
                ui = PrivateSendUi.Loading(percent, stage)
            }
        }
    DisposableEffect(client) {
        onDispose { client.cancel() }
    }

    LaunchedEffect(session) {
        ui = PrivateSendUi.Loading()
        ui = loadPrivateSend(client, session, host, onProgress)
        val ready = ui as? PrivateSendUi.Deposit
        if (ready != null) host.callbacks.onSigned(ready.signed)
    }

    PrivateSendBody(
        session = session,
        ui = ui,
        host = host,
        modifier = modifier,
        onQuote = { quote ->
            scope.launch {
                ui = PrivateSendUi.Loading()
                ui = swapPrivateSend(client, session, quote, host, onProgress)
                val ready = ui as? PrivateSendUi.Deposit
                if (ready != null) host.callbacks.onSigned(ready.signed)
            }
        },
    )
}

private suspend fun loadPrivateSend(
    client: RocketxClient,
    session: PrivateSendSession,
    host: PrivateSendHost,
    onProgress: FetchProgressListener,
): PrivateSendUi {
    val blocked =
        when {
            BuildSecrets.ROCKETX_API_KEY.isEmpty() -> "missing RocketX API key"
            session.refundAddress.isEmpty() -> "missing refund address"
            else -> null
        }
    if (blocked != null) return PrivateSendUi.Error(blocked)
    return runCatching {
        val quotes = privateSendQuotes(client, session, onProgress)
        when (val action = nextAfterQuotes(quotes)) {
            RocketxQuoteAction.None -> PrivateSendUi.Error("no quotes")
            is RocketxQuoteAction.Choose -> PrivateSendUi.Quotes(action.quotes)
            is RocketxQuoteAction.AutoSwap ->
                swapPrivateSend(client, session, action.quote, host, onProgress)
        }
    }.getOrElse { PrivateSendUi.Error(it.message ?: it.toString()) }
}

private suspend fun privateSendQuotes(
    client: RocketxClient,
    session: PrivateSendSession,
    onProgress: FetchProgressListener,
): List<RocketxQuote> {
    val selected = session.utxos.sumOf { it.valueSats }
    val fee = privateSendMinerFee(session)
    return when (session.amountSats) {
        is SendAmount.Exact -> client.quote(satsToBtcString(session.amountSats.sats), onProgress)
        SendAmount.Max -> {
            val from = privateSendMaxFromBtc(selected, fee) ?: error("not enough for miner fee")
            client.quoteForSend(from, onProgress)
        }
    }
}

private suspend fun swapPrivateSend(
    client: RocketxClient,
    session: PrivateSendSession,
    quote: RocketxQuote,
    host: PrivateSendHost,
    onProgress: FetchProgressListener,
): PrivateSendUi =
    runCatching {
        val selected = session.utxos.sumOf { it.valueSats }
        val fee = privateSendMinerFee(session)
        if (!privateSendCanPay(quote.fromAmount, selected, fee)) error("not enough for private send")
        val swap =
            client.startSwap(
                quote = quote,
                destinationAddress = session.destination,
                refundAddress = host.refundAddress,
                onProgress = onProgress,
            )
        finishPrivateSend(session, swap, host)
    }.getOrElse { PrivateSendUi.Error(it.message ?: it.toString()) }

internal fun privateSendSwapError(
    session: PrivateSendSession,
    swap: RocketxSwap,
): String? {
    val need = privateSendNeedBtc(session.amountSats)
    val pay = privateSendPayAmount(swap.fromAmount)
    return when {
        need != null && !swapCoversDestination(swap.toAmount, need) -> "get amount below destination"
        pay == null -> "swap missing pay amount"
        else -> null
    }
}

internal fun finishPrivateSend(
    session: PrivateSendSession,
    swap: RocketxSwap,
    host: PrivateSendHost,
): PrivateSendUi {
    val err = privateSendSwapError(session, swap)
    val pay = privateSendBuildAmount(session.amountSats, swap.fromAmount)
    val w = host.wallet
    return when {
        err != null -> PrivateSendUi.Error(err)
        pay == null || w == null -> PrivateSendUi.Error("missing wallet")
        else -> signedPrivateSend(session, swap, host, pay, w)
    }
}

private fun privateSendMinerFee(session: PrivateSendSession): Long =
    when (session.amountSats) {
        SendAmount.Max -> estimatePrivateSendMaxFee(session.utxos, session.feeRateSatPerVb)
        is SendAmount.Exact ->
            estimateSendFeeSats(
                session.utxos,
                session.feeRateSatPerVb,
                listOf(PRIVATE_SEND_DEPOSIT_SCRIPT, outputScriptFromAddress(session.refundAddress)),
            )
    }

private fun signedPrivateSend(
    session: PrivateSendSession,
    swap: RocketxSwap,
    host: PrivateSendHost,
    pay: SendAmount,
    wallet: Wallet,
): PrivateSendUi {
    val built =
        buildActiveSendTx(
            host.db,
            wallet,
            SendBuildParams(
                utxos = session.utxos,
                toAddress = swap.address,
                amountSats = pay,
                feeRateSatPerVb = session.feeRateSatPerVb,
            ),
        )
    val signed = built as? SignedSendResult
    val fail =
        when {
            signed == null -> "wallet cannot sign"
            session.amountSats is SendAmount.Max ->
                privateSendMaxSignError(
                    signed.changeSats,
                    session.utxos.sumOf { it.valueSats } - signed.feeSats,
                    swap.fromAmount,
                )
            else -> null
        }
    if (signed == null || fail != null) return PrivateSendUi.Error(fail ?: "wallet cannot sign")
    savePaymentLabel(host.db, signed.txid, session.label, signed.changeVouts)
    return PrivateSendUi.Deposit(
        swap = swap,
        signed = signed,
        refundAddress = host.refundAddress,
        needBtc = privateSendNeedBtc(session.amountSats).orEmpty(),
        label = session.label,
    )
}

@Composable
private fun PrivateSendBody(
    session: PrivateSendSession,
    ui: PrivateSendUi,
    host: PrivateSendHost,
    onQuote: (RocketxQuote) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val state = ui) {
            is PrivateSendUi.Loading ->
                ProgressMetricCard(
                    label = "Preparing private send...",
                    value = "${state.percent}%",
                    percent = state.percent,
                    caption = "Tor: ${state.stage}",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = BwSpace.Gap),
                )
            is PrivateSendUi.Error ->
                Text(state.message, color = BwColors.Danger, fontFamily = BwFontFamily, fontSize = BwType.BodySize)
            is PrivateSendUi.Deposit ->
                PrivateSendDeposit(
                    state = state,
                    broadcast = host.broadcast,
                    onBroadcast = {
                        host.callbacks.onBroadcast(
                            state.signed,
                            privateSendRecord(session, state.swap, state.signed),
                        )
                    },
                    finish = host.callbacks.finish,
                )
            is PrivateSendUi.Quotes ->
                PrivateSendQuotes(state.quotes, onQuote)
        }
    }
}

@Composable
private fun PrivateSendQuotes(
    quotes: List<RocketxQuote>,
    onQuote: (RocketxQuote) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(BwSpace.Gap),
    ) {
        items(quotes, key = { it.rateId }) { quote ->
            Column(
                modifier = Modifier.fillMaxWidth().clickable { onQuote(quote) },
            ) {
                Text(quote.venue, color = BwColors.Ink, fontFamily = BwFontFamily, fontSize = BwType.BodySize)
                Text(
                    text = "pay ${quote.fromAmount} → ${quote.toAmount} BTC",
                    color = BwColors.InkMuted,
                    fontFamily = BwFontFamily,
                    fontSize = BwType.CaptionSize,
                )
            }
        }
    }
}
