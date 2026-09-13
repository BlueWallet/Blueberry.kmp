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
        store.apply(snap(1000, 100, 1000))
        store.apply(snap(2000, 200, 1000))
        assertNull(store.get().etaMs)

        store.setParsingActive(true)
        store.apply(snap(3000, 300, 1000))
        assertNull(store.get().etaMs)
        store.apply(snap(4000, 400, 1000))
        assertEquals(6000, store.get().etaMs)
    }

    @Test
    fun pause_clears_eta_resume_excludes_paused_time() {
        val store = createWalletTxsStore()
        store.setParsingActive(true)
        store.apply(snap(1000, 100, 1000))
        store.apply(snap(2000, 200, 1000))
        assertEquals(8000, store.get().etaMs)

        store.setParsingActive(false)
        assertNull(store.get().etaMs)
        store.apply(snap(1_000_000, 300, 1000))
        assertNull(store.get().etaMs)

        store.setParsingActive(true)
        store.apply(snap(1_001_000, 400, 1000))
        assertNull(store.get().etaMs)
        store.apply(snap(1_002_000, 500, 1000))
        assertEquals(5000, store.get().etaMs)
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
    fun setBlockCounts_resets_eta_samples_when_a_new_backlog_starts() {
        val store = createWalletTxsStore()
        store.setParsingActive(true)
        store.apply(snap(1000, 100, 1000))
        store.apply(snap(2000, 1000, 1000))
        assertNull(store.get().etaMs)

        store.setBlockCounts(1000, 1100)
        assertNull(store.get().etaMs)
        assertEquals(1100, store.get().blocksTotal)

        store.apply(snap(601_000, 1001, 1100))
        assertNull(store.get().etaMs)
        store.apply(snap(602_000, 1002, 1100))
        assertEquals(98_000, store.get().etaMs)
    }
}
