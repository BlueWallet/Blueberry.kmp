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
        assertEquals(0xFFBE50DAL, UtxoCirclePaletteArgb[0])
        assertEquals(0xFFB74EE0L, UtxoCirclePaletteArgb[10])
        assertEquals(3, utxoCirclePaletteIndex("abc:0"))
        assertEquals(0xFFDDCD2BL, utxoCircleArgb("abc:0"))
        assertEquals(7, utxoCirclePaletteIndex(""))
        assertEquals(0xFFD95C53L, utxoCircleArgb(""))
        assertEquals(utxoCircleArgb("abc:0"), utxoCircleArgb("abc:0"))
    }

    @Test
    fun different_outpoints_usually_get_different_palette_slots() {
        assertEquals(false, utxoCircleRgbHex("txid:0") == utxoCircleRgbHex("txid:1"))
        assertTrue(utxoCirclePaletteIndex("txid:0") != utxoCirclePaletteIndex("txid:1"))
    }
}
