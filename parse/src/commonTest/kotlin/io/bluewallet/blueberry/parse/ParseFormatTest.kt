package io.bluewallet.blueberry.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParseFormatTest {
    @Test
    fun btc_and_net_delta_labels() {
        assertEquals("0.00001000 BTC", formatBtc(1000))
        assertEquals("+0.00000100 BTC", formatNetDelta(100))
        assertEquals("-0.00000050 BTC", formatNetDelta(-50))
        assertEquals("${"a".repeat(8)}…${"a".repeat(8)}", shortTxid("a".repeat(64)))
    }

    @Test
    fun splitBtc_peels_trailing_zeros_and_keeps_one_fractional_digit() {
        val peeled = splitBtc(1000)
        assertEquals("00001", peeled.fracSignificant)
        assertEquals("000", peeled.fracTrailing)

        val whole = splitBtc(100_000_000)
        assertEquals("1", whole.whole)
        assertEquals("0", whole.fracSignificant)
        assertEquals("0000000", whole.fracTrailing)

        val zero = splitBtc(0)
        assertEquals("0", zero.fracSignificant)
        assertEquals("0000000", zero.fracTrailing)

        assertEquals("", splitBtc(12_345_678).fracTrailing)
        assertEquals("+", splitBtc(100, plus = true).sign)
        assertEquals("", splitBtc(0, plus = true).sign)
        assertEquals("-", splitBtc(-50, plus = true).sign)
    }

    @Test
    fun parseBtcToSats_accepts_decimals_and_rejects_junk() {
        assertEquals(1000, parseBtcToSats("0.00001000"))
        assertEquals(100_000_000, parseBtcToSats("1"))
        assertEquals(50_000_000, parseBtcToSats("0.5 BTC"))
        assertNull(parseBtcToSats(""))
        assertNull(parseBtcToSats("abc"))
        assertNull(parseBtcToSats("0.000000001"))
        assertNull(parseBtcToSats("-1"))
    }

    @Test
    fun isSendMaxAmount_accepts_trimmed_case_insensitive_max_only() {
        assertTrue(isSendMaxAmount("MAX"))
        assertTrue(isSendMaxAmount(" Max "))
        assertFalse(isSendMaxAmount("maximum"))
        assertFalse(isSendMaxAmount(""))
    }

    @Test
    fun utxoValueBar_uses_full_partial_empty_cells_at_fixed_width() {
        assertEquals("█".repeat(30), utxoValueBar(100, 100))
        assertEquals("█".repeat(15) + "░".repeat(15), utxoValueBar(50, 100))
        assertEquals("█".repeat(10) + "░".repeat(20), utxoValueBar(1, 3))
        assertEquals("", utxoValueBar(0, 100))
        assertEquals("", utxoValueBar(1, 0))
        assertEquals("▏" + "░".repeat(29), utxoValueBar(1, 1_000_000))
    }
}
