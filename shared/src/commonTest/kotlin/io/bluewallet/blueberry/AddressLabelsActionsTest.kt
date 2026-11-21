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
import io.bluewallet.blueberry.wallet.canonicalBitcoinAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

        assertTrue(setAddressLabel(db, null, store, address, "   "))
        assertNull(db.addressLabels.get(address))
        assertNull(store.labelOfOnlyTx())
        db.close()
    }

    @Test
    fun set_is_noop_when_stored_label_already_matches() {
        val db = createSqliteDatabase(":memory:")
        val store = createWalletTxsStore()
        val address = "bc1qnoop"

        assertFalse(setAddressLabel(db, null, store, address, "   "))
        assertNull(db.addressLabels.get(address))
        assertNull(db.keyValue.get(LABELS_DIRTY_KEY))

        assertTrue(setAddressLabel(db, null, store, address, "  donations  "))
        assertEquals("donations", db.addressLabels.get(address))
        assertEquals("1", db.keyValue.get(LABELS_DIRTY_KEY))

        db.keyValue.set(LABELS_DIRTY_KEY, "0")
        assertFalse(setAddressLabel(db, null, store, address, " donations "))
        assertEquals("donations", db.addressLabels.get(address))
        assertEquals("0", db.keyValue.get(LABELS_DIRTY_KEY))

        assertTrue(setAddressLabel(db, null, store, address, ""))
        assertNull(db.addressLabels.get(address))
        assertEquals("1", db.keyValue.get(LABELS_DIRTY_KEY))
        db.keyValue.set(LABELS_DIRTY_KEY, "0")
        assertFalse(setAddressLabel(db, null, store, address, "  "))
        assertNull(db.addressLabels.get(address))
        assertEquals("0", db.keyValue.get(LABELS_DIRTY_KEY))
        db.close()
    }

    @Test
    fun uppercase_bech32_label_is_stored_and_read_under_the_canonical_key() {
        val db = createSqliteDatabase(":memory:")
        val store = createWalletTxsStore()
        val script = byteArrayOf(0x00, 0x14) + ByteArray(20) { 9 }
        val address = addressFromOutputScript(script)!!
        val upper = address.uppercase()
        db.addressLabels.upsert(upper, "savings")
        assertEquals("savings", addressLabel(db, upper))
        assertEquals("savings", addressLabel(db, address))
        assertTrue(setAddressLabel(db, null, store, upper, "donations"))
        assertEquals("donations", db.addressLabels.get(canonicalBitcoinAddress(address)))
        assertNull(db.addressLabels.get(upper))
        assertEquals("donations", addressLabel(db, upper))
        db.close()
    }
}

private fun WalletTxsStore.labelOfOnlyTx(): String? = get().txs.single().addressLabel
