package io.bluewallet.blueberry

import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxHash
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import io.bluewallet.blueberry.storage.PrivateSendCoin
import io.bluewallet.blueberry.storage.SendRow
import io.bluewallet.blueberry.storage.StoredTx
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.AddressScriptType
import io.bluewallet.blueberry.wallet.addressFromOutputScript
import io.bluewallet.blueberry.wallet.createWallet
import io.bluewallet.blueberry.wallet.deriveWatchWallet
import io.bluewallet.blueberry.wallet.hexFromBytes
import io.bluewallet.blueberry.wallet.outpointKey
import io.bluewallet.blueberry.wallet.saveWalletSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PaymentLabelResolveTest {
    @Test
    fun snapshot_keeps_stored_note_and_exposes_utxo_label_separately() {
        val db = createSqliteDatabase(":memory:")
        val wif = "L4ccWrPMmFDZw4kzAKFqJNxgHANjdy6b7YKNXMwB4xac4FLF3Tov"
        saveWalletSecret(db, wif)
        val wallet = createWallet(db)
        val recv = deriveWatchWallet(wif).addresses.first { it.scriptType == AddressScriptType.P2PKH }
        val prevHash = ByteArray(32).also { it[0] = 42 }
        val fund =
            Transaction(
                2L,
                listOf(TxIn(OutPoint(TxHash(prevHash), 0L), 0xffffffffL)),
                listOf(TxOut(Satoshi(9_664L), recv.scriptPubKey)),
                0L,
            )
        val txid = fund.txid.toString()
        db.transactions.upsert(
            StoredTx(txid, 800_000, 0, "aa".repeat(32), Transaction.write(fund), 9_664L),
        )
        db.utxoNames.upsert(outpointKey(txid, 0), "donation")

        val unlabeled = snapshotFromDb(db, 1, 1, wallet).txs.single()
        assertNull(unlabeled.paymentLabel)
        assertEquals("donation", unlabeled.utxoLabel)
        assertEquals("donation", txListSecondary(unlabeled.shortTxid, unlabeled.paymentLabel, unlabeled.utxoLabel))

        db.txPaymentLabels.upsert(
            io.bluewallet.blueberry.storage
                .TxPaymentLabelRow(txid, "payroll"),
        )
        val labeled = snapshotFromDb(db, 2, 2, wallet).txs.single()
        assertEquals("payroll", labeled.paymentLabel)
        assertEquals("donation", labeled.utxoLabel)
        assertEquals("payroll", txListSecondary(labeled.shortTxid, labeled.paymentLabel, labeled.utxoLabel))

        db.close()
    }

    @Test
    fun snapshot_exposes_utxo_label_without_wallet() {
        val db = createSqliteDatabase(":memory:")
        val txid = "ab".repeat(32)
        db.transactions.upsert(
            StoredTx(txid, 1, 0, "11".repeat(32), byteArrayOf(0x00), 1),
        )
        db.utxoNames.upsert(outpointKey(txid, 0), "cold storage")
        val snap = snapshotFromDb(db, 1, 1, wallet = null)
        assertNull(snap.txs.single().paymentLabel)
        assertEquals("cold storage", snap.txs.single().utxoLabel)
        db.close()
    }

    @Test
    fun snapshot_exposes_address_label_without_wallet_and_drops_it_when_cleared() {
        val db = createSqliteDatabase(":memory:")
        val first = txPaying(1, LABELLED_SCRIPT)
        val second = txPaying(2, LABELLED_SCRIPT)
        db.transactions.upsert(StoredTx(first.txid.toString(), 1, 0, "11".repeat(32), Transaction.write(first), 1_000L))
        db.transactions.upsert(StoredTx(second.txid.toString(), 2, 0, "22".repeat(32), Transaction.write(second), 1_000L))
        db.addressLabels.upsert(addressFromOutputScript(LABELLED_SCRIPT)!!, "savings")

        assertEquals(listOf("savings", "savings"), snapshotFromDb(db, 1, 1, wallet = null).txs.map { it.addressLabel })

        db.addressLabels.delete(addressFromOutputScript(LABELLED_SCRIPT)!!)
        assertEquals(listOf(null, null), snapshotFromDb(db, 2, 2, wallet = null).txs.map { it.addressLabel })
        db.close()
    }

    @Test
    fun pending_send_exposes_address_label_from_tx_hex() {
        val db = createSqliteDatabase(":memory:")
        val send = txPaying(3, LABELLED_SCRIPT)
        db.sends.upsert(
            SendRow(
                txid = send.txid.toString(),
                txHex = hexFromBytes(Transaction.write(send)),
                destination = addressFromOutputScript(LABELLED_SCRIPT)!!,
                coins = listOf(PrivateSendCoin("bb".repeat(32), 0, 2_000L)),
            ),
        )
        db.addressLabels.upsert(addressFromOutputScript(LABELLED_SCRIPT)!!, "savings")

        assertEquals("savings", snapshotFromDb(db, 1, 1, wallet = null).txs.single().addressLabel)
        db.close()
    }
}

private val LABELLED_SCRIPT = byteArrayOf(0x00, 0x14) + ByteArray(20) { 5 }

private fun txPaying(
    seed: Byte,
    script: ByteArray,
): Transaction =
    Transaction(
        2L,
        listOf(TxIn(OutPoint(TxHash(ByteArray(32) { seed }), 0L), 0xffffffffL)),
        listOf(TxOut(Satoshi(1_000L), script)),
        0L,
    )
