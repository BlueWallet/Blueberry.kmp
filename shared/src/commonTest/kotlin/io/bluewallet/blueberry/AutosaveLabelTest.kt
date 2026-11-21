package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutosaveLabelTest {
    @Test
    fun a_renamed_note_is_saved_after_a_pause() {
        assertEquals("Coffee", autosaveLabelDraft("Coffee", "Rent"))
    }

    @Test
    fun a_blank_draft_is_not_saved_while_editing() {
        assertNull(autosaveLabelDraft("", "Rent"))
        assertNull(autosaveLabelDraft("   ", "Rent"))
        assertNull(autosaveLabelDraft("Rent", "Rent"))
    }
}
