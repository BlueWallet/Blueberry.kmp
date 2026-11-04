package io.bluewallet.blueberry

import io.bluewallet.blueberry.parse.CoinGraphCoin
import io.bluewallet.blueberry.parse.CoinLinkEdge
import io.bluewallet.blueberry.parse.CoinLinkGraph
import io.bluewallet.blueberry.parse.CoinLinkage
import io.bluewallet.blueberry.parse.LinkNode
import io.bluewallet.blueberry.parse.Relation
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
        assertEquals("Pending", formatSheetWhen(null, 0))
        assertEquals("Jan 1", formatSheetWhenLocal("1970-01-01 00:00", "2023-11-14 00:00"))
        assertEquals("Today 17:38", formatSheetWhenLocal("2023-11-14 17:38", "2023-11-14 09:00"))
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
    fun strip_leads_with_the_open_group_and_its_share() {
        val salary = utxo("cc", 200_000, name = "Salary Sept")
        val withIt = utxo("dd", 50_000)
        val alone = utxo("ee", 50_000)
        val root = salary.key
        val keys = mapOf(salary.key to root, withIt.key to root, alone.key to alone.key)
        val control = buildCoinControlSnapshot(listOf(salary, withIt, alone), CoinLinkage(keys, keys), emptySet(), null)
        val group = sheetStrip(control, salary.key)
        assertNotNull(group)
        assertEquals(listOf(salary.key, withIt.key, alone.key), group.bubbles.map { it.key })
        assertEquals(salary.key, group.openKey)
        assertEquals("Salary Sept group · 2 coins · 83% of balance", group.caption)
        val solo = sheetStrip(control, alone.key)
        assertNotNull(solo)
        assertEquals(alone.key, solo.bubbles.first().key)
        assertEquals(listOf(salary.key, withIt.key), solo.bubbles.drop(1).map { it.key })
        assertEquals("This coin · 17% of balance", solo.caption)
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
        assertEquals("101", model.origin?.confirmations)
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
        val sending =
            buildCoinSheetModel(
                CoinSheetRequest(big.key, snap, control.markedSending(setOf(big.key)), emptyMap(), 0, null, true),
            )
        assertNotNull(sending)
        assertFalse(sending.sendEnabled)
        assertEquals("Already in a send", sending.sendSubtitle)
    }

    @Test
    fun address_label_is_the_placeholder_not_the_saved_name() {
        val hinted = utxo("ee", 50_000).copy(addressLabel = "Rent")
        val named = utxo("ff", 40_000, name = "Mine").copy(addressLabel = "Rent")
        val rows = listOf(hinted, named)
        val keys = rows.associate { it.key to it.key }
        val control = buildCoinControlSnapshot(rows, CoinLinkage(keys, keys), emptySet(), null)
        val snap = WalletTxsSnapshot(utxos = rows, coinLinkGraph = CoinLinkGraph.Empty)
        val hint =
            buildCoinSheetModel(CoinSheetRequest(hinted.key, snap, control, emptyMap(), 0, null, false))
        val own =
            buildCoinSheetModel(CoinSheetRequest(named.key, snap, control, emptyMap(), 0, null, false))
        assertNotNull(hint)
        assertEquals("", hint.label)
        assertEquals("Rent", hint.labelPlaceholder)
        assertNotNull(own)
        assertEquals("Mine", own.label)
        assertEquals("Rent", own.labelPlaceholder)
    }

    @Test
    fun linked_coin_shows_its_address_label_like_the_coin_list() {
        val origin = utxo("aa", 60_000, name = "Salary")
        val rent = utxo("bb", 30_000).copy(addressLabel = "Rent")
        val rows = listOf(origin, rent)
        val root = origin.key
        val keys = mapOf(origin.key to root, rent.key to root)
        val control = buildCoinControlSnapshot(rows, CoinLinkage(keys, keys), emptySet(), null)
        val graph =
            CoinLinkGraph(
                coins =
                    rows.associate {
                        it.key to CoinGraphCoin(it.key, it.txid, it.valueSats, "0014", it.isChange, spent = false)
                    },
                txs = emptyMap(),
                neighbors =
                    mapOf(
                        LinkNode.Coin(origin.key) to
                            listOf(CoinLinkEdge(LinkNode.Coin(rent.key), Relation.SameAddressAs, warning = true)),
                        LinkNode.Coin(rent.key) to
                            listOf(CoinLinkEdge(LinkNode.Coin(origin.key), Relation.SameAddressAs, warning = true)),
                    ),
            )
        val snap = WalletTxsSnapshot(utxos = rows, coinLinkGraph = graph)
        val model = buildCoinSheetModel(CoinSheetRequest(origin.key, snap, control, emptyMap(), 0, null, false))
        assertNotNull(model)
        assertEquals(
            "Rent",
            model.links
                .single()
                .steps
                .last()
                .title,
        )
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
