@file:Suppress("TooManyFunctions")

package io.bluewallet.blueberry

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
    val groupId: String,
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
    val totalSats: Long,
    val coins: List<CoinControlCoin>,
)

data class CoinControlBubble(
    val key: String,
    val valueSats: Long,
    val colorArgb: Long,
    val isFrozen: Boolean,
    val linkedGroupId: String?,
)

data class CoinControlSnapshot(
    val coins: List<CoinControlCoin>,
    val groups: List<CoinControlGroup>,
    val linkedGroupCount: Int,
    val soloCount: Int,
    val totalSats: Long,
    val bubbles: List<CoinControlBubble>,
    val filterCounts: Map<CoinControlFilter, Int>,
)

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
    groupIds: Map<String, String>,
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
                groupId = groupIds[u.key] ?: u.key,
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
        linkedGroupCount = namedLinked.size,
        soloCount = independentCoins.size,
        totalSats = coins.sumOf { it.valueSats },
        bubbles = bubbles,
        filterCounts = filterCounts,
    )
}

internal fun linkedGroupLetterNames(groupsNewestFirstByTotal: List<CoinControlGroup>): List<String> =
    groupsNewestFirstByTotal.mapIndexed { index, g ->
        val letter = ('A' + index).coerceAtMost('Z')
        val labeled = g.coins.filter { !it.label.isNullOrBlank() }.maxByOrNull { it.valueSats }
        if (labeled != null) {
            "${labeled.label!!.trim()} group"
        } else {
            "Linked group $letter"
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
        if (coins.isEmpty()) null else g.copy(coins = coins)
    }
}

fun privacyNoticeForSelection(
    selected: List<CoinControlCoin>,
): PrivacyNotice {
    val k = selected.size
    if (k == 0) {
        return PrivacyNotice(
            PrivacyNoticeKind.Hint,
            "Tap coins to choose exactly what this payment spends.",
        )
    }
    val n = selected.map { it.groupId }.toSet().size
    val kind = if (n == 1) PrivacyNoticeKind.Safe else PrivacyNoticeKind.Warning
    val message =
        if (n == 1) {
            "These coins already share history, so spending them together reveals nothing new."
        } else {
            "Joins $n unlinked histories. Anyone watching the chain will learn these coins share an owner."
        }
    val costly =
        selected.filter { it.nearDust || it.uneconomical }.mapNotNull { c ->
            val cost = c.spendCostSat ?: return@mapNotNull null
            if (c.valueSats <= 0L) return@mapNotNull null
            val pct = ((cost * 100.0) / c.valueSats).roundToLong()
            Triple(c, cost, pct)
        }
    val costLine =
        when {
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
    return PrivacyNotice(kind, message, costLine)
}

fun coinControlHeaderRight(
    linkedGroupCount: Int,
    soloCount: Int,
): String =
    when {
        linkedGroupCount == 0 && soloCount == 0 -> ""
        linkedGroupCount == 0 -> "All coins independent"
        else -> {
            val groups =
                if (linkedGroupCount == 1) {
                    "1 linked group"
                } else {
                    "$linkedGroupCount linked groups"
                }
            val solo = if (soloCount == 1) "1 solo" else "$soloCount solo"
            "$groups · $solo"
        }
    }

fun coinControlAccessibilityLabel(
    coinCount: Int,
    linkedGroups: List<CoinControlGroup>,
    soloCount: Int,
): String {
    val coinsWord = if (coinCount == 1) "1 coin" else "$coinCount coins"
    if (linkedGroups.isEmpty()) {
        return "$coinsWord. All independent. Opens coin control."
    }
    val parts =
        linkedGroups.joinToString(" and ") { g ->
            "${g.coins.size} coins"
        }
    val linkedWord =
        if (linkedGroups.size == 1) {
            "1 linked group of $parts"
        } else {
            "${linkedGroups.size} linked groups of $parts"
        }
    val soloWord =
        when (soloCount) {
            0 -> ""
            1 -> ", 1 independent"
            else -> ", $soloCount independent"
        }
    return "$coinsWord. $linkedWord$soloWord. Opens coin control."
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
