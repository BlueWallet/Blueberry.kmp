package io.bluewallet.blueberry

import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.BlocksProgressPayload
import io.bluewallet.blueberry.bus.SyncCatchupPayload
import io.bluewallet.blueberry.bus.SyncCatchupReason
import io.bluewallet.blueberry.bus.SyncIdlePayload
import io.bluewallet.blueberry.bus.WalletTxsPayload
import io.bluewallet.blueberry.bus.createMessageBus
import io.bluewallet.blueberry.parse.formatBtc
import io.bluewallet.blueberry.storage.DownloadedBlock
import io.bluewallet.blueberry.storage.HeaderWrite
import io.bluewallet.blueberry.storage.StoredTx
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.headers.BlockHeader
import io.bluewallet.headers.encodeBlockHeader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class WalletTxsHydrateTest {
    private fun headerAt(timestamp: Long): ByteArray = encodeBlockHeader(
        BlockHeader(
            version = 1,
            previousBlockHash = ByteArray(32),
            merkleRoot = ByteArray(32),
            timestamp = timestamp,
            bits = 0x1d00ffff,
            nonce = 0,
        ),
    )

    @Test
    fun seeds_and_refreshes_txs_balance_label_and_parse_backlog_from_db_events() {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.blocks.insert(DownloadedBlock(1, "11".repeat(32), byteArrayOf(0xaa.toByte())))
        db.blocks.insert(DownloadedBlock(2, "22".repeat(32), byteArrayOf(0xbb.toByte())))
        db.transactions.upsert(
            StoredTx("ab".repeat(32), 1, 0, "11".repeat(32), byteArrayOf(0x00), 1500),
        )

        val store = createWalletTxsStore()
        val off = bindWalletTxsEvents(bus, db, store)
        hydrateWallet(db, store, null, 1)

        assertEquals(0, store.get().blocksParsed)
        assertEquals(2, store.get().blocksTotal)
        assertEquals("0.00001500 BTC", store.get().balanceBtcLabel)
        assertEquals(1, store.get().txs.size)
        assertEquals("+0.00001500 BTC", store.get().txs[0].netDeltaLabel)
        assertEquals("#1".padEnd(16), store.get().txs[0].timeLabel)

        db.parsedBlocks.mark(1)
        db.transactions.upsert(
            StoredTx("cd".repeat(32), 2, 0, "22".repeat(32), byteArrayOf(0x00), -500),
        )
        bus.emit(Event.WalletTxs, WalletTxsPayload(at = 10))
        assertEquals(10, store.get().at)
        assertEquals(1, store.get().blocksParsed)
        assertEquals(listOf(2, 1), store.get().txs.map { it.height })
        assertEquals("0.00001000 BTC", store.get().balanceBtcLabel)

        val txsBefore = store.get().txs
        db.blocks.insert(DownloadedBlock(3, "33".repeat(32), byteArrayOf(0xcc.toByte())))
        bus.emit(Event.BlocksProgress, BlocksProgressPayload(at = 11, downloaded = 3, matched = 3))
        assertEquals(3, store.get().blocksTotal)
        assertEquals(1, store.get().blocksParsed)
        assertSame(txsBefore, store.get().txs)

        bus.emit(Event.WalletTxs, WalletTxsPayload(at = 12))
        assertSame(txsBefore, store.get().txs)
        assertEquals(3, store.get().blocksTotal)
        assertEquals(1, store.get().blocksParsed)

        off()
        db.close()
    }

    @Test
    fun timeLabel_decodes_header_timestamp() {
        val db = createSqliteDatabase(":memory:")
        val nowMs = 1_700_000_000_000L
        val ts = nowMs / 1000 - 3600
        db.headers.append(
            listOf(
                HeaderWrite(
                    height = 1,
                    hashInternalHex = "11".repeat(32),
                    header = headerAt(ts),
                    cumulativeWork = com.ionspin.kotlin.bignum.integer.BigInteger.ONE,
                ),
            ),
        )
        db.transactions.upsert(
            StoredTx("ab".repeat(32), 1, 0, "11".repeat(32), byteArrayOf(0x00), 1),
        )
        val snap = snapshotFromDb(db, nowMs, nowMs)
        assertEquals("1h ago".padEnd(16), snap.txs[0].timeLabel)
        assertEquals(emptyList(), snap.utxos)
        db.close()
    }

    @Test
    fun forwards_sync_state_so_eta_is_active_only() {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        for (height in 1..6) {
            db.blocks.insert(
                DownloadedBlock(
                    height,
                    "$height".repeat(32).padEnd(64, '0'),
                    byteArrayOf(height.toByte()),
                ),
            )
        }
        val store = createWalletTxsStore()
        val off = bindWalletTxsEvents(bus, db, store)
        hydrateWallet(db, store, null, 1)

        assertEquals(6, store.get().blocksTotal)
        assertEquals(0, store.get().blocksParsed)
        assertNull(store.get().etaMs)

        bus.emit(Event.SyncIdle, SyncIdlePayload(at = 1000))
        db.parsedBlocks.mark(1)
        bus.emit(Event.WalletTxs, WalletTxsPayload(at = 1000))
        assertNull(store.get().etaMs)
        db.parsedBlocks.mark(2)
        bus.emit(Event.WalletTxs, WalletTxsPayload(at = 2000))
        assertEquals(4000, store.get().etaMs)

        bus.emit(Event.SyncCatchup, SyncCatchupPayload(at = 2000, reason = SyncCatchupReason.BLOCKS))
        assertNull(store.get().etaMs)

        db.parsedBlocks.mark(3)
        bus.emit(Event.WalletTxs, WalletTxsPayload(at = 1_000_000))
        assertNull(store.get().etaMs)

        bus.emit(Event.SyncIdle, SyncIdlePayload(at = 1_001_000))
        db.parsedBlocks.mark(4)
        bus.emit(Event.WalletTxs, WalletTxsPayload(at = 1_001_000))
        assertNull(store.get().etaMs)
        db.parsedBlocks.mark(5)
        bus.emit(Event.WalletTxs, WalletTxsPayload(at = 1_002_000))
        assertEquals(1000, store.get().etaMs)

        off()
        db.close()
    }

    @Test
    fun formatParseProgress_matches_original() {
        assertEquals("1/2 blocks parsed", formatParseProgress(1, 2, null))
        assertEquals("1/2 blocks parsed (ETA 2s)", formatParseProgress(1, 2, 1500))
        assertEquals(formatBtc(0), emptyWalletTxsSnapshot.balanceBtcLabel)
    }
}
