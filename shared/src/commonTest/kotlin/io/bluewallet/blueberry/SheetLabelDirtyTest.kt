package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SheetLabelDirtyTest {
    @Test
    fun dirty_when_the_draft_matches_neither_saved_nor_last_saved() {
        assertTrue(sheetLabelDirty("Y", saved = "X", lastSaved = null))
        assertTrue(sheetLabelDirty("  Y  ", saved = "X", lastSaved = null))
    }

    @Test
    fun clean_when_the_draft_already_matches_the_stored_value() {
        assertFalse(sheetLabelDirty("Y", saved = "Y", lastSaved = null))
        assertFalse(sheetLabelDirty("Y", saved = "Y", lastSaved = "Y"))
    }

    @Test
    fun clean_while_last_saved_still_covers_an_outstanding_save() {
        assertFalse(sheetLabelDirty("Y", saved = "X", lastSaved = "Y"))
    }

    @Test
    fun dirty_again_after_saved_changes_and_last_saved_is_cleared() {
        // After saving Y, the store moves to Z and lastSaved is reset so re-typing Y is dirty.
        assertTrue(sheetLabelDirty("Y", saved = "Z", lastSaved = null))
    }
}
