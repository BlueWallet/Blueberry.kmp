package io.bluewallet.blueberry

import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxHash
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import io.bluewallet.blueberry.storage.StoredTx
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.addressFromOutputScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AddressLabelsActionsTest {
    @Test
    fun set_trims_marks_labels_dirty_refreshes_tx_list_empty_deletes() {
        val db = createSqliteDatabase(":memory:")
        val store = createWalletTxsStore()
        val script = byteArrayOf(0x00, 0x14) + ByteArray(20) { 3 }
        val address = addressFromOutputScript(script)!!
        val tx =
            Transaction(
                2L,
                listOf(TxIn(OutPoint(TxHash(ByteArray(32)), 0L), 0xffffffffL)),
                listOf(TxOut(Satoshi(1_000L), script)),
                0L,
            )
        db.transactions.upsert(StoredTx(tx.txid.toString(), 1, 0, "11".repeat(32), Transaction.write(tx), 1_000L))

        setAddressLabel(db, null, store, address, "  donations  ")
        assertEquals("donations", db.addressLabels.get(address))
        assertEquals("1", db.keyValue.get(LABELS_DIRTY_KEY))
        assertEquals("donations", store.labelOfOnlyTx())

        setAddressLabel(db, null, store, address, "   ")
        assertNull(db.addressLabels.get(address))
        assertNull(store.labelOfOnlyTx())
        db.close()
    }
}

private fun WalletTxsStore.labelOfOnlyTx(): String? = get().txs.single().addressLabel
