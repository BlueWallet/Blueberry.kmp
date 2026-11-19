package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class WalletTxsStoreEtaTest {
    private fun snap(
        at: Long,
        parsed: Int,
        total: Int,
    ): WalletTxsSnapshot =
        emptyWalletTxsSnapshot.copy(
            at = at,
            blocksParsed = parsed,
            blocksTotal = total,
            etaMs = null,
        )

    @Test
    fun estimates_only_while_parsing_is_active() {
        val store = createWalletTxsStore()
        store.apply(snap(1000, 100, 2000))
        store.apply(snap(2000, 200, 2000))
        assertNull(store.get().etaMs)

        store.setParsingActive(true)
        for (i in 3..9) store.apply(snap(i * 1000L, i * 100, 2000))
        assertNull(store.get().etaMs)
        store.apply(snap(10_000, 1000, 2000))
        assertEquals(10_000, store.get().etaMs)
    }

    @Test
    fun pause_clears_eta_resume_excludes_paused_time() {
        val store = createWalletTxsStore()
        store.setParsingActive(true)
        for (i in 1..8) store.apply(snap(i * 1000L, i * 100, 2000))
        assertEquals(12_000, store.get().etaMs)

        store.setParsingActive(false)
        assertNull(store.get().etaMs)
        store.apply(snap(1_000_000, 900, 2000))
        assertNull(store.get().etaMs)

        store.setParsingActive(true)
        for (i in 1..7) store.apply(snap(1_000_000L + i * 1000, 900 + i * 100, 2000))
        assertNull(store.get().etaMs)
        store.apply(snap(1_008_000, 1700, 2000))
        assertEquals(3000, store.get().etaMs)
    }

    @Test
    fun setBlockCounts_updates_backlog_without_replacing_txs() {
        val store = createWalletTxsStore()
        store.apply(
            emptyWalletTxsSnapshot.copy(
                at = 1,
                blocksParsed = 0,
                blocksTotal = 2,
                txs =
                    listOf(
                        WalletTxRow(
                            txid = "ab",
                            shortTxid = "ab",
                            height = 1,
                            timeLabel = "#1".padEnd(16),
                            netDeltaSats = 1,
                            netDeltaLabel = "+1",
                            paymentLabel = null,
                        ),
                    ),
            ),
        )
        val txs = store.get().txs
        store.setBlockCounts(1, 4)
        assertEquals(1, store.get().blocksParsed)
        assertEquals(4, store.get().blocksTotal)
        assertSame(txs, store.get().txs)
    }

    @Test
    fun setBlockCounts_drops_the_speed_when_a_new_backlog_starts() {
        val store = createWalletTxsStore()
        store.setParsingActive(true)
        store.apply(snap(1000, 100, 1000))
        store.apply(snap(2000, 1000, 1000))
        assertNull(store.get().etaMs)

        store.setBlockCounts(1000, 1100)
        assertNull(store.get().etaMs)
        assertEquals(1100, store.get().blocksTotal)

        for (i in 1..7) store.apply(snap(600_000L + i * 1000, 1000 + i, 1100))
        assertNull(store.get().etaMs)
        store.apply(snap(608_000, 1008, 1100))
        assertEquals(92_000, store.get().etaMs)
    }

    @Test
    fun apply_eases_a_one_second_speed_swing() {
        val store = createWalletTxsStore()
        store.setParsingActive(true)
        for (i in 0..7) store.apply(snap(i * 1000L, 1000 * (i + 1), 600_000))
        assertEquals(592_000L, store.get().etaMs)

        // This second runs at twice the speed.
        store.apply(snap(8000, 10_000, 600_000))
        assertEquals(524_878L, store.get().etaMs)
    }

    @Test
    fun setBlockCounts_eases_a_one_second_speed_swing() {
        val store = createWalletTxsStore()
        store.setParsingActive(true)
        for (i in 1..8) store.setBlockCounts(i * 100, 2000, at = i * 1000L)
        assertEquals(12_000, store.get().etaMs)

        // This second runs at twice the speed.
        store.setBlockCounts(1000, 2000, at = 9000)
        assertEquals(8896, store.get().etaMs)
    }

    @Test
    fun eta_follows_full_rebuilds_and_count_updates() {
        val store = createWalletTxsStore()
        store.setParsingActive(true)
        for (i in 1..8) store.apply(snap(i * 1000L, i * 100, 2000))
        store.setBlockCounts(900, 2000, at = 9000)
        store.setBlockCounts(1000, 2000, at = 10_000)
        assertEquals(10_000, store.get().etaMs)

        store.apply(snap(11_000, 1100, 2000))
        assertEquals(9000, store.get().etaMs)
    }

    @Test
    fun count_update_without_a_time_uses_the_known_speed() {
        val store = createWalletTxsStore()
        store.setParsingActive(true)
        for (i in 1..8) store.apply(snap(i * 1000L, i * 100, 2000))
        assertEquals(12_000, store.get().etaMs)

        store.setBlockCounts(800, 2100)
        assertEquals(13_000, store.get().etaMs)
        assertEquals(2100, store.get().blocksTotal)
    }

    @Test
    fun metadata_update_keeps_the_eta() {
        val store = createWalletTxsStore()
        store.setParsingActive(true)
        for (i in 1..8) store.apply(snap(i * 1000L, i * 100, 2000))
        store.setBlockCounts(900, 2000, at = 9000)
        assertEquals(11_000, store.get().etaMs)

        store.update { it.copy(utxosReady = true) }
        assertEquals(11_000, store.get().etaMs)
    }
}
