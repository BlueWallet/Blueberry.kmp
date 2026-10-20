package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UtxoCircleColorTest {
    @Test
    fun rgb_hex_is_first_six_chars_of_sha256_of_outpoint() {
        assertEquals("5f36ef", utxoCircleRgbHex("abc:0"))
        assertEquals("e3b0c4", utxoCircleRgbHex(""))
        assertEquals("b275cc", utxoCircleRgbHex("aa".repeat(32) + ":1"))
    }

    @Test
    fun argb_picks_spec_palette_by_hash_mod_11() {
        assertEquals(11, UtxoCirclePaletteArgb.size)
        assertEquals(0xFF7E4F8AL, UtxoCirclePaletteArgb[0])
        assertEquals(0xFF7C4E8EL, UtxoCirclePaletteArgb[10])
        assertEquals(3, utxoCirclePaletteIndex("abc:0"))
        assertEquals(0xFF77723FL, utxoCircleArgb("abc:0"))
        assertEquals(7, utxoCirclePaletteIndex(""))
        assertEquals(0xFF8A5450L, utxoCircleArgb(""))
        assertEquals(utxoCircleArgb("abc:0"), utxoCircleArgb("abc:0"))
    }

    @Test
    fun different_outpoints_usually_get_different_palette_slots() {
        assertEquals(false, utxoCircleRgbHex("txid:0") == utxoCircleRgbHex("txid:1"))
        assertTrue(utxoCirclePaletteIndex("txid:0") != utxoCirclePaletteIndex("txid:1"))
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
