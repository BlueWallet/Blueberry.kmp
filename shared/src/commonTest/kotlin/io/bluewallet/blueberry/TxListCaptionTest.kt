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

    @Test
    fun utxo_label_fills_in_when_note_is_blank() {
        assertEquals("donation", txListSecondary("abcd…efgh", null, "donation"))
        assertEquals("donation", txListSecondary("abcd…efgh", "   ", "  donation  "))
        assertEquals("rent", txListSecondary("abcd…efgh", "rent", "donation"))
        assertFalse(txListSecondaryMuted(null, "donation"))
        assertTrue(txListSecondaryMuted(null, "   "))
    }

    @Test
    fun address_label_fills_in_after_note_and_utxo_label() {
        assertEquals("savings", txListSecondary("abcd…efgh", null, null, "  savings  "))
        assertEquals("donation", txListSecondary("abcd…efgh", null, "donation", "savings"))
        assertEquals("rent", txListSecondary("abcd…efgh", "rent", "donation", "savings"))
        assertEquals("abcd…efgh", txListSecondary("abcd…efgh", null, null, "   "))
        assertFalse(txListSecondaryMuted(null, null, "savings"))
        assertTrue(txListSecondaryMuted(null, null, "   "))
    }

    @Test
    fun absolute_block_time_drops_the_hour() {
        assertEquals("2025-12-09", txListDateLabel("2025-12-09 18:20"))
        assertEquals("2025-12-09", txListDateLabel("2025-12-09 18:20".padEnd(16)))
        assertEquals("just now", txListDateLabel("just now"))
        assertEquals("1h ago", txListDateLabel("1h ago".padEnd(16)))
        assertEquals("pending", txListDateLabel("pending"))
        assertEquals("#1", txListDateLabel("#1".padEnd(16)))
    }
}
