package io.bluewallet.blueberry

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

private fun fixtureSnapshot(feeRate: Double? = 10.0): CoinControlSnapshot {
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
    return buildCoinControlSnapshot(utxos, groupIds, frozen, feeRate, ages)
}
