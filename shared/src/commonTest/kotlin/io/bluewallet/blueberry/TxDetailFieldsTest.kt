package io.bluewallet.blueberry

import fr.acinq.bitcoin.Bech32
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxHash
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import io.bluewallet.blueberry.parse.TxFee
import io.bluewallet.blueberry.storage.StoredTx
import io.bluewallet.blueberry.wallet.outputScriptFromAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class TxDetailFieldsTest {
    private fun row(note: String? = "coffee") =
        WalletTxRow(
            txid = "ab".repeat(32),
            shortTxid = "ab",
            height = 100,
            timeLabel = "1h ago".padEnd(16),
            netDeltaSats = 1,
            netDeltaLabel = "+0.00000001",
            paymentLabel = note,
        )

    @Test
    fun dumps_stored_fields_in_order_and_trims_date() {
        val raw =
            Transaction.write(
                Transaction(
                    2L,
                    listOf(TxIn(OutPoint(TxHash(ByteArray(32)), 0xffffffffL), 0xffffffffL)),
                    listOf(TxOut(Satoshi(1000L), byteArrayOf(0x00))),
                    0L,
                ),
            )
        val stored =
            StoredTx(
                txid = "ab".repeat(32),
                height = 100,
                txIndex = 3,
                blockHashInternalHex = "aabbccdd",
                tx = raw,
                netDeltaSats = 1,
            )
        val fields = txDetailFields(row(), stored)
        val vsize = (Transaction.read(raw).weight() + 3) / 4
        assertEquals(
            listOf(
                TxDetailField("Amount", "+0.00000001"),
                TxDetailField("Note", "coffee"),
                TxDetailField("Date", "1h ago"),
                TxDetailField("Height", "100"),
                TxDetailField("Transaction ID", "ab".repeat(32)),
                TxDetailField("Block hash", "ddccbbaa"),
                TxDetailField("Index", "3"),
                TxDetailField("Size", "${raw.size} B"),
                TxDetailField("Virtual size", "$vsize vB"),
            ),
            fields,
        )
    }

    @Test
    fun omits_stored_only_fields_when_row_has_no_db_tx() {
        val fields = txDetailFields(row(note = "  "), stored = null)
        assertEquals(
            listOf(
                TxDetailField("Amount", "+0.00000001"),
                TxDetailField("Note", ""),
                TxDetailField("Date", "1h ago"),
                TxDetailField("Height", "100"),
                TxDetailField("Transaction ID", "ab".repeat(32)),
            ),
            fields,
        )
    }

    @Test
    fun fee_then_note_sit_under_amount() {
        val stored =
            StoredTx(
                txid = "ab".repeat(32),
                height = 100,
                txIndex = 3,
                blockHashInternalHex = "aabbccdd",
                tx = byteArrayOf(0x01, 0x02, 0x03, 0x04),
                netDeltaSats = 1,
            )
        val fields = txDetailFields(row(), stored, TxFee(100, 110))
        assertEquals(
            listOf("Amount", "Fee", "Fee rate", "Note", "Date"),
            fields.take(5).map { it.label },
        )
        assertEquals(TxDetailField("Fee", "0.00000100 BTC"), fields[1])
        assertEquals(TxDetailField("Fee rate", "0.9 sat/vB"), fields[2])
        assertEquals(TxDetailField("Note", "coffee"), fields[3])
    }

    @Test
    fun destinations_drop_change_and_private_destination_follows() {
        val dest = Bech32.encodeWitnessAddress("bc", 0, ByteArray(20) { 1 })
        val other = Bech32.encodeWitnessAddress("bc", 0, ByteArray(20) { 2 })
        val change = Bech32.encodeWitnessAddress("bc", 0, ByteArray(20) { 3 })
        val tx =
            Transaction(
                2L,
                listOf(TxIn(OutPoint(TxHash(ByteArray(32)), 0L), 0xffffffffL)),
                listOf(
                    TxOut(Satoshi(1L), outputScriptFromAddress(dest)),
                    TxOut(Satoshi(2L), outputScriptFromAddress(change)),
                    TxOut(Satoshi(3L), outputScriptFromAddress(other)),
                ),
                0L,
            )
        val fields =
            txDetailFields(
                row().copy(netDeltaSats = -1, netDeltaLabel = "-0.00000001"),
                stored = null,
                sources =
                    TxDetailSources(
                        txBytes = Transaction.write(tx),
                        changeScripts = listOf(outputScriptFromAddress(change)),
                        privateDestination = "bc1qprivate",
                        privateProvider = privateSendProvider(),
                        privateOrderId = "P-1352e544",
                    ),
            )
        assertEquals(
            listOf(
                TxDetailField("Amount", "-0.00000001"),
                TxDetailField("Destination", "$dest, $other"),
                TxDetailField("Private destination", "bc1qprivate"),
                TxDetailField("Provider", "rocketx.exchange"),
                TxDetailField("Order ID", "P-1352e544"),
            ),
            fields.take(5),
        )
    }

    @Test
    fun receive_shows_from_inputs_instead_of_destination() {
        val first = Bech32.encodeWitnessAddress("bc", 0, ByteArray(20) { 4 })
        val second = Bech32.encodeWitnessAddress("bc", 0, ByteArray(20) { 5 })
        val parent =
            Transaction(
                2L,
                listOf(TxIn(OutPoint(TxHash(ByteArray(32)), 0L), 0xffffffffL)),
                listOf(
                    TxOut(Satoshi(1L), outputScriptFromAddress(first)),
                    TxOut(Satoshi(2L), outputScriptFromAddress(second)),
                ),
                0L,
            )
        val received =
            Transaction(
                2L,
                listOf(
                    TxIn(OutPoint(parent.txid, 1L), 0xffffffffL),
                    TxIn(OutPoint(parent.txid, 0L), 0xffffffffL),
                ),
                listOf(TxOut(Satoshi(3L), outputScriptFromAddress(first))),
                0L,
            )
        val fields =
            txDetailFields(
                row(),
                stored = null,
                sources =
                    TxDetailSources(
                        txBytes = Transaction.write(received),
                        parentTx = { id -> if (id == parent.txid.toString()) Transaction.write(parent) else null },
                    ),
            )
        assertEquals(
            listOf(
                TxDetailField("Amount", "+0.00000001"),
                TxDetailField("From", "$second, $first"),
            ),
            fields.take(2),
        )
    }
}
