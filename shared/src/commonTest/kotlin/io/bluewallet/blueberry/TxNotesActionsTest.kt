package io.bluewallet.blueberry

import io.bluewallet.blueberry.storage.StoredTx
import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TxNotesActionsTest {
    @Test
    fun set_trims_and_shows_on_snapshot_empty_deletes() {
        val db = createSqliteDatabase(":memory:")
        val txid = "ab".repeat(32)
        db.transactions.upsert(StoredTx(txid, 1, 0, "11".repeat(32), byteArrayOf(0x00), 1))
        val store = createWalletTxsStore()
        setTxNote(db, null, store, txid, "  coffee  ")
        assertEquals("coffee", db.txPaymentLabels.get(txid)?.label)
        assertEquals(
            "coffee",
            store
                .get()
                .txs
                .single()
                .paymentLabel,
        )
        setTxNote(db, null, store, txid, "   ")
        assertNull(db.txPaymentLabels.get(txid))
        assertNull(
            store
                .get()
                .txs
                .single()
                .paymentLabel,
        )
        db.close()
    }
}
