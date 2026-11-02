package io.bluewallet.blueberry

import io.bluewallet.blueberry.parse.CoinLinkGraph
import io.bluewallet.blueberry.parse.CoinLinkage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CoinSheetModelTest {
    @Test
    fun age_dates_and_derivation_chips() {
        assertEquals("36 days old", formatCoinAgeLong(0, 36 * 86_400L))
        assertEquals("2 hours old", formatCoinAgeLong(0, 2 * 3_600L))
        assertEquals("14 months old", formatCoinAgeLong(0, 420 * 86_400L))
        assertEquals("Jan 1", formatSheetWhen(0, 1_699_920_000))
        assertEquals("Today 00:00", formatSheetWhen(1_699_920_000, 1_699_920_000))
        val chips = derivationChips("m/84'/0'/0'/1/75")
        assertNotNull(chips)
        assertEquals("84'", chips[0].value)
        assertEquals("Native SegWit", chips[0].meaning)
        assertEquals("0'", chips[1].value)
        assertEquals("Bitcoin", chips[1].meaning)
        assertEquals("0'", chips[2].value)
        assertEquals("Account 0", chips[2].meaning)
        assertEquals("1", chips[3].value)
        assertEquals("Change", chips[3].meaning)
        assertEquals("75", chips[4].value)
        assertEquals("Index", chips[4].meaning)
        assertTrue(chips[3].accent && chips[4].accent)
        assertFalse(chips[0].accent)
    }

    @Test
    fun map_pins_the_open_coin_and_repeats() {
        val open = MapCoin("a", 100, 1, false, "a", null, "A")
        val other = MapCoin("b", 25, 2, false, "b", null, "B")
        val map = layoutCoinMap(listOf(open, other), "a", null, emptyList())
        assertEquals(map, layoutCoinMap(listOf(open, other), "a", null, emptyList()))
        val dot = map.dots.first { it.open }
        assertTrue(abs(dot.x - COIN_MAP_WIDTH * COIN_MAP_OPEN_X) < 0.01f)
        assertTrue(abs(dot.y - COIN_MAP_HEIGHT * 0.5f) < 0.01f)
        assertTrue(abs(dot.diameter - 44f) < 0.01f)
        assertTrue(map.description.contains("This coin is solo. 1 solo coin is separate."))
    }

    @Test
    fun map_groups_and_their_names_do_not_collide() {
        val coins =
            listOf(
                MapCoin("a1", 10_000, 1, false, "A", "Linked group A", "a1"),
                MapCoin("a2", 9_000, 1, false, "A", "Linked group A", "a2"),
                MapCoin("a3", 1_000, 1, false, "A", "Linked group A", "a3"),
                MapCoin("b1", 20_000, 2, false, "B", "Linked group B", "b1"),
                MapCoin("b2", 18_000, 2, false, "B", "Linked group B", "b2"),
            ) + (1..5).map { MapCoin("s$it", it * 30_000L, 3, false, "s$it", null, "s$it") }
        val map = layoutCoinMap(coins, "a1", "a3", listOf("a2", "a3"))
        assertEquals(0, map.overflow)
        assertTrue(abs(map.dots.maxOf { it.diameter } - 44f) < 0.01f, "map shrank although it fits at full size")
        val open = map.dots.first { it.open }
        assertTrue(abs(open.x - COIN_MAP_WIDTH / 2f) < 0.01f)
        assertEquals(2, map.outlines.size)
        val (first, second) = map.outlines
        assertFalse(first.left < second.right && second.left < first.right && first.top < second.bottom && second.top < first.bottom)
        for (tag in map.tags) {
            val halfWidth = tag.text.length * 3.2f
            for (outline in map.outlines) {
                val own = abs(tag.x - (outline.left + outline.right) / 2f) < 0.01f && abs(tag.y - outline.bottom - 14f) < 0.01f
                val hits = tag.x + halfWidth > outline.left && tag.x - halfWidth < outline.right && tag.y + 8f > outline.top && tag.y - 8f < outline.bottom
                assertFalse(!own && hits, "${tag.text} overlaps another group")
            }
        }
    }

    @Test
    fun map_keeps_a_crowded_wallet_inside_and_apart() {
        val coins =
            listOf(
                MapCoin("a1", 10_000, 1, false, "A", "Linked group A", "a1"),
                MapCoin("a2", 6_000, 1, false, "A", "Linked group A", "a2"),
                MapCoin("a3", 1_000, 1, false, "A", "Linked group A", "a3"),
                MapCoin("b1", 20_000, 2, false, "B", "Linked group B", "b1"),
                MapCoin("b2", 15_000, 2, false, "B", "Linked group B", "b2"),
            ) + (1..4).map { MapCoin("s$it", it * 30_000L, 3, false, "s$it", null, "s$it") }
        val map = layoutCoinMap(coins, "a1", "a3", listOf("a3", "a2"))
        for (outline in map.outlines) {
            assertTrue(outline.left >= 0f && outline.top >= 0f, "outline leaves the map")
            assertTrue(outline.right <= COIN_MAP_WIDTH && outline.bottom <= COIN_MAP_HEIGHT, "outline leaves the map")
        }
        for (dot in map.dots) {
            assertTrue(dot.x - dot.diameter / 2f >= 0f && dot.x + dot.diameter / 2f <= COIN_MAP_WIDTH, "${dot.key} leaves the map")
        }
        map.outlines.forEachIndexed { i, a ->
            map.outlines.drop(i + 1).forEach { b ->
                assertFalse(a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom, "groups overlap")
            }
        }
        assertEquals(map, layoutCoinMap(coins, "a1", "a3", listOf("a3", "a2")))
        assertEquals(map, layoutCoinMap(coins, "a1", null, listOf("a3", "a2")).withSelection("a3"))
    }

    @Test
    fun solo_sheet_and_near_dust_row() {
        val solo = utxo("aa", 100_000, change = false, height = 100)
        val dust = utxo("bb", 292, change = true, height = 100, frozen = true)
        val rows = listOf(solo, dust)
        val keys = rows.associate { it.key to it.key }
        val control = buildCoinControlSnapshot(rows, CoinLinkage(keys, keys), setOf(dust.key), 10.0)
        val snap =
            WalletTxsSnapshot(
                utxos = rows,
                txs = listOf(tx(solo.txid, "Bike parts", 1_000)),
                coinLinkGraph = CoinLinkGraph.Empty,
            )
        val model =
            buildCoinSheetModel(
                CoinSheetRequest(dust.key, snap, control, emptyMap(), 1_000 + 36 * 86_400L, 200, false),
            )
        assertNotNull(model)
        assertEquals("Change", model.chips[0].text)
        assertTrue(model.chips.any { it.text == "Frozen" })
        assertTrue(model.chips.any { it.text == "Near dust" })
        assertTrue(model.chips.any { it.text == "Solo" })
        assertEquals("Not linked to any coin", model.linkedTitle)
        assertEquals("1 others separate", model.linkedAside)
        assertFalse(model.sendEnabled)
        assertEquals("Unfreeze it first", model.sendSubtitle)
        val cost = model.spendRows.first { it.title.startsWith("Costs about") }
        assertEquals("Costs about 680 sats to spend", cost.title)
        assertEquals("233% of its value at the current fee rate.", cost.subtitle)
        assertTrue(model.spendRows.any { it.title == "With any other coin: its first link" })
    }

    @Test
    fun labeled_group_chip_uses_the_largest_coin() {
        val big = utxo("cc", 200_000, name = "Salary Sept")
        val small = utxo("dd", 5_000)
        val root = big.key
        val keys = mapOf(big.key to root, small.key to root)
        val control = buildCoinControlSnapshot(listOf(big, small), CoinLinkage(keys, keys), emptySet(), null)
        val snap = WalletTxsSnapshot(utxos = listOf(big, small), coinLinkGraph = CoinLinkGraph.Empty)
        val model = buildCoinSheetModel(CoinSheetRequest(big.key, snap, control, emptyMap(), 0, null, true))
        assertNotNull(model)
        assertTrue(model.chips.any { it.text == "Salary Sept group · 2 coins" })
        assertEquals("Linked to 1 of your coins", model.linkedTitle)
        assertTrue(model.syncing)
        assertEquals("Send this coin", model.sendTitle)
        assertEquals("Starts a send with this coin selected", model.sendSubtitle)
        assertTrue(model.sendEnabled)
    }
}

@Suppress("LongParameterList")
private fun utxo(
    id: String,
    sats: Long,
    change: Boolean = false,
    height: Int = 0,
    frozen: Boolean = false,
    name: String? = null,
): WalletUtxoRow {
    val txid = id.repeat(32).take(64)
    return WalletUtxoRow(
        key = "$txid:0",
        txid = txid,
        vout = 0,
        outpointShort = "$id:0",
        valueSats = sats,
        scriptPubKey = byteArrayOf(0x00, 0x14) + ByteArray(20),
        address = "bc1qexample",
        path = if (change) "m/84'/0'/0'/1/75" else "m/84'/0'/0'/0/9",
        amountLabel = "",
        height = height,
        ageLabel = "1d",
        valueBar = "",
        name = name,
        isChange = change,
        isFrozen = frozen,
    )
}

private fun tx(
    txid: String,
    label: String,
    time: Long,
): WalletTxRow =
    WalletTxRow(
        txid = txid,
        shortTxid = txid.take(8),
        height = 100,
        timeLabel = "Sep 14",
        netDeltaSats = 0,
        netDeltaLabel = "0",
        paymentLabel = label,
        blockTimeS = time,
    )
