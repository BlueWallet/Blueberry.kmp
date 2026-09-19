package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Bip21Test {
    @Test
    fun parseBip21_reads_address_amount_and_label() {
        val uri =
            "bitcoin:bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu" +
                "?amount=0.0130267&label=Invoice%20%231&message=ignored"
        val got = parseBip21(uri)!!
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", got.address)
        assertEquals("0.0130267", got.amountBtc)
        assertEquals("Invoice #1", got.label)
    }

    @Test
    fun parseBip21_uses_message_when_label_is_absent() {
        val uri =
            "BITCOIN:13HaCAB4jf7FYSZexJxoczyDDnutzZigjS?message=groceries"
        val got = parseBip21(uri)!!
        assertEquals("13HaCAB4jf7FYSZexJxoczyDDnutzZigjS", got.address)
        assertNull(got.amountBtc)
        assertEquals("groceries", got.label)
    }

    @Test
    fun parseBip21_rejects_plain_address() {
        assertNull(parseBip21("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"))
    }

    @Test
    fun applyBip21Payload_fills_empty_fields_and_keeps_missing_ones() {
        val filled =
            applyBip21Payload(
                "bitcoin:bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu?amount=0.01&label=rent",
                SendFormFields(address = "", amount = "", label = ""),
            )
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", filled.address)
        assertEquals("0.01", filled.amount)
        assertEquals("rent", filled.label)
        val keepAmount =
            applyBip21Payload(
                "bitcoin:bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu",
                SendFormFields(address = "old", amount = "1", label = "keep"),
            )
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", keepAmount.address)
        assertEquals("1", keepAmount.amount)
        assertEquals("keep", keepAmount.label)
        val plain =
            applyBip21Payload(
                "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu",
                SendFormFields(address = "", amount = "2", label = "x"),
            )
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", plain.address)
        assertEquals("2", plain.amount)
        assertEquals("x", plain.label)
    }
}
