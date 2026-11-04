@file:Suppress("TooManyFunctions")

package io.bluewallet.blueberry

import fr.acinq.bitcoin.Transaction
import io.bluewallet.blueberry.parse.CoinLinkGraph
import io.bluewallet.blueberry.parse.CoinLinkage
import io.bluewallet.blueberry.parse.TxRow
import io.bluewallet.blueberry.parse.formatLocalYmdHm
import io.bluewallet.blueberry.parse.spendingTxs
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.PrivateSendCoin
import io.bluewallet.blueberry.storage.StoredTx
import io.bluewallet.blueberry.wallet.Wallet
import io.bluewallet.blueberry.wallet.WatchAddress
import io.bluewallet.blueberry.wallet.hexToBytes

class TxQuery(
    val db: Database,
    val wallet: Wallet?,
    val snap: WalletTxsSnapshot,
    val tipHeight: Int?,
    val nowEpochS: Long,
)

fun txHomeSections(query: TxQuery): List<TxListSection> {
    val now = civilOf(query.nowEpochS) ?: TxCivilTime(1970, 1, 1, 0, 0)
    val facts = txFacts(query)
    val items =
        query.snap.txs.mapNotNull { row ->
            val screen = txScreenOf(row, facts, query, now, null) ?: return@mapNotNull null
            txListItem(row, screen)
        }
    return txListSections(items, now)
}

fun txDetailOf(
    query: TxQuery,
    row: WalletTxRow,
    blockStats: TxBlockStats?,
): TxScreen? {
    val now = civilOf(query.nowEpochS) ?: TxCivilTime(1970, 1, 1, 0, 0)
    return txScreenOf(row, txFacts(query), query, now, blockStats)
}

fun txLabelSuggestions(
    snap: WalletTxsSnapshot,
    direction: TxDirection,
): List<String> {
    val wantedOut = direction != TxDirection.In
    val labels =
        snap.txs.mapNotNull { row ->
            val label = row.paymentLabel?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val outgoing = row.ownSend || row.netDeltaSats < 0
            if (wantedOut == outgoing) label else null
        }
    return labelSuggestions(labels)
}

fun TxDisplayInput.withBlockTxCount(count: Int?): TxDisplayInput {
    val current = block ?: return this
    return if (count == null) this else copy(block = current.copy(txCount = count))
}

internal class SendPack(
    val txHex: String,
    val coins: List<PrivateSendCoin>,
    val destination: String,
)

@Suppress("LongParameterList")
internal class TxFacts(
    val watchByScript: Map<String, WatchAddress>,
    val groups: Map<String, Pair<String, Int>>,
    val utxos: Map<String, WalletUtxoRow>,
    val graph: CoinLinkGraph,
    val spends: Map<String, TxRow>,
    val labels: Map<String, String>,
    val names: Map<String, String>,
    val frozen: Set<String>,
    val sends: Map<String, SendPack>,
    val broadcast: Map<String, String>,
    val stored: Map<String, StoredTx>,
    val feeRateSatPerVb: Double?,
)

private fun txFacts(query: TxQuery): TxFacts {
    val db = query.db
    val stored = db.transactions.list().associateBy { it.txid }
    val sends = db.sends.list().associate { it.txid to SendPack(it.txHex, it.coins, it.destination) }
    val privateSends =
        db.privateSends.list().associate { it.txid to SendPack(it.txHex, it.coins, it.destination) }
    return TxFacts(
        watchByScript =
            query.wallet
                ?.snapshot()
                ?.addresses
                ?.associateBy {
                    io.bluewallet.blueberry.parse
                        .scriptHex(it.scriptPubKey)
                }.orEmpty(),
        groups = groupTitles(query.snap),
        utxos = query.snap.utxos.associateBy { it.key },
        graph = query.snap.coinLinkGraph,
        spends = spendingTxs(stored.values.map { TxRow(it.txid, it.height, it.txIndex, it.tx) }),
        labels =
            query.snap.txs
                .mapNotNull { row -> row.paymentLabel?.let { row.txid to it } }
                .toMap(),
        names = db.utxoNames.list().associate { it.outpoint to it.name },
        frozen =
            query.snap.utxos
                .filter { it.isFrozen }
                .map { it.key }
                .toSet(),
        sends = sends + privateSends,
        broadcast = db.broadcastVia.list().associate { it.txid to it.via },
        stored = stored,
        feeRateSatPerVb = loadSendFeeRate(db),
    )
}

private fun groupTitles(snap: WalletTxsSnapshot): Map<String, Pair<String, Int>> {
    val frozen =
        snap.utxos
            .filter { it.isFrozen }
            .map { it.key }
            .toSet()
    val linkage = CoinLinkage(certain = snap.coinCertainGroupIds, likely = snap.coinGroupIds)
    val control = buildCoinControlSnapshot(snap.utxos, linkage, frozen)
    val titles = mutableMapOf<String, Pair<String, Int>>()
    control.groups.filter { it.linked }.forEach { group ->
        group.coins.forEach { coin -> titles[coin.key] = group.title to group.coins.size }
    }
    return titles
}

class TxBlockStats(
    val txCount: Int?,
    val feeRateLabel: String?,
)

private class TxMoment(
    val tipHeight: Int?,
    val nowEpochS: Long,
    val now: TxCivilTime,
    val blockStats: TxBlockStats?,
)

private fun txScreenOf(
    row: WalletTxRow,
    facts: TxFacts,
    query: TxQuery,
    now: TxCivilTime,
    blockStats: TxBlockStats?,
): TxScreen? {
    val bytes = txBytes(row, facts)
    val tx = bytes?.let { runCatching { Transaction.read(it) }.getOrNull() }
    val moment = TxMoment(query.tipHeight, query.nowEpochS, now, blockStats)
    val input =
        if (bytes == null || tx == null) {
            null
        } else {
            displayInput(row, tx, bytes.size, facts, moment)
        }
    return input?.let { txScreen(it) }
}

private fun txBytes(
    row: WalletTxRow,
    facts: TxFacts,
): ByteArray? = facts.stored[row.txid]?.tx ?: facts.sends[row.txid]?.txHex?.let { runCatching { hexToBytes(it) }.getOrNull() }

private fun displayInput(
    row: WalletTxRow,
    tx: Transaction,
    rawSize: Int,
    facts: TxFacts,
    moment: TxMoment,
): TxDisplayInput {
    val pack = facts.sends[row.txid]
    val outputs = displayOutputs(row, tx, facts, pack?.destination)
    val inputs = displayInputs(row, tx, facts, pack)
    val split = byteSplit(rawSize, tx.weight())
    val epoch = whenEpoch(row)
    val whenCivil = epoch?.let { civilOf(it) } ?: moment.now
    val direction = txDirection(tx, inputs, outputs, row.ownSend)
    return TxDisplayInput(
        txid = row.txid,
        direction = direction,
        label = row.paymentLabel?.trim().orEmpty(),
        inputCount = tx.txIn.size,
        walletInputs = inputs,
        outputs = outputs,
        baseBytes = split.first,
        witnessBytes = split.second,
        block = displayBlock(row, facts, whenCivil, moment.blockStats),
        broadcastAt = if (row.height == 0) whenCivil else null,
        broadcastEpochS = if (row.height == 0) epoch else null,
        now = moment.now,
        nowEpochS = moment.nowEpochS,
        tipHeight = moment.tipHeight,
        broadcast = broadcastOf(row, facts),
        outcome = if (row.replaced) TxOutcome.Replaced else TxOutcome.None,
        replacementTxid = row.replacementTxid,
        ownSend = row.ownSend,
    )
}

private fun txDirection(
    tx: Transaction,
    inputs: List<TxDisplayInputCoin>,
    outputs: List<TxDisplayOutput>,
    ownSend: Boolean,
): TxDirection {
    val known = !tx.isCoinbase() && inputs.size == tx.txIn.size && inputs.isNotEmpty()
    val fee = inputs.sumOf { it.valueSat } - outputs.sumOf { it.valueSat }
    val allMine = outputs.isNotEmpty() && outputs.all { it.mine }
    return when {
        known && fee >= 0 && allMine -> TxDirection.Moved
        inputs.isNotEmpty() || ownSend -> TxDirection.Out
        else -> TxDirection.In
    }
}

private fun txListItem(
    row: WalletTxRow,
    screen: TxScreen,
): TxListItem {
    val epoch = whenEpoch(row)
    return TxListItem(
        txid = row.txid,
        pending = row.height == 0 && !row.replaced,
        whenCivil = epoch?.let { civilOf(it) },
        epochS = epoch ?: 0L,
        row = screen.list,
    )
}

internal fun whenEpoch(row: WalletTxRow): Long? =
    when {
        row.height > 0 -> row.blockTimeS
        row.createdAtMs > 0L -> row.createdAtMs / 1000L
        else -> null
    }

internal fun civilOf(epochS: Long): TxCivilTime? = txCivilFromYmdHm(formatLocalYmdHm(epochS))

/** Only a recorded path counts. Sends from before the record existed, or from another device, say nothing. */
private fun broadcastOf(
    row: WalletTxRow,
    facts: TxFacts,
): TxBroadcast = if (facts.broadcast[row.txid] == BROADCAST_VIA_TOR) TxBroadcast.Tor else TxBroadcast.Unknown

private fun displayBlock(
    row: WalletTxRow,
    facts: TxFacts,
    mined: TxCivilTime,
    blockStats: TxBlockStats?,
): TxDisplayBlock? {
    val stored = facts.stored[row.txid]
    if (row.height <= 0 || stored == null) return null
    return TxDisplayBlock(
        height = row.height,
        hash =
            io.bluewallet.blueberry.headers
                .internalHexToDisplayHex(stored.blockHashInternalHex),
        mined = row.blockTimeS?.let { civilOf(it) } ?: mined,
        index = stored.txIndex,
        txCount = blockStats?.txCount,
        feeRateLabel = blockStats?.feeRateLabel,
    )
}

private fun byteSplit(
    rawSize: Int,
    weight: Int,
): Pair<Int, Int> {
    val base = ((weight - rawSize) / 3).coerceAtLeast(0)
    return base to (rawSize - base).coerceAtLeast(0)
}
