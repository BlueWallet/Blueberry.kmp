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
    fun non_empty_picker_commit_asks_send_to_advance_once() {
        val committed = SendDraft.Empty.pickerCommitted(setOf("a"))
        assertTrue(committed.advanceToDetails)
        assertFalse(committed.detailsAdvanceConsumed().advanceToDetails)
        assertFalse(SendDraft(selectedKeys = abc).pickerCommitted(emptySet()).advanceToDetails)
    }

    @Test
    fun confirmed_pick_moves_the_coin_step_to_details_even_after_recreation() {
        assertEquals(SendStep.Details, sendStepAfterPick(SendStep.Utxos, advanceToDetails = true, setOf("a")))
        assertEquals(SendStep.Utxos, sendStepAfterPick(SendStep.Utxos, advanceToDetails = false, setOf("a")))
        assertEquals(SendStep.Utxos, sendStepAfterPick(SendStep.Utxos, advanceToDetails = true, emptySet()))
        assertEquals(SendStep.Preview, sendStepAfterPick(SendStep.Preview, advanceToDetails = true, setOf("a")))
    }

    @Test
    fun coin_step_continue_stays_live_while_a_vanished_coin_warning_is_shown() {
        assertTrue(utxosContinueEnabled(emptySet(), selectionNeedsAck = true))
        assertTrue(utxosContinueEnabled(setOf("a"), selectionNeedsAck = false))
        assertFalse(utxosContinueEnabled(emptySet(), selectionNeedsAck = false))
    }

    @Test
    fun empty_selection_sends_details_back_to_choosing_coins() {
        assertEquals(SendStep.Utxos, sendStepForSelection(SendStep.Details, emptySet()))
    }

    @Test
    fun details_with_coins_stays_put_and_other_steps_are_untouched() {
        assertEquals(SendStep.Details, sendStepForSelection(SendStep.Details, setOf("a")))
        assertEquals(SendStep.Utxos, sendStepForSelection(SendStep.Utxos, emptySet()))
        assertEquals(SendStep.Preview, sendStepForSelection(SendStep.Preview, emptySet()))
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
