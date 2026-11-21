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
import io.bluewallet.blueberry.wallet.AddressScriptType
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

    @Test
    fun set_note_updates_and_clears_a_derived_change_name() {
        val db = createSqliteDatabase(":memory:")
        saveWalletSecret(
            db,
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        )
        val wallet = createWallet(db)
        val recv = wallet.snapshot().addresses.first { !it.change && it.index == 1 }
        val change = wallet.snapshot().addresses.first { it.change && it.index == 0 }
        val tx =
            Transaction(
                2L,
                listOf(TxIn(OutPoint(TxHash(ByteArray(32) { 1 }), 0L), 0xffffffffL)),
                listOf(
                    TxOut(Satoshi(40_000L), recv.scriptPubKey),
                    TxOut(Satoshi(10_000L), change.scriptPubKey),
                ),
                0L,
            )
        val txid = tx.txid.toString()
        db.transactions.upsert(StoredTx(txid, 100, 0, "11".repeat(32), Transaction.write(tx), 50_000L))
        val changeKey = outpointKey(txid, 1)
        db.utxoNames.upsert(changeKey, "change from: Rent")
        db.txPaymentLabels.upsert(
            io.bluewallet.blueberry.storage
                .TxPaymentLabelRow(txid, "Rent"),
        )
        val store = createWalletTxsStore()
        setTxNote(db, wallet, store, txid, "Coffee")
        assertEquals("change from: Coffee", db.utxoNames.get(changeKey))
        setTxNote(db, wallet, store, txid, "")
        assertNull(db.utxoNames.get(changeKey))
        db.close()
    }

    @Test
    fun an_hd_receive_coin_keeps_a_change_from_name_when_the_note_changes() {
        val db = createSqliteDatabase(":memory:")
        saveWalletSecret(
            db,
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        )
        val wallet = createWallet(db)
        val recv = wallet.snapshot().addresses.first { !it.change && it.index == 1 }
        val tx =
            Transaction(
                2L,
                listOf(TxIn(OutPoint(TxHash(ByteArray(32) { 1 }), 0L), 0xffffffffL)),
                listOf(TxOut(Satoshi(40_000L), recv.scriptPubKey)),
                0L,
            )
        val txid = tx.txid.toString()
        db.transactions.upsert(StoredTx(txid, 100, 0, "11".repeat(32), Transaction.write(tx), 40_000L))
        val recvKey = outpointKey(txid, 0)
        db.utxoNames.upsert(recvKey, "change from: Rent")
        db.txPaymentLabels.upsert(
            io.bluewallet.blueberry.storage
                .TxPaymentLabelRow(txid, "Rent"),
        )
        val store = createWalletTxsStore()
        setTxNote(db, wallet, store, txid, "Coffee")
        assertEquals("change from: Rent", db.utxoNames.get(recvKey))
        db.close()
    }

    @Test
    fun a_wif_change_name_still_follows_the_note() {
        val db = createSqliteDatabase(":memory:")
        saveWalletSecret(db, "L4ccWrPMmFDZw4kzAKFqJNxgHANjdy6b7YKNXMwB4xac4FLF3Tov")
        val wallet = createWallet(db)
        assertEquals(false, wallet.snapshot().addresses.any { it.change })
        val recv = wallet.snapshot().addresses.first { it.scriptType == AddressScriptType.P2PKH }
        val tx =
            Transaction(
                2L,
                listOf(TxIn(OutPoint(TxHash(ByteArray(32) { 1 }), 0L), 0xffffffffL)),
                listOf(TxOut(Satoshi(40_000L), recv.scriptPubKey)),
                0L,
            )
        val txid = tx.txid.toString()
        db.transactions.upsert(StoredTx(txid, 100, 0, "11".repeat(32), Transaction.write(tx), 40_000L))
        val key = outpointKey(txid, 0)
        db.utxoNames.upsert(key, "change from: Rent")
        db.txPaymentLabels.upsert(
            io.bluewallet.blueberry.storage
                .TxPaymentLabelRow(txid, "Rent"),
        )
        val store = createWalletTxsStore()
        setTxNote(db, wallet, store, txid, "Coffee")
        assertEquals("change from: Coffee", db.utxoNames.get(key))
        setTxNote(db, wallet, store, txid, "")
        assertNull(db.utxoNames.get(key))
        db.close()
    }
}
