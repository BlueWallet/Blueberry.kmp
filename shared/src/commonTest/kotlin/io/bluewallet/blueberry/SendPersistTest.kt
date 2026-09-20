package io.bluewallet.blueberry

import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.SendInputUtxo
import io.bluewallet.blueberry.wallet.SignedSendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SendPersistTest {
    @Test
    fun persist_clears_arm_only_when_the_same_row_is_still_armed() {
        val first =
            sendRecord(
                destination = "bc1qdest",
                utxos = listOf(SendInputUtxo("bb".repeat(32), 1, 50_000L, ByteArray(0))),
                signed =
                    SignedSendResult(
                        txHex = "aa",
                        txid = "aa".repeat(32),
                        feeSats = 1L,
                        vsize = 1,
                        changeSats = 0L,
                        changeVouts = emptyList(),
                    ),
            )
        val second = first.copy(txid = "cc".repeat(32), txHex = "cc")
        assertEquals(null, armedAfterPersist(first, first, wrote = true))
        assertEquals(second, armedAfterPersist(second, first, wrote = true))
        assertEquals(first, armedAfterPersist(first, first, wrote = false))
    }

    @Test
    fun persist_send_only_after_matching_broadcast() {
        assertEquals(true, shouldPersistSend("success", "aa", "aa"))
        assertEquals(false, shouldPersistSend("error", "aa", "aa"))
        assertEquals(false, shouldPersistSend("success", "bb", "aa"))
        val db = createSqliteDatabase(":memory:")
        val signed =
            SignedSendResult(
                txHex = "010203",
                txid = "aa".repeat(32),
                feeSats = 200L,
                vsize = 140,
                changeSats = 0L,
                changeVouts = emptyList(),
            )
        val row =
            sendRecord(
                destination = "bc1qdest",
                utxos = listOf(SendInputUtxo("bb".repeat(32), 1, 50_000L, ByteArray(0))),
                signed = signed,
            )
        persistSendIfBroadcast(db, row, BroadcastSnapshot(txHex = "010203", phase = "sending"))
        assertEquals(null, db.sends.get(signed.txid))
        persistSendIfBroadcast(db, row, BroadcastSnapshot(txHex = "010203", phase = "success"))
        val saved = db.sends.get(signed.txid)!!
        assertEquals("010203", saved.txHex)
        assertEquals("bc1qdest", saved.destination)
        assertEquals(1, saved.coins.size)
        assertEquals("bb".repeat(32), saved.coins[0].txid)
        assertEquals(1, saved.coins[0].vout)
        assertEquals(50_000L, saved.coins[0].valueSats)
        assertEquals(0L, saved.confirmedInBlock)
        assertTrue(saved.createdAt > 0L)
        db.close()
    }

    @Test
    fun persist_success_puts_pending_row_on_the_home_list() {
        val db = createSqliteDatabase(":memory:")
        val store = createWalletTxsStore()
        val signed =
            SignedSendResult(
                txHex = "010203",
                txid = "aa".repeat(32),
                feeSats = 200L,
                vsize = 140,
                changeSats = 0L,
                changeVouts = emptyList(),
            )
        val row =
            sendRecord(
                destination = "bc1qdest",
                utxos = listOf(SendInputUtxo("bb".repeat(32), 1, 50_000L, ByteArray(0))),
                signed = signed,
            )
        hydrateWallet(db, store, null, 1)
        assertEquals(emptyList(), store.get().txs)
        assertEquals(1L, store.get().at)
        assertEquals(true, persistSendAndRefresh(db, store, null, row, BroadcastSnapshot(txHex = "010203", phase = "success")))
        val pending = store.get().txs.single()
        assertEquals(signed.txid, pending.txid)
        assertEquals("pending", pending.timeLabel.trim())
        assertEquals(0, pending.height)
        db.close()
    }
}
