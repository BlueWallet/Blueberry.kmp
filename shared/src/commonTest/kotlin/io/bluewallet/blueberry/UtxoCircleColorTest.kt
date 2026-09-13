package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals

class UtxoCircleColorTest {
    @Test
    fun rgb_hex_is_first_six_chars_of_sha256_of_outpoint() {
        assertEquals("5f36ef", utxoCircleRgbHex("abc:0"))
        assertEquals("e3b0c4", utxoCircleRgbHex(""))
        assertEquals("b275cc", utxoCircleRgbHex("aa".repeat(32) + ":1"))
        assertEquals(0xFF5F36EFL, utxoCircleArgb("abc:0"))
        assertEquals(0xFFE3B0C4L, utxoCircleArgb(""))
    }

    @Test
    fun different_outpoints_get_different_colors() {
        assertEquals(false, utxoCircleRgbHex("txid:0") == utxoCircleRgbHex("txid:1"))
    }

    @Test
    fun visible_count_is_total_until_the_row_is_full() {
        assertEquals(0, visibleUtxoCircleCount(total = 0, maxFit = 8))
        assertEquals(0, visibleUtxoCircleCount(total = 6, maxFit = 0))
        assertEquals(6, visibleUtxoCircleCount(total = 6, maxFit = 8))
        assertEquals(8, visibleUtxoCircleCount(total = 40, maxFit = 8))
    }

    @Test
    fun max_fit_counts_overlapping_circles_that_fit_the_width() {
        assertEquals(0, maxUtxoCirclesThatFit(available = 10f, diameter = 22f, step = 12f))
        assertEquals(1, maxUtxoCirclesThatFit(available = 22f, diameter = 22f, step = 12f))
        assertEquals(3, maxUtxoCirclesThatFit(available = 46f, diameter = 22f, step = 12f))
        assertEquals(4, maxUtxoCirclesThatFit(available = 58f, diameter = 22f, step = 12f))
    }
}
