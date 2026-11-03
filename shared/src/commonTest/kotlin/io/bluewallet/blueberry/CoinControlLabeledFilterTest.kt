package io.bluewallet.blueberry

import io.bluewallet.blueberry.parse.CoinLinkage
import kotlin.test.Test
import kotlin.test.assertEquals

/** Labeled means "shows a label"; Change and Frozen are independent tags, not label states. */
class CoinControlLabeledFilterTest {
    private fun coin(
        id: String,
        name: String? = null,
        addressLabel: String? = null,
        isChange: Boolean = false,
    ) = WalletUtxoRow(
        key = "$id:0",
        txid = id.padEnd(64, '0'),
        vout = 0,
        outpointShort = id,
        valueSats = 10_000,
        scriptPubKey = ByteArray(0),
        address = "bc1q$id",
        amountLabel = "",
        height = 1,
        ageLabel = "1d",
        valueBar = "",
        name = name,
        isChange = isChange,
        addressLabel = addressLabel,
    )

    private fun snapshot(
        coins: List<WalletUtxoRow>,
        frozen: Set<String> = emptySet(),
    ) = buildCoinControlSnapshot(coins, CoinLinkage(emptyMap(), emptyMap()), frozen)

    private fun CoinControlSnapshot.keysIn(filter: CoinControlFilter) = filterCoinControlGroups(this, filter).flatMap { g -> g.coins.map { it.key } }.toSet()

    @Test
    fun labelled_change_coin_counts_as_labeled_and_as_change() {
        val snap =
            snapshot(
                listOf(
                    coin("aa", addressLabel = "rent from gareth"),
                    coin("bb", name = "change from: test tx to persona", isChange = true),
                ),
            )
        assertEquals(2, snap.filterCounts[CoinControlFilter.All])
        assertEquals(2, snap.filterCounts[CoinControlFilter.Labeled])
        assertEquals(1, snap.filterCounts[CoinControlFilter.Change])
        assertEquals(0, snap.filterCounts[CoinControlFilter.NoLabel])
        assertEquals(setOf("aa:0", "bb:0"), snap.keysIn(CoinControlFilter.Labeled))
        assertEquals(setOf("bb:0"), snap.keysIn(CoinControlFilter.Change))
    }

    @Test
    fun labelled_frozen_coin_counts_as_labeled_and_as_frozen() {
        val snap = snapshot(listOf(coin("aa", name = "Unsolicited")), frozen = setOf("aa:0"))
        assertEquals(1, snap.filterCounts[CoinControlFilter.Labeled])
        assertEquals(1, snap.filterCounts[CoinControlFilter.Frozen])
        assertEquals(setOf("aa:0"), snap.keysIn(CoinControlFilter.Labeled))
    }

    @Test
    fun no_label_keeps_only_spendable_received_coins_that_show_no_label() {
        val snap =
            snapshot(
                listOf(
                    coin("aa"),
                    coin("bb", isChange = true),
                    coin("cc", name = "  "),
                    coin("dd", addressLabel = "savings"),
                    coin("ee"),
                ),
                frozen = setOf("ee:0"),
            )
        assertEquals(setOf("aa:0", "cc:0"), snap.keysIn(CoinControlFilter.NoLabel))
        assertEquals(2, snap.filterCounts[CoinControlFilter.NoLabel])
        assertEquals(setOf("dd:0"), snap.keysIn(CoinControlFilter.Labeled))
        assertEquals(1, snap.filterCounts[CoinControlFilter.Labeled])
    }
}
