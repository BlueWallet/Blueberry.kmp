package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TxListCaptionTest {
    @Test
    fun blank_note_keeps_short_hash_and_muted() {
        assertEquals("abcd…efgh", txListSecondary("abcd…efgh", null))
        assertEquals("abcd…efgh", txListSecondary("abcd…efgh", ""))
        assertEquals("abcd…efgh", txListSecondary("abcd…efgh", "   "))
        assertTrue(txListSecondaryMuted(null))
        assertTrue(txListSecondaryMuted(""))
        assertTrue(txListSecondaryMuted("   "))
    }

    @Test
    fun note_replaces_short_hash_and_is_not_muted() {
        assertEquals("coffee", txListSecondary("abcd…efgh", "coffee"))
        assertEquals("coffee", txListSecondary("abcd…efgh", "  coffee  "))
        assertFalse(txListSecondaryMuted("coffee"))
        assertFalse(txListSecondaryMuted("  coffee  "))
    }
}
