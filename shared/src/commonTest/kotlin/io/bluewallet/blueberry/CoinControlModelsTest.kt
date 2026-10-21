package io.bluewallet.blueberry

import io.bluewallet.blueberry.parse.CoinLinkage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoinControlModelsTest {
    @Test
    fun fixture_header_and_sections() {
        val snap = fixtureSnapshot()
        assertEquals(11, snap.coins.size)
        assertEquals(2, snap.linkedGroupCount)
        assertEquals(0, snap.probableGroupCount)
        assertEquals(6, snap.soloCount)
        assertEquals(275_226L, snap.totalSats)
        assertEquals("2 linked groups · 6 solo", coinControlHeaderRight(2, 6))

        assertEquals(3, snap.groups.size)
        assertEquals("Salary Sept group", snap.groups[0].title)
        assertEquals(3, snap.groups[0].coins.size)
        assertEquals(175_756L, snap.groups[0].totalSats)
        assertEquals("Linked group B", snap.groups[1].title)
        assertEquals(2, snap.groups[1].coins.size)
        assertEquals(49_510L, snap.groups[1].totalSats)
        assertEquals("Independent", snap.groups[2].title)
        assertEquals(6, snap.groups[2].coins.size)
        assertEquals(49_960L, snap.groups[2].totalSats)
    }

    @Test
    fun header_counts_only_provable_links_and_keeps_probable_distinct() {
        val snap = fixtureSnapshot(provableLinks = false)
        assertEquals(0, snap.linkedGroupCount)
        assertEquals(2, snap.probableGroupCount)
        assertEquals(
            "No on-chain links · 2 probable · 6 solo",
            coinControlHeaderRight(snap.linkedGroupCount, snap.soloCount, snap.probableGroupCount),
        )
        assertEquals("No on-chain links", coinControlHeaderRight(0, 6))
    }

    @Test
    fun fixture_filter_counts() {
        val counts = fixtureSnapshot().filterCounts
        assertEquals(11, counts[CoinControlFilter.All])
        assertEquals(3, counts[CoinControlFilter.Labeled])
        assertEquals(4, counts[CoinControlFilter.Change])
        assertEquals(3, counts[CoinControlFilter.NoLabel])
        assertEquals(1, counts[CoinControlFilter.Frozen])
    }

    @Test
    fun fixture_near_dust_and_frozen() {
        val snap = fixtureSnapshot()
        val c10 = snap.coins.first { it.key == "c10:0" }
        assertTrue(c10.nearDust)
        assertEquals(680L, c10.spendCostSat)
        assertEquals("Near dust", coinRowTag(c10))

        val c11 = snap.coins.first { it.key == "c11:0" }
        assertTrue(c11.isFrozen)
        assertEquals(CoinPrivacyState.Frozen, c11.privacyState)
        assertEquals("Frozen", coinRowTag(c11))
    }

    @Test
    fun privacy_same_group_green() {
        val snap = fixtureSnapshot()
        val c1 = snap.coins.first { it.key == "c1:0" }
        val c2 = snap.coins.first { it.key == "c2:0" }
        val notice = privacyNoticeForSelection(listOf(c1, c2))
        assertEquals(PrivacyNoticeKind.Safe, notice.kind)
        assertTrue(notice.message.contains("reveals nothing new"))
        assertNull(notice.costLine)
        assertEquals(170_610L, c1.valueSats + c2.valueSats)
    }

    @Test
    fun privacy_same_group_by_change_guess_is_amber_not_green() {
        val snap = fixtureSnapshot(provableLinks = false)
        assertFalse(snap.groups[0].provable)
        val c1 = snap.coins.first { it.key == "c1:0" }
        val c2 = snap.coins.first { it.key == "c2:0" }
        val notice = privacyNoticeForSelection(listOf(c1, c2))
        assertEquals(PrivacyNoticeKind.Warning, notice.kind)
        assertTrue(notice.message.contains("probably linked through change"))
    }

    @Test
    fun filter_does_not_overclaim_when_hidden_sibling_is_only_a_guess() {
        // Group A: c2 and c8 share a certain id; c1 joins only by a change guess.
        val snap = fixtureSnapshot(certainOverride = mapOf("c1:0" to "c1:0"))
        assertFalse(snap.groups[0].provable)
        assertEquals(1, snap.linkedGroupCount)
        assertEquals(1, snap.probableGroupCount)
        val change = filterCoinControlGroups(snap, CoinControlFilter.Change).first { it.groupId == "group-A" }
        assertEquals(setOf("c2:0", "c8:0"), change.coins.map { it.key }.toSet())
        assertTrue(change.linked)
        // Hidden receive output is outside the certain id — do not say "Already linked on-chain".
        assertFalse(change.provable)
        assertEquals("Probably linked through change, 1 more coin filtered out", coinGroupSubtitle(change))
    }

    @Test
    fun filter_leaving_one_coin_of_a_group_keeps_its_link() {
        val labeled = filterCoinControlGroups(fixtureSnapshot(), CoinControlFilter.Labeled)
        val fromA = labeled.first { it.groupId == "group-A" }
        assertEquals(listOf("c1:0"), fromA.coins.map { it.key })
        assertTrue(fromA.linked)
        assertTrue(fromA.provable)
        assertEquals(2, fromA.hiddenCount)
        assertEquals("Already linked on-chain, 2 more coins filtered out", coinGroupSubtitle(fromA))
    }

    @Test
    fun filter_leaving_one_guessed_coin_says_probably() {
        val snap = fixtureSnapshot(certainOverride = mapOf("c1:0" to "c1:0"))
        val fromA = filterCoinControlGroups(snap, CoinControlFilter.Labeled).first { it.groupId == "group-A" }
        assertTrue(fromA.linked)
        assertFalse(fromA.provable)
        assertEquals("Probably linked through change, 2 more coins filtered out", coinGroupSubtitle(fromA))
    }

    @Test
    fun accessibility_label_separates_provable_from_guessed_groups() {
        val snap = fixtureSnapshot(certainOverride = mapOf("c3:0" to "c3:0"))
        val label = coinControlAccessibilityLabel(snap.coins.size, snap.groups.filter { it.linked }, snap.soloCount)
        assertEquals(
            "11 coins. 1 group linked on-chain, of 3 coins, 1 group probably linked through change, of 2 coins, " +
                "6 independent. Opens coin control.",
            label,
        )
    }

    @Test
    fun weight_bar_widths_fit_the_track() {
        val values = listOf(1_000_000L) + List(40) { 1L }
        val widths = coinWeightBarWidths(values, usable = 200f, minWidth = 3f)
        assertEquals(200f, widths.sum(), 0.01f)
        assertTrue(widths.all { it >= 3f - 0.001f })
        assertEquals(80f, widths.first(), 0.01f)
        val crowded = coinWeightBarWidths(List(100) { 5L }, usable = 200f, minWidth = 3f)
        assertEquals(200f, crowded.sum(), 0.01f)
    }

    @Test
    fun selection_cost_line_uses_typed_fee() {
        val utxo =
            WalletUtxoRow(
                key = "d:0",
                txid = "dd",
                vout = 0,
                outpointShort = "d",
                valueSats = 1_043,
                scriptPubKey = ByteArray(22) { if (it == 1) 0x14 else 0x01 }.also { it[0] = 0x00 },
                amountLabel = "",
                height = 1,
                ageLabel = "1d",
                valueBar = "",
                name = null,
                isChange = false,
            )
        assertNull(selectionCostLine(listOf(utxo), setOf("d:0"), null))
        assertEquals(
            "1 coin costs about 680 sats to spend, 65% of its value.",
            selectionCostLine(listOf(utxo), setOf("d:0"), 10.0),
        )
    }

    @Test
    fun privacy_cross_group_amber() {
        val snap = fixtureSnapshot()
        val c1 = snap.coins.first { it.key == "c1:0" }
        val c4 = snap.coins.first { it.key == "c4:0" }
        val notice = privacyNoticeForSelection(listOf(c1, c4))
        assertEquals(PrivacyNoticeKind.Warning, notice.kind)
        assertTrue(notice.message.contains("Joins 2 unlinked histories"))
        assertEquals(134_175L, c1.valueSats + c4.valueSats)
    }

    @Test
    fun privacy_near_dust_cost_line() {
        val snap = fixtureSnapshot()
        val c10 = snap.coins.first { it.key == "c10:0" }
        val notice = privacyNoticeForSelection(listOf(c10))
        assertEquals(PrivacyNoticeKind.Safe, notice.kind)
        assertEquals("1 coin costs about 680 sats to spend, 65% of its value.", notice.costLine)
    }

    @Test
    fun fee_unavailable_hides_dust() {
        val snap = fixtureSnapshot(feeRate = null)
        assertTrue(snap.coins.none { it.nearDust })
        assertTrue(snap.coins.all { it.spendCostSat == null })
    }

    @Test
    fun bubble_order_linked_then_independent() {
        val bubbles = fixtureSnapshot().bubbles
        assertEquals(
            listOf("c1:0", "c2:0", "c8:0", "c3:0", "c6:0"),
            bubbles.take(5).map { it.key },
        )
        assertTrue(bubbles.take(3).all { it.linkedGroupId != null })
        assertTrue(bubbles.drop(5).all { it.linkedGroupId == null })
        assertTrue(bubbles.last { it.key == "c11:0" }.isFrozen)
    }

    @Test
    fun format_coin_age() {
        assertEquals("Pending", formatCoinAgeDays(null))
        assertEquals("1d", formatCoinAgeDays(1))
        assertEquals("35d", formatCoinAgeDays(35))
        assertEquals("14mo", formatCoinAgeDays(420))
    }

    @Test
    fun empty_selection_hint() {
        val notice = privacyNoticeForSelection(emptyList())
        assertEquals(PrivacyNoticeKind.Hint, notice.kind)
        assertFalse(notice.message.isBlank())
    }

    @Test
    fun privacy_single_coin_safe_copy() {
        val snap = fixtureSnapshot()
        val c4 = snap.coins.first { it.key == "c4:0" }
        val notice = privacyNoticeForSelection(listOf(c4))
        assertEquals(PrivacyNoticeKind.Safe, notice.kind)
        assertTrue(notice.message.contains("One coin selected"))
        assertTrue(notice.message.contains("does not link"))
        assertNull(notice.costLine)
    }

    @Test
    fun snapshot_splits_spendable_from_frozen() {
        val snap = fixtureSnapshot()
        assertEquals(292L, snap.frozenSats)
        assertEquals(275_226L - 292L, snap.spendableSats)
        assertEquals(CoinAvailability.Spendable, snap.availability)
    }

    @Test
    fun filter_recomputes_group_totals() {
        val snap = fixtureSnapshot()
        val frozen = filterCoinControlGroups(snap, CoinControlFilter.Frozen)
        assertEquals(1, frozen.size)
        assertEquals(listOf("c11:0"), frozen.single().coins.map { it.key })
        assertEquals(292L, frozen.single().totalSats)

        val change = filterCoinControlGroups(snap, CoinControlFilter.Change)
        assertTrue(change.isNotEmpty())
        for (group in change) {
            assertEquals(group.coins.sumOf { it.valueSats }, group.totalSats)
        }
        val salaryChange = change.first { it.title == "Salary Sept group" }
        assertEquals(listOf("c2:0", "c8:0"), salaryChange.coins.map { it.key })
        assertEquals(63_356L, salaryChange.totalSats)
    }

    @Test
    fun spendable_selected_keys_drops_frozen() {
        val utxos =
            listOf(
                WalletUtxoRow(
                    key = "a:0",
                    txid = "aa",
                    vout = 0,
                    outpointShort = "a",
                    valueSats = 100,
                    scriptPubKey = ByteArray(0),
                    amountLabel = "",
                    height = 1,
                    ageLabel = "1d",
                    valueBar = "",
                    name = null,
                    isChange = false,
                    isFrozen = false,
                ),
                WalletUtxoRow(
                    key = "b:0",
                    txid = "bb",
                    vout = 0,
                    outpointShort = "b",
                    valueSats = 50,
                    scriptPubKey = ByteArray(0),
                    amountLabel = "",
                    height = 1,
                    ageLabel = "1d",
                    valueBar = "",
                    name = null,
                    isChange = false,
                    isFrozen = true,
                ),
            )
        assertEquals(listOf("a:0"), spendableSelectedKeys(utxos, setOf("a:0", "b:0")))
    }
}

private data class FixtureCoin(
    val id: String,
    val valueSat: Long,
    val label: String?,
    val isChange: Boolean,
    val parentPrefix: String,
    val ageDays: Long,
    val isFrozen: Boolean,
    val group: String,
)

private val FIXTURE =
    listOf(
        FixtureCoin("c1", 112_400, "Salary Sept", false, "", 35, false, "A"),
        FixtureCoin("c2", 58_210, null, true, "3a798327", 1, false, "A"),
        FixtureCoin("c8", 5_146, null, true, "7a89d9aa", 34, false, "A"),
        FixtureCoin("c3", 38_050, null, true, "e97c3c3d", 161, false, "B"),
        FixtureCoin("c6", 11_460, null, true, "c0f38945", 168, false, "B"),
        FixtureCoin("c4", 21_775, "Bisq trade", false, "", 160, false, "c4"),
        FixtureCoin("c5", 15_320, "Refund", false, "", 210, false, "c5"),
        FixtureCoin("c7", 8_920, null, false, "", 330, false, "c7"),
        FixtureCoin("c9", 2_610, null, false, "", 412, false, "c9"),
        FixtureCoin("c10", 1_043, null, false, "", 420, false, "c10"),
        FixtureCoin("c11", 292, "Unsolicited", false, "", 40, true, "c11"),
    )

/** [provableLinks] false keeps the fixture groups but makes every link a change guess. */
private fun fixtureSnapshot(
    feeRate: Double? = 10.0,
    provableLinks: Boolean = true,
    certainOverride: Map<String, String> = emptyMap(),
): CoinControlSnapshot {
    val p2wpkh =
        ByteArray(22) {
            if (it == 0) {
                0x00
            } else if (it == 1) {
                0x14
            } else {
                0x01
            }
        }
    val utxos =
        FIXTURE.map { f ->
            WalletUtxoRow(
                key = "${f.id}:0",
                txid = if (f.parentPrefix.isNotEmpty()) f.parentPrefix + "00".repeat(28) else "${f.id}${"0".repeat(62)}",
                vout = 0,
                outpointShort = f.id,
                valueSats = f.valueSat,
                scriptPubKey = p2wpkh,
                amountLabel = "",
                height = if (f.ageDays >= 0) 1 else 0,
                ageLabel = formatCoinAgeDays(f.ageDays),
                valueBar = "",
                name = f.label,
                isChange = f.isChange,
            )
        }
    val groupIds =
        FIXTURE.associate { f ->
            val key = "${f.id}:0"
            // Map fixture group letters to a shared root so A/B members share groupId.
            val gid =
                when (f.group) {
                    "A" -> "group-A"
                    "B" -> "group-B"
                    else -> key
                }
            key to gid
        }
    val frozen = FIXTURE.filter { it.isFrozen }.map { "${it.id}:0" }.toSet()
    val ages = FIXTURE.associate { "${it.id}:0" to formatCoinAgeDays(it.ageDays) }
    val certain = (if (provableLinks) groupIds else groupIds.keys.associateWith { it }) + certainOverride
    return buildCoinControlSnapshot(utxos, CoinLinkage(certain = certain, likely = groupIds), frozen, feeRate, ages)
}
