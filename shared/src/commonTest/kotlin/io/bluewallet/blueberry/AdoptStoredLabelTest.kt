package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals

class AdoptStoredLabelTest {
    @Test
    fun adopts_stored_when_draft_is_untouched() {
        assertEquals("Cafe tips", adoptStoredLabel("", "", "Cafe tips"))
        assertEquals("Cafe tips", adoptStoredLabel("  ", "", "Cafe tips"))
        assertEquals("new", adoptStoredLabel("old", "old", "new"))
        assertEquals("new", adoptStoredLabel("  old  ", "old", "new"))
    }

    @Test
    fun keeps_in_progress_edit() {
        assertEquals("mine", adoptStoredLabel("mine", "", "Cafe tips"))
        assertEquals("  mine  ", adoptStoredLabel("  mine  ", "", "Cafe tips"))
        assertEquals("mine", adoptStoredLabel("mine", "old", "new"))
    }
}
