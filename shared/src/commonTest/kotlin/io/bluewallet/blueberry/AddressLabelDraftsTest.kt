package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals

class AddressLabelDraftsTest {
    @Test
    fun flush_saves_each_address_and_does_not_cross_them() {
        val saved = mutableListOf<Pair<String, String>>()
        val drafts = AddressLabelDrafts { address, text -> saved += address to text }
        drafts.actions("bc1qa").onDraft("one")
        drafts.actions("bc1qb").onDraft("two")
        drafts.flush()
        assertEquals(listOf("bc1qa" to "one", "bc1qb" to "two"), saved)
        drafts.flush()
        assertEquals(2, saved.size)
    }

    @Test
    fun save_or_clear_drops_the_draft_so_flush_does_not_write_it_again() {
        val saved = mutableListOf<Pair<String, String>>()
        val drafts = AddressLabelDrafts { address, text -> saved += address to text }
        drafts.actions("bc1qa").onDraft("one")
        drafts.actions("bc1qa").onSave("one")
        drafts.actions("bc1qb").onDraft("two")
        drafts.actions("bc1qb").onDraft(null)
        drafts.flush()
        assertEquals(listOf("bc1qa" to "one"), saved)
    }
}
