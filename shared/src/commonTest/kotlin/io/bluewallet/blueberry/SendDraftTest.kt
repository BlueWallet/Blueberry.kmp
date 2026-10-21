package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendDraftTest {
    private val abc = setOf("a", "b", "c")

    @Test
    fun picker_commit_of_a_partial_set_replaces_the_previous_inputs() {
        val draft = SendDraft(selectedKeys = abc).pickerCommitted(setOf("a"))
        assertEquals(setOf("a"), draft.selectedKeys)
    }

    @Test
    fun picker_commit_of_an_empty_set_clears_the_previous_inputs() {
        val draft = SendDraft(selectedKeys = abc, feeRateSatPerVb = 4.0).pickerCommitted(emptySet())
        assertTrue(draft.selectedKeys.isEmpty())
        assertEquals(4.0, draft.feeRateSatPerVb)
    }

    @Test
    fun picker_commit_answers_the_vanished_coin_warning() {
        val draft =
            SendDraft(selectedKeys = setOf("a"), selectionNeedsAck = true)
                .pickerCommitted(setOf("a"))
        assertFalse(draft.selectionNeedsAck)
    }

    @Test
    fun send_side_selection_changes_keep_the_warning() {
        val draft =
            SendDraft(selectedKeys = abc, selectionNeedsAck = true)
                .withSelection(setOf("a", "b"))
        assertTrue(draft.selectionNeedsAck)
    }

    @Test
    fun freeze_drops_only_that_outpoint() {
        val draft = SendDraft(selectedKeys = abc, selectionNeedsAck = true).frozen("b")
        assertEquals(setOf("a", "c"), draft.selectedKeys)
        assertTrue(draft.selectionNeedsAck)
    }

    @Test
    fun standalone_send_starts_a_fresh_draft() {
        val draft = SendDraft.fromStandalone(setOf("a"))
        assertEquals(setOf("a"), draft.selectedKeys)
        assertNull(draft.feeRateSatPerVb)
        assertFalse(draft.selectionNeedsAck)
    }

    @Test
    fun narrowed_filter_drops_hidden_coins_from_the_picker_set() {
        val next = pickerSelection(abc, visibleKeys = setOf("a", "b"), frozenKeys = emptySet(), showsAll = false)
        assertEquals(setOf("a", "b"), next)
    }

    @Test
    fun all_filter_keeps_coins_outside_the_visible_set() {
        val next = pickerSelection(abc, visibleKeys = setOf("a"), frozenKeys = emptySet(), showsAll = true)
        assertEquals(abc, next)
    }

    @Test
    fun frozen_coins_always_leave_the_picker_set() {
        val next = pickerSelection(abc, visibleKeys = abc, frozenKeys = setOf("c"), showsAll = true)
        assertEquals(setOf("a", "b"), next)
    }
}
