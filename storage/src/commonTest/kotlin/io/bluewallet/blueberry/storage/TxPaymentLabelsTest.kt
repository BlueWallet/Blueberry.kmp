package io.bluewallet.blueberry.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TxPaymentLabelsTest {
    @Test
    fun get_upsert_list_by_txid_replace_keeps_one_row() {
        val db = createSqliteDatabase(":memory:")
        val txid = "aa".repeat(32)
        assertNull(db.txPaymentLabels.get(txid))
        assertEquals(emptyList(), db.txPaymentLabels.list())
        db.txPaymentLabels.upsert(TxPaymentLabelRow(txid, "rent"))
        assertEquals(TxPaymentLabelRow(txid, "rent"), db.txPaymentLabels.get(txid))
        assertEquals(listOf(TxPaymentLabelRow(txid, "rent")), db.txPaymentLabels.list())
        db.txPaymentLabels.upsert(TxPaymentLabelRow(txid, "rent paid"))
        assertEquals(TxPaymentLabelRow(txid, "rent paid"), db.txPaymentLabels.get(txid))
        assertEquals(1, db.txPaymentLabels.list().size)
        db.close()
    }

    @Test
    fun delete_removes_label_for_txid() {
        val db = createSqliteDatabase(":memory:")
        val txid = "aa".repeat(32)
        db.txPaymentLabels.upsert(TxPaymentLabelRow(txid, "rent"))
        db.txPaymentLabels.delete(txid)
        assertNull(db.txPaymentLabels.get(txid))
        assertEquals(emptyList(), db.txPaymentLabels.list())
        db.close()
    }
}
