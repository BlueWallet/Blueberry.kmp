package io.bluewallet.blueberry

import io.bluewallet.blueberry.parse.CoinLink
import io.bluewallet.blueberry.parse.LinkCopy
import io.bluewallet.blueberry.parse.coinLinks
import io.bluewallet.blueberry.wallet.scriptTypeOfScriptPubKey

enum class SheetChipKind {
    Neutral,
    Group,
    Solo,
    Frozen,
    Dust,
}

data class SheetChip(
    val text: String,
    val kind: SheetChipKind,
)

data class CoinSheetRequest(
    val outpoint: String,
    val snap: WalletTxsSnapshot,
    val control: CoinControlSnapshot,
    val labels: Map<String, String>,
    val nowEpochS: Long,
    val tipHeight: Int?,
    val syncing: Boolean,
)

data class CoinSheetModel(
    val outpoint: String,
    val valueSats: Long,
    val colorArgb: Long,
    val frozen: Boolean,
    val spentBanner: String?,
    val spentTxid: String?,
    val syncing: Boolean,
    val chips: List<SheetChip>,
    val label: String,
    val labelPlaceholder: String,
    val linkedTitle: String,
    val linkedAside: String,
    val links: List<CoinLink>,
    val addressUseCount: Int,
    val strip: SheetStrip?,
    val unspentKeys: Set<String>,
    val spendRows: List<SpendRowModel>,
    val sendEnabled: Boolean,
    val sendTitle: String,
    val sendSubtitle: String,
    val address: AddressSheet?,
    val origin: OriginSheet?,
)

fun buildCoinSheetModel(request: CoinSheetRequest): CoinSheetModel? {
    val coin = request.control.coins.find { it.key == request.outpoint }
    return if (coin != null) liveSheet(request, coin) else spentSheet(request)
}

private fun liveSheet(
    request: CoinSheetRequest,
    coin: CoinControlCoin,
): CoinSheetModel? {
    val row = request.snap.utxos.find { it.key == coin.key } ?: return null
    val members = request.control.coins.filter { it.groupId == coin.groupId }
    val group = request.control.groups.find { it.linked && it.groupId == coin.groupId }
    val others = members.filter { it.key != coin.key }
    val useCount = addressUses(request, coin.key)
    val links = linkedCoins(request, coin.key, others.map { it.key })
    val parent = request.snap.txs.find { it.txid == row.txid }
    val index =
        row.path
            ?.substringAfterLast('/')
            ?.substringBefore('\'')
            ?.toIntOrNull()
    val outside = request.control.coins.size - members.size
    val pending = row.height == 0
    val age = if (pending) null else formatCoinAgeLong(parent?.blockTimeS, request.nowEpochS)
    return CoinSheetModel(
        outpoint = coin.key,
        valueSats = coin.valueSats,
        colorArgb = coin.colorArgb,
        frozen = coin.isFrozen,
        spentBanner = null,
        spentTxid = null,
        syncing = request.syncing,
        chips = chipsFor(coin, pending, age, group?.title, members.size),
        label = row.name?.trim().orEmpty(),
        labelPlaceholder = labelPlaceholder(coin.isChange, row.addressLabel),
        linkedTitle = if (others.isEmpty()) "Not linked to any coin" else "Linked to ${others.size} of your coins",
        linkedAside = if (others.isEmpty()) "${request.control.coins.size - 1} others separate" else formatPlusBtc(others.sumOf { it.valueSats }),
        links = links,
        addressUseCount = useCount,
        strip = sheetStrip(request.control, coin.key),
        unspentKeys =
            request.control.coins
                .map { it.key }
                .toSet(),
        spendRows =
            spendRowsFor(
                coin = coin,
                groupTitle = group?.title,
                groupSize = members.size,
                otherUnspent = outside,
                addressUseCount = useCount,
                addressIndex = index,
                changeAddress = coin.isChange,
                parentLabel = parent?.paymentLabel,
            ),
        sendEnabled = coin.canSelect,
        sendTitle = "Send this coin",
        sendSubtitle = sendSubtitle(coin),
        address = addressSheet(row, useCount),
        origin = originSheet(row, parent, request, inputVbytes(scriptTypeOfScriptPubKey(coin.scriptPubKey))),
    )
}

private fun spentSheet(request: CoinSheetRequest): CoinSheetModel? {
    val fact = request.snap.coinLinkGraph.coins[request.outpoint] ?: return null
    val tx = spendingTx(request.snap, request.outpoint)
    val title =
        tx?.let {
            request.snap.txs
                .find { row -> row.txid == it.txid }
                ?.paymentLabel
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
            ?: "Payment"
    return CoinSheetModel(
        outpoint = request.outpoint,
        valueSats = fact.valueSat,
        colorArgb = utxoCircleArgb(request.outpoint),
        frozen = false,
        spentBanner = "Spent in “$title”",
        spentTxid = tx?.txid,
        syncing = request.syncing,
        chips = emptyList(),
        label = request.labels[request.outpoint]?.trim().orEmpty(),
        labelPlaceholder = labelPlaceholder(fact.isChange, addressLabel = null),
        linkedTitle = "Not linked to any coin",
        linkedAside = "",
        links = emptyList(),
        addressUseCount = addressUses(request, request.outpoint),
        strip = null,
        unspentKeys = emptySet(),
        spendRows = emptyList(),
        sendEnabled = false,
        sendTitle = "Send this coin",
        sendSubtitle = "This coin is already spent",
        address = null,
        origin = null,
    )
}

/** Address label when the coin has one, so it shows as a hint and is never saved as the coin's name. */
private fun labelPlaceholder(
    change: Boolean,
    addressLabel: String?,
): String =
    addressLabel?.trim()?.takeIf { it.isNotEmpty() }
        ?: if (change) "Name this coin" else "Who sent it?"

private fun chipsFor(
    coin: CoinControlCoin,
    pending: Boolean,
    age: String?,
    groupTitle: String?,
    groupSize: Int,
): List<SheetChip> {
    val chips = mutableListOf<SheetChip>()
    val kind =
        if (pending) {
            "Pending"
        } else if (coin.isChange) {
            "Change"
        } else {
            "Received"
        }
    chips += SheetChip(kind, SheetChipKind.Neutral)
    if (age != null) chips += SheetChip(age, SheetChipKind.Neutral)
    if (groupSize >= 2 && groupTitle != null) {
        chips += SheetChip("$groupTitle · $groupSize coins", SheetChipKind.Group)
    }
    if (groupSize < 2) chips += SheetChip("Solo", SheetChipKind.Solo)
    if (coin.isFrozen) chips += SheetChip("Frozen", SheetChipKind.Frozen)
    if (coin.nearDust || coin.uneconomical) chips += SheetChip("Near dust", SheetChipKind.Dust)
    return chips
}

private fun linkedCoins(
    request: CoinSheetRequest,
    origin: String,
    others: List<String>,
): List<CoinLink> {
    val txLabels =
        request.snap.txs.mapNotNull { tx ->
            tx.paymentLabel
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { tx.txid to it }
        }
    val txDates =
        request.snap.txs.mapNotNull { tx ->
            tx.blockTimeS?.let { tx.txid to formatSheetWhen(it, request.nowEpochS) }
        }
    val paths =
        request.snap.utxos
            .mapNotNull { row -> row.path?.let { row.key to it } }
            .toMap()
    // Same title as the coin list: the saved name, else the address label (never saved).
    val names =
        request.labels +
            request.snap.utxos.mapNotNull { row ->
                row.label
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { row.key to it }
            }
    return coinLinks(
        request.snap.coinLinkGraph,
        origin,
        others,
        LinkCopy(labels = names, txLabels = txLabels.toMap(), txDates = txDates.toMap(), paths = paths),
    )
}

private fun sendSubtitle(coin: CoinControlCoin): String =
    when {
        coin.isFrozen -> "Unfreeze it first"
        coin.sending -> "Already in a send"
        else -> "Starts a send with this coin selected"
    }

/** Home-widget bubble order, with [openKey]'s group moved to the front. */
internal fun sheetStrip(
    control: CoinControlSnapshot,
    openKey: String,
): SheetStrip? {
    val open = control.bubbles.find { it.key == openKey } ?: return null
    val groupId = open.linkedGroupId
    val mine = if (groupId == null) listOf(open) else control.bubbles.filter { it.linkedGroupId == groupId }
    val mineKeys = mine.map { it.key }.toSet()
    val bubbles = mine + control.bubbles.filter { it.key !in mineKeys }
    val group = groupId?.let { id -> control.groups.find { it.linked && it.groupId == id } }
    val share = shareOfBalance(mine.sumOf { it.valueSats }, control.totalSats)
    val caption =
        if (group != null && mine.size >= 2) {
            "${group.title} · ${mine.size} coins · $share% of balance"
        } else {
            "This coin · $share% of balance"
        }
    return SheetStrip(bubbles, openKey, caption)
}

private fun shareOfBalance(
    part: Long,
    total: Long,
): Int = if (total <= 0L) 0 else ((part * 100 + total / 2) / total).toInt()

data class SheetStrip(
    val bubbles: List<CoinControlBubble>,
    val openKey: String,
    val caption: String,
)

private fun addressUses(
    request: CoinSheetRequest,
    outpoint: String,
): Int {
    val script =
        request.snap.coinLinkGraph.coins[outpoint]
            ?.scriptHex ?: return 1
    return request.snap.coinLinkGraph.coins.values
        .count { it.scriptHex == script }
}
