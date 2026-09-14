package io.bluewallet.blueberry

import io.bluewallet.blueberry.wallet.SendAmount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SendPreviewFieldsTest {
    @Test
    fun max_send_labels_amount_fee_rate_and_size() {
        val fields =
            sendPreviewFields(
                details =
                    SendDetails(
                        toAddress = "13HaCAB4jf7FYSZexJxoczyDDnutzZigjS",
                        amountSats = SendAmount.Max,
                        paymentLabel = "to 13hacab testdest",
                    ),
                inputSum = 198_761L + 260L,
                totals = SendPreviewTotals(feeSats = 260L, sizeBytes = 191, vsize = 260, changeSats = 0L),
            )
        assertEquals(
            listOf("To", "Send", "Fee", "Transaction size", "Label"),
            fields.map { it.label },
        )
        assertEquals("13HaCAB4jf7FYSZexJxoczyDDnutzZigjS", fields[0].value)
        assertEquals("0.00198761 BTC", fields[1].value)
        assertEquals("MAX", fields[1].caption)
        assertEquals("0.00000260 BTC", fields[2].value)
        assertEquals("1.0 sat/vB", fields[2].caption)
        assertEquals("191 bytes · 260 vbytes", fields[3].value)
        assertEquals("to 13hacab testdest", fields[4].value)
    }

    @Test
    fun exact_send_includes_change_and_no_max_caption() {
        val fields =
            sendPreviewFields(
                details =
                    SendDetails(
                        toAddress = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
                        amountSats = SendAmount.Exact(50_000L),
                        paymentLabel = "rent",
                    ),
                inputSum = 100_000L,
                totals = SendPreviewTotals(feeSats = 141L, sizeBytes = 200, vsize = 141, changeSats = 49_859L),
            )
        assertEquals(
            listOf("To", "Send", "Fee", "Transaction size", "Change", "Label"),
            fields.map { it.label },
        )
        assertEquals("0.00050000 BTC", fields[1].value)
        assertNull(fields[1].caption)
        assertEquals("1.0 sat/vB", fields[2].caption)
        assertEquals("200 bytes · 141 vbytes", fields[3].value)
        assertEquals("0.00049859 BTC", fields[4].value)
    }

    @Test
    fun formatTxSize_omits_vbytes_when_equal() {
        assertEquals("191 bytes", formatTxSize(191, 191))
        assertEquals("220 bytes · 191 vbytes", formatTxSize(220, 191))
    }
}
