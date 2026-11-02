package io.bluewallet.blueberry

import io.bluewallet.blueberry.parse.LinkNode
import io.bluewallet.blueberry.parse.Relation
import io.bluewallet.blueberry.parse.derivationAddressName

enum class SpendTone {
    Green,
    Amber,
    Neutral,
}

data class SpendRowModel(
    val title: String,
    val subtitle: String,
    val tone: SpendTone,
)

@Suppress("LongParameterList")
fun spendRowsFor(
    coin: CoinControlCoin,
    groupTitle: String?,
    groupSize: Int,
    otherUnspent: Int,
    addressUseCount: Int,
    addressIndex: Int?,
    changeAddress: Boolean,
    parentLabel: String?,
): List<SpendRowModel> {
    val rows = mutableListOf<SpendRowModel>()
    if (coin.isFrozen) {
        rows += SpendRowModel("Frozen", "Coin selection will never pick it.", SpendTone.Green)
    }
    rows += groupSpendRow(groupTitle, groupSize)
    if (otherUnspent > 0 && groupSize >= 2) {
        rows +=
            SpendRowModel(
                "With any other coin: joins two histories",
                "Pairing it with another group or a single coin tells watchers they share an owner.",
                SpendTone.Amber,
            )
    }
    if (otherUnspent > 0 && groupSize < 2) {
        rows +=
            SpendRowModel(
                "With any other coin: its first link",
                "Whatever you pair it with becomes visibly yours, to anyone, forever.",
                SpendTone.Amber,
            )
    }
    if (addressUseCount > 1) {
        val name = if (addressIndex == null) "This address" else addressUseName(changeAddress, addressIndex)
        rows += SpendRowModel("$name used $addressUseCount times", "Every payment to it is visibly yours.", SpendTone.Amber)
    }
    val cost = coin.spendCostSat
    if ((coin.nearDust || coin.uneconomical) && cost != null) {
        val pct = percentOfValue(cost, coin.valueSats)
        rows +=
            SpendRowModel(
                "Costs about $cost sats to spend",
                "$pct% of its value at the current fee rate.",
                SpendTone.Amber,
            )
    }
    rows += counterpartyRow(coin.isChange, parentLabel)
    return rows
}

private fun groupSpendRow(
    groupTitle: String?,
    groupSize: Int,
): SpendRowModel =
    if (groupSize >= 2 && groupTitle != null) {
        SpendRowModel(
            "Alone or with $groupTitle: no new links",
            "Those $groupSize coins are already tied together on-chain.",
            SpendTone.Green,
        )
    } else {
        SpendRowModel(
            "Alone: reveals nothing new",
            "Spend it by itself and it stays unlinked.",
            SpendTone.Green,
        )
    }

private fun addressUseName(
    changeAddress: Boolean,
    index: Int,
): String = if (changeAddress) "Change address #$index" else "Address #$index"

private fun counterpartyRow(
    change: Boolean,
    parentLabel: String?,
): SpendRowModel {
    val spot = "It's change from your payment to them, so they may guess it's yours and see when it moves."
    return when {
        change && !parentLabel.isNullOrBlank() ->
            SpendRowModel("The recipient of “${parentLabel.trim()}” can likely spot it", spot, SpendTone.Neutral)
        change -> SpendRowModel("That payment's recipient can likely spot it", spot, SpendTone.Neutral)
        else ->
            SpendRowModel(
                "The sender knows this coin",
                "They paid it, so they'll see when it moves and where to.",
                SpendTone.Neutral,
            )
    }
}

fun addressSheet(
    row: WalletUtxoRow,
    useCount: Int,
): AddressSheet? {
    val address = row.address ?: return null
    val chips = derivationChips(row.path) ?: emptyList()
    val name = derivationAddressName(row.path, row.isChange)
    val used = if (useCount == 1) "used once" else "used $useCount times"
    return AddressSheet(collapsed = "$name · $used", address = address, chips = chips, rawPath = row.path.orEmpty())
}

data class AddressSheet(
    val collapsed: String,
    val address: String,
    val chips: List<PathChipModel>,
    val rawPath: String,
)

fun originSheet(
    row: WalletUtxoRow,
    parent: WalletTxRow?,
    request: CoinSheetRequest,
    spendVbytes: Double,
): OriginSheet {
    val graph = request.snap.coinLinkGraph
    val outputCount = graph.txs[row.txid]?.outputCount
    val fromSpend = graph.neighbors[LinkNode.Tx(row.txid)].orEmpty().isNotEmpty()
    val nowEpochS = request.nowEpochS
    val whenLabel = formatSheetWhen(parent?.blockTimeS, nowEpochS)
    val label = parent?.paymentLabel?.trim().orEmpty()
    val collapsed =
        when {
            row.isChange && label.isNotEmpty() -> "Change from “$label” · $whenLabel"
            row.isChange -> "Change · $whenLabel"
            else -> "Received · $whenLabel"
        }
    val others = ((outputCount ?: 1) - 1).coerceAtLeast(0)
    val elsewhere = if (others == 1) "1 other output went elsewhere." else "$others other outputs went elsewhere."
    val body =
        if (fromSpend) {
            val whose = if (label.isEmpty()) "your payment" else "your “$label” payment"
            "Output ${row.vout} of $whose. $elsewhere"
        } else {
            "Output ${row.vout} of a payment you received."
        }
    val unconfirmed = row.height == 0
    val block = if (unconfirmed) "—" else formatGroupedInt(row.height)
    val confirmations = confirmationsLabel(row.height, request.tipHeight)
    val age = formatCoinAgeLong(if (unconfirmed) null else parent?.blockTimeS, nowEpochS) ?: "Pending"
    return OriginSheet(
        collapsed = collapsed,
        txid = row.txid,
        vout = row.vout,
        body = body,
        block = block,
        confirmations = confirmations,
        age = age,
        spendSize = spendSizeLabel(spendVbytes),
    )
}

/** Tip minus the coin's height; unconfirmed is `0`, unknown tip is `—`. */
private fun confirmationsLabel(
    height: Int,
    tipHeight: Int?,
): String =
    when {
        height == 0 -> "0"
        tipHeight == null -> "—"
        else -> formatGroupedInt((tipHeight - height).coerceAtLeast(0))
    }

data class OriginSheet(
    val collapsed: String,
    val txid: String,
    val vout: Int,
    val body: String,
    val block: String,
    val confirmations: String,
    val age: String,
    val spendSize: String,
)

fun spendingTx(
    snap: WalletTxsSnapshot,
    outpoint: String,
): LinkNode.Tx? =
    snap.coinLinkGraph.neighbors[LinkNode.Coin(outpoint)]
        ?.firstOrNull { it.relation == Relation.SpentIn }
        ?.to as? LinkNode.Tx
