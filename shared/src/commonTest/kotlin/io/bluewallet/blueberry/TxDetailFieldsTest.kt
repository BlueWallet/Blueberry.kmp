package io.bluewallet.blueberry

import io.bluewallet.blueberry.parse.TxFee
import io.bluewallet.blueberry.storage.StoredTx
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
        val stored =
            StoredTx(
                txid = "ab".repeat(32),
                height = 100,
                txIndex = 3,
                blockHashInternalHex = "aabbccdd",
                tx = byteArrayOf(0x01, 0x02, 0x03, 0x04),
                netDeltaSats = 1,
            )
        val fields = txDetailFields(row(), stored)
        assertEquals(
            listOf(
                TxDetailField("Amount", "+0.00000001"),
                TxDetailField("Note", "coffee"),
                TxDetailField("Date", "1h ago"),
                TxDetailField("Height", "100"),
                TxDetailField("Transaction ID", "ab".repeat(32)),
                TxDetailField("Block hash", "ddccbbaa"),
                TxDetailField("Index", "3"),
                TxDetailField("Size", "4 B"),
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
}
