package io.bluewallet.blueberry

import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxHash
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import io.bluewallet.blueberry.parse.outpointKey
import io.bluewallet.blueberry.storage.StoredTx
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.createWallet
import io.bluewallet.blueberry.wallet.saveWalletSecret
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

    @Test
    fun set_note_names_empty_coins_and_keeps_a_different_name() {
        val db = createSqliteDatabase(":memory:")
        saveWalletSecret(
            db,
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        )
        val wallet = createWallet(db)
        val first = wallet.snapshot().addresses.first { !it.change && it.index == 1 }
        val second = wallet.snapshot().addresses.first { !it.change && it.index == 2 }
        val tx =
            Transaction(
                2L,
                listOf(TxIn(OutPoint(TxHash(ByteArray(32) { 1 }), 0L), 0xffffffffL)),
                listOf(
                    TxOut(Satoshi(40_000L), first.scriptPubKey),
                    TxOut(Satoshi(10_000L), second.scriptPubKey),
                ),
                0L,
            )
        val txid = tx.txid.toString()
        db.transactions.upsert(StoredTx(txid, 100, 0, "11".repeat(32), Transaction.write(tx), 50_000L))
        val keep = outpointKey(txid, 1)
        db.utxoNames.upsert(keep, "Keep")
        val store = createWalletTxsStore()
        setTxNote(db, wallet, store, txid, "Rent")
        assertEquals("Rent", db.utxoNames.get(outpointKey(txid, 0)))
        assertEquals("Keep", db.utxoNames.get(keep))
        setTxNote(db, wallet, store, txid, "Coffee")
        assertEquals("Coffee", db.utxoNames.get(outpointKey(txid, 0)))
        assertEquals("Keep", db.utxoNames.get(keep))
        db.close()
    }
}
