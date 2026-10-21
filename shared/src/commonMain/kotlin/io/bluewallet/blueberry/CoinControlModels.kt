@file:Suppress("TooManyFunctions")

package io.bluewallet.blueberry

import io.bluewallet.blueberry.parse.CoinLinkage
import io.bluewallet.blueberry.wallet.AddressScriptType
import io.bluewallet.blueberry.wallet.scriptTypeOfScriptPubKey
import kotlin.math.ceil
import kotlin.math.roundToLong

enum class CoinPrivacyState {
    Frozen,
    Change,
    Labeled,
    Unlabeled,
}

enum class CoinControlFilter {
    All,
    Labeled,
    Change,
    NoLabel,
    Frozen,
}

data class CoinControlCoin(
    val key: String,
    val valueSats: Long,
    val label: String?,
    val isChange: Boolean,
    val isFrozen: Boolean,
    /** Likely group: provable links plus guessed change. */
    val groupId: String,
    /** Group from provable links only. */
    val certainGroupId: String,
    val privacyState: CoinPrivacyState,
    val colorArgb: Long,
    val spendCostSat: Long?,
    val nearDust: Boolean,
    val uneconomical: Boolean,
    val ageLabel: String,
    val parentTxid: String,
    val scriptPubKey: ByteArray,
)

data class CoinControlGroup(
    val groupId: String,
    val title: String,
    val linked: Boolean,
    /** True when every coin shares one provable group, false when change guesses join them. */
    val provable: Boolean,
    val totalSats: Long,
    val coins: List<CoinControlCoin>,
    /** Members of a linked group that the active filter hides. */
    val hiddenCount: Int = 0,
)

data class CoinControlBubble(
    val key: String,
    val valueSats: Long,
    val colorArgb: Long,
    val isFrozen: Boolean,
    val linkedGroupId: String?,
    /** False when the coin's group is joined only by a change guess. */
    val provable: Boolean = true,
)

data class CoinControlSnapshot(
    val coins: List<CoinControlCoin>,
    val groups: List<CoinControlGroup>,
    /** Provable (on-chain) multi-coin clusters only. */
    val linkedGroupCount: Int,
    /** Multi-coin clusters joined only by a change guess. */
    val probableGroupCount: Int,
    val soloCount: Int,
    val totalSats: Long,
    val bubbles: List<CoinControlBubble>,
    val filterCounts: Map<CoinControlFilter, Int>,
    val frozenSats: Long = 0L,
) {
    val spendableSats: Long get() = totalSats - frozenSats

    val availability: CoinAvailability
        get() = coinAvailability(coinCount = coins.size, frozenCount = coins.count { it.isFrozen })
}

enum class PrivacyNoticeKind {
    Hint,
    Safe,
    Warning,
}

data class PrivacyNotice(
    val kind: PrivacyNoticeKind,
    val message: String,
    val costLine: String? = null,
)

/** Input vbytes used for per-coin spend-cost estimates (spec). */
fun inputVbytes(scriptType: AddressScriptType): Double =
    when (scriptType) {
        AddressScriptType.P2TR -> 57.5
        AddressScriptType.P2WPKH -> 68.0
        AddressScriptType.P2SH_P2WPKH -> 91.0
        AddressScriptType.P2PKH -> 148.0
    }

fun spendCostSat(
    scriptPubKey: ByteArray,
    feeRateSatPerVb: Double?,
): Long? {
    if (feeRateSatPerVb == null || !feeRateSatPerVb.isFinite() || feeRateSatPerVb <= 0.0) return null
    val vbytes = inputVbytes(scriptTypeOfScriptPubKey(scriptPubKey))
    return ceil(vbytes * feeRateSatPerVb).toLong()
}

fun coinPrivacyState(
    isFrozen: Boolean,
    isChange: Boolean,
    label: String?,
): CoinPrivacyState =
    when {
        isFrozen -> CoinPrivacyState.Frozen
        isChange -> CoinPrivacyState.Change
        !label.isNullOrBlank() -> CoinPrivacyState.Labeled
        else -> CoinPrivacyState.Unlabeled
    }

/** Spec age: `1d`, `35d`, then `14mo` from 365 days; unconfirmed → Pending. */
fun formatCoinAgeDays(ageDays: Long?): String =
    when {
        ageDays == null -> "Pending"
        ageDays < 365 -> "${ageDays}d"
        else -> {
            val months = (ageDays / 30.0).roundToLong().coerceAtLeast(12)
            "${months}mo"
        }
    }

fun formatCoinAgeFromConfirmedAt(
    confirmedAtEpochS: Long?,
    nowEpochS: Long,
): String {
    if (confirmedAtEpochS == null) return "Pending"
    val days = ((nowEpochS - confirmedAtEpochS) / 86_400L).coerceAtLeast(0)
    return formatCoinAgeDays(days)
}

@Suppress("LongMethod")
fun buildCoinControlSnapshot(
    utxos: List<WalletUtxoRow>,
    linkage: CoinLinkage,
    frozen: Set<String>,
    feeRateSatPerVb: Double? = null,
    ageOverride: Map<String, String> = emptyMap(),
): CoinControlSnapshot {
    val coins =
        utxos.map { u ->
            val cost = spendCostSat(u.scriptPubKey, feeRateSatPerVb)
            val nearDust = cost != null && u.valueSats > 0 && cost * 2 >= u.valueSats
            val uneconomical = cost != null && cost >= u.valueSats
            val isFrozen = u.key in frozen
            CoinControlCoin(
                key = u.key,
                valueSats = u.valueSats,
                label = u.name,
                isChange = u.isChange,
                isFrozen = isFrozen,
                groupId = linkage.likely[u.key] ?: u.key,
                certainGroupId = linkage.certain[u.key] ?: u.key,
                privacyState = coinPrivacyState(isFrozen, u.isChange, u.name),
                colorArgb = utxoCircleArgb(u.key),
                spendCostSat = cost,
                nearDust = nearDust,
                uneconomical = uneconomical,
                ageLabel = ageOverride[u.key] ?: u.ageLabel.trim().ifEmpty { "Pending" },
                parentTxid = u.txid,
                scriptPubKey = u.scriptPubKey,
            )
        }

    val byGroup = coins.groupBy { it.groupId }
    val linkedGroups =
        byGroup
            .filter { it.value.size >= 2 }
            .map { (id, members) ->
                val total = members.sumOf { it.valueSats }
                id to
                    CoinControlGroup(
                        groupId = id,
                        title = "", // filled after naming pass
                        linked = true,
                        provable = members.map { it.certainGroupId }.toSet().size == 1,
                        totalSats = total,
                        coins = members.sortedByDescending { it.valueSats },
                    )
            }.sortedByDescending { it.second.totalSats }

    val letterNames = linkedGroupLetterNames(linkedGroups.map { it.second })
    val namedLinked =
        linkedGroups.mapIndexed { index, (_, g) ->
            g.copy(title = letterNames[index])
        }

    val independentCoins =
        byGroup
            .filter { it.value.size == 1 }
            .values
            .flatten()
            .sortedByDescending { it.valueSats }
    val independentSection =
        if (independentCoins.isEmpty()) {
            null
        } else {
            CoinControlGroup(
                groupId = "independent",
                title = "Independent",
                linked = false,
                provable = true,
                totalSats = independentCoins.sumOf { it.valueSats },
                coins = independentCoins,
            )
        }

    val groups = namedLinked + listOfNotNull(independentSection)

    val bubbles = orderedBubbles(namedLinked, independentCoins)

    val filterCounts =
        mapOf(
            CoinControlFilter.All to coins.size,
            CoinControlFilter.Labeled to coins.count { it.privacyState == CoinPrivacyState.Labeled },
            CoinControlFilter.Change to coins.count { it.isChange },
            CoinControlFilter.NoLabel to
                coins.count {
                    !it.isFrozen && !it.isChange && it.label.isNullOrBlank()
                },
            CoinControlFilter.Frozen to coins.count { it.isFrozen },
        )

    return CoinControlSnapshot(
        coins = coins,
        groups = groups,
        linkedGroupCount = namedLinked.count { it.provable },
        probableGroupCount = namedLinked.count { !it.provable },
        soloCount = independentCoins.size,
        totalSats = coins.sumOf { it.valueSats },
        bubbles = bubbles,
        filterCounts = filterCounts,
        frozenSats = coins.filter { it.isFrozen }.sumOf { it.valueSats },
    )
}

internal fun linkedGroupLetterNames(groupsNewestFirstByTotal: List<CoinControlGroup>): List<String> =
    groupsNewestFirstByTotal.mapIndexed { index, g ->
        val labeled = g.coins.filter { !it.label.isNullOrBlank() }.maxByOrNull { it.valueSats }
        if (labeled != null) {
            "${labeled.label!!.trim()} group"
        } else {
            val suffix =
                if (index < 26) {
                    ('A' + index).toString()
                } else {
                    (index + 1).toString()
                }
            "Linked group $suffix"
        }
    }

private fun orderedBubbles(
    linked: List<CoinControlGroup>,
    independent: List<CoinControlCoin>,
): List<CoinControlBubble> {
    val out = mutableListOf<CoinControlBubble>()
    for (g in linked) {
        for (c in g.coins) {
            out.add(
                CoinControlBubble(
                    key = c.key,
                    valueSats = c.valueSats,
                    colorArgb = c.colorArgb,
                    isFrozen = c.isFrozen,
                    linkedGroupId = g.groupId,
                    provable = g.provable,
                ),
            )
        }
    }
    for (c in independent) {
        out.add(
            CoinControlBubble(
                key = c.key,
                valueSats = c.valueSats,
                colorArgb = c.colorArgb,
                isFrozen = c.isFrozen,
                linkedGroupId = null,
            ),
        )
    }
    return out
}

fun filterCoinControlGroups(
    snapshot: CoinControlSnapshot,
    filter: CoinControlFilter,
): List<CoinControlGroup> {
    if (filter == CoinControlFilter.All) return snapshot.groups
    return snapshot.groups.mapNotNull { g ->
        val coins =
            g.coins.filter { c ->
                when (filter) {
                    CoinControlFilter.All -> true
                    CoinControlFilter.Labeled -> c.privacyState == CoinPrivacyState.Labeled
                    CoinControlFilter.Change -> c.isChange
                    CoinControlFilter.NoLabel ->
                        !c.isFrozen && !c.isChange && c.label.isNullOrBlank()
                    CoinControlFilter.Frozen -> c.isFrozen
                }
            }
        if (coins.isEmpty()) {
            null
        } else {
            g.copy(
                coins = coins,
                totalSats = coins.sumOf { it.valueSats },
                provable = !g.linked || filteredGroupProvable(g.coins, coins),
                hiddenCount = if (g.linked) g.coins.size - coins.size else 0,
            )
        }
    }
}

/**
 * A filtered section is provable only when every member of the full cluster —
 * including those the filter hides — shares one certain id. Visible coins that
 * share a certain id while a hidden sibling does not must not claim "Already
 * linked on-chain".
 */
internal fun filteredGroupProvable(
    all: List<CoinControlCoin>,
    visible: List<CoinControlCoin>,
): Boolean {
    val certain = visible.map { it.certainGroupId }.toSet().singleOrNull()
    return certain != null && all.isNotEmpty() && all.all { it.certainGroupId == certain }
}

/** Segment widths that fill [usable] in value proportion while keeping each at least [minWidth]. */
fun coinWeightBarWidths(
    values: List<Long>,
    usable: Float,
    minWidth: Float,
): List<Float> {
    if (values.isEmpty() || minWidth * values.size >= usable) {
        return values.map { (usable / values.size).coerceAtLeast(0f) }
    }
    val clamped = BooleanArray(values.size)
    var widths: List<Float>
    do {
        val rest = usable - minWidth * clamped.count { it }
        val restSum =
            values.indices
                .filterNot { clamped[it] }
                .sumOf { values[it].coerceAtLeast(0L) }
                .toFloat()
        widths =
            values.mapIndexed { i, v ->
                when {
                    clamped[i] -> minWidth
                    restSum <= 0f -> rest / clamped.count { !it }
                    else -> rest * v.coerceAtLeast(0L) / restSum
                }
            }
        val newlyClamped = widths.indices.filter { !clamped[it] && widths[it] < minWidth }
        newlyClamped.forEach { clamped[it] = true }
    } while (newlyClamped.isNotEmpty())
    return widths
}

/** Spend-cost warning for the coins chosen for a send at the typed fee rate. */
fun selectionCostLine(
    utxos: List<WalletUtxoRow>,
    selectedKeys: Set<String>,
    feeRateSatPerVb: Double?,
): String? {
    if (selectedKeys.isEmpty() || feeRateSatPerVb == null) return null
    val selected = utxos.filter { it.key in selectedKeys }
    val coins = buildCoinControlSnapshot(selected, CoinLinkage(emptyMap(), emptyMap()), emptySet(), feeRateSatPerVb).coins
    return nearDustCostLine(coins)
}

fun privacyNoticeForSelection(
    selected: List<CoinControlCoin>,
): PrivacyNotice {
    val k = selected.size
    return when {
        k == 0 ->
            PrivacyNotice(
                PrivacyNoticeKind.Hint,
                "Tap coins to choose exactly what this payment spends.",
            )
        k == 1 ->
            PrivacyNotice(
                PrivacyNoticeKind.Safe,
                "One coin selected. Spending it alone does not link other outputs.",
                costLine = nearDustCostLine(selected),
            )
        selected.map { it.certainGroupId }.toSet().size == 1 ->
            PrivacyNotice(
                PrivacyNoticeKind.Safe,
                "These coins already share history on-chain, so spending them together reveals nothing new.",
                nearDustCostLine(selected),
            )
        else -> PrivacyNotice(PrivacyNoticeKind.Warning, joinedHistoriesMessage(selected), nearDustCostLine(selected))
    }
}

/** Selected coins split by likely history: a cluster is provable when every coin shares one certain group. */
private fun joinedHistoriesMessage(selected: List<CoinControlCoin>): String {
    val histories = selected.groupBy { it.groupId }.values
    if (histories.size == 1) {
        return "These coins are probably linked through change. Spending them together confirms it."
    }
    val clusters = histories.filter { it.size > 1 }
    val provable = clusters.count { c -> c.map { it.certainGroupId }.toSet().size == 1 }
    val probable = clusters.size - provable
    val solo = histories.size - clusters.size
    val owner = "Anyone watching the chain will learn these coins share an owner."
    return when {
        clusters.isEmpty() -> "Joins ${histories.size} unlinked histories. $owner"
        clusters.size == 1 -> {
            val n = clusters.single().size
            val lead =
                if (provable == 1) {
                    "$n of these coins already share history on-chain."
                } else {
                    "$n of these coins are probably linked through change."
                }
            "$lead Adding the other $solo joins ${histories.size} separate histories, " +
                "so anyone watching the chain will learn they share an owner."
        }
        else -> {
            val parts =
                listOfNotNull(
                    provable.takeIf { it > 0 }?.let { "${groupsWord(it)} already linked on-chain" },
                    probable.takeIf { it > 0 }?.let { "${groupsWord(it)} probably linked through change" },
                    solo.takeIf { it > 0 }?.let { if (it == 1) "1 single coin" else "$it single coins" },
                )
            "Joins ${histories.size} separate histories (${parts.joinToString(", ")}). $owner"
        }
    }
}

private fun groupsWord(n: Int): String = if (n == 1) "1 group" else "$n groups"

internal fun nearDustCostLine(selected: List<CoinControlCoin>): String? {
    val costly =
        selected.filter { it.nearDust || it.uneconomical }.mapNotNull { c ->
            val cost = c.spendCostSat ?: return@mapNotNull null
            if (c.valueSats <= 0L) return@mapNotNull null
            val pct = ((cost * 100.0) / c.valueSats).roundToLong()
            Triple(c, cost, pct)
        }
    return when {
        costly.isEmpty() -> null
        costly.size == 1 -> {
            val (_, cost, pct) = costly.first()
            "1 coin costs about $cost sats to spend, $pct% of its value."
        }
        else -> {
            val (_, cost, pct) = costly.maxBy { it.third }
            "${costly.size} coins include one that costs about $cost sats to spend, $pct% of its value."
        }
    }
}

fun coinControlHeaderRight(
    linkedGroupCount: Int,
    soloCount: Int,
    probableGroupCount: Int = 0,
): String {
    if (linkedGroupCount == 0 && probableGroupCount == 0 && soloCount == 0) return ""
    val parts = mutableListOf<String>()
    if (linkedGroupCount == 0) {
        parts += "No on-chain links"
    } else {
        parts +=
            if (linkedGroupCount == 1) {
                "1 linked group"
            } else {
                "$linkedGroupCount linked groups"
            }
    }
    if (probableGroupCount == 1) {
        parts += "1 probable"
    } else if (probableGroupCount > 1) {
        parts += "$probableGroupCount probable"
    }
    if (linkedGroupCount > 0 || probableGroupCount > 0) {
        if (soloCount == 1) {
            parts += "1 solo"
        } else if (soloCount > 1) {
            parts += "$soloCount solo"
        }
    }
    return parts.joinToString(" · ")
}

fun coinControlAccessibilityLabel(
    coinCount: Int,
    linkedGroups: List<CoinControlGroup>,
    soloCount: Int,
    frozenCount: Int = 0,
): String {
    val coinsWord = if (coinCount == 1) "1 coin" else "$coinCount coins"
    val frozenWord =
        when {
            frozenCount <= 0 -> ""
            frozenCount >= coinCount -> " All coins are frozen."
            else -> " $frozenCount frozen."
        }
    if (linkedGroups.isEmpty()) {
        return "$coinsWord. No on-chain links.$frozenWord Opens coin control."
    }
    val (provable, probable) = linkedGroups.partition { it.provable }
    val groupsWord =
        listOfNotNull(
            accessibilityGroupPhrase(provable, "linked on-chain"),
            accessibilityGroupPhrase(probable, "probably linked through change"),
        ).joinToString(", ")
    val soloWord =
        when (soloCount) {
            0 -> ""
            1 -> ", 1 independent"
            else -> ", $soloCount independent"
        }
    return "$coinsWord. $groupsWord$soloWord.$frozenWord Opens coin control."
}

/** "11 coins · 1 linked group · 1 probable · 6 independent" line above the coin control weight bar. */
fun coinControlSummaryLine(control: CoinControlSnapshot): String {
    val parts = mutableListOf(if (control.coins.size == 1) "1 coin" else "${control.coins.size} coins")
    parts +=
        when (control.linkedGroupCount) {
            0 -> "no on-chain links"
            1 -> "1 linked group"
            else -> "${control.linkedGroupCount} linked groups"
        }
    if (control.probableGroupCount > 0) parts += "${control.probableGroupCount} probable"
    if (control.soloCount > 0) parts += "${control.soloCount} independent"
    return parts.joinToString(" · ")
}

/** Screen-reader action for a filter chip; narrowing also drops hidden coins from the selection. */
fun coinFilterClickLabel(
    label: String,
    count: Int,
    narrows: Boolean,
): String {
    val coins = if (count == 1) "1 coin" else "$count coins"
    return if (narrows) "Show $label, $coins. Deselects hidden coins" else "Show all $coins"
}

private fun accessibilityGroupPhrase(
    groups: List<CoinControlGroup>,
    relation: String,
): String? {
    if (groups.isEmpty()) return null
    val sizes = groups.joinToString(" and ") { "${it.coins.size} coins" }
    val count = if (groups.size == 1) "1 group" else "${groups.size} groups"
    return "$count $relation, of $sizes"
}

fun coinGroupSubtitle(group: CoinControlGroup): String {
    val relation =
        when {
            !group.linked -> return "No shared history, keep them apart"
            group.provable -> "Already linked on-chain"
            else -> "Probably linked through change"
        }
    return when (group.hiddenCount) {
        0 -> relation
        1 -> "$relation, 1 more coin filtered out"
        else -> "$relation, ${group.hiddenCount} more coins filtered out"
    }
}

fun coinRowSubtitle(coin: CoinControlCoin): String =
    when {
        !coin.label.isNullOrBlank() -> coin.label.trim()
        coin.isChange -> "from ${coin.parentTxid.take(8)}…"
        else -> "No label"
    }

fun coinRowTag(coin: CoinControlCoin): String? =
    when {
        coin.isFrozen -> "Frozen"
        coin.nearDust -> "Near dust"
        coin.isChange -> "Change"
        else -> null
    }
