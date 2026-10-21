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

    /**
     * Runs Send's one step effect the way Compose does: it reruns while the step or the pending
     * advance changes, so the result is what the subject sees once the screen settles.
     */
    private fun settle(
        restoredStep: SendStep,
        draft: SendDraft,
    ): Pair<SendStep, SendDraft> {
        var step = restoredStep
        var d = draft
        repeat(4) {
            val resolved = resolveSendStep(step, d.selectedKeys, d.advanceToDetails)
            step = resolved.step
            if (resolved.consumeAdvance) d = d.detailsAdvanceConsumed()
        }
        return step to d
    }

    @Test
    fun recreated_send_on_the_coin_step_moves_to_details_once_after_use() {
        val committed = SendDraft.Empty.pickerCommitted(setOf("a"))
        val (step, after) = settle(SendStep.Utxos, committed)
        assertEquals(SendStep.Details, step)
        assertFalse(after.advanceToDetails)
        // A later Back to the coin step is not undone by a stale advance.
        assertEquals(SendStep.Utxos, settle(SendStep.Utxos, after).first)
    }

    @Test
    fun empty_commit_from_details_returns_to_the_coin_step() {
        val committed = SendDraft(selectedKeys = abc).pickerCommitted(emptySet())
        assertEquals(SendStep.Utxos, settle(SendStep.Details, committed).first)
    }

    @Test
    fun later_steps_are_left_alone_and_the_advance_is_still_consumed() {
        val committed = SendDraft.Empty.pickerCommitted(setOf("a"))
        val (step, after) = settle(SendStep.Preview, committed)
        assertEquals(SendStep.Preview, step)
        assertFalse(after.advanceToDetails)
        assertEquals(SendStep.Details, settle(SendStep.Details, SendDraft(selectedKeys = setOf("a"))).first)
    }

    @Test
    fun coins_spent_while_the_picker_was_open_keep_the_warning_on_the_coin_step() {
        val committed = SendDraft(selectedKeys = abc).pickerCommitted(setOf("a"), coinsVanished = true)
        assertTrue(committed.selectionNeedsAck)
        assertFalse(committed.advanceToDetails)
        assertEquals(SendStep.Utxos, settle(SendStep.Utxos, committed).first)
    }

    @Test
    fun coin_step_continue_stays_live_while_a_vanished_coin_warning_is_shown() {
        assertTrue(utxosContinueEnabled(emptySet(), selectionNeedsAck = true))
        assertTrue(utxosContinueEnabled(setOf("a"), selectionNeedsAck = false))
        assertFalse(utxosContinueEnabled(emptySet(), selectionNeedsAck = false))
    }

    @Test
    fun narrowed_filter_drops_hidden_coins_from_the_picker_set() {
        val next =
            pickerSelection(abc, liveKeys = abc, visibleKeys = setOf("a", "b"), frozenKeys = emptySet(), showsAll = false)
        assertEquals(setOf("a", "b"), next)
    }

    @Test
    fun all_filter_keeps_live_coins_outside_the_visible_set() {
        val next = pickerSelection(abc, liveKeys = abc, visibleKeys = setOf("a"), frozenKeys = emptySet(), showsAll = true)
        assertEquals(abc, next)
    }

    @Test
    fun spent_coins_leave_the_picker_set_even_under_all() {
        val next =
            pickerSelection(abc, liveKeys = setOf("a", "b"), visibleKeys = setOf("a", "b"), frozenKeys = emptySet(), showsAll = true)
        assertEquals(setOf("a", "b"), next)
    }

    @Test
    fun frozen_coins_always_leave_the_picker_set() {
        val next = pickerSelection(abc, liveKeys = abc, visibleKeys = abc, frozenKeys = setOf("c"), showsAll = true)
        assertEquals(setOf("a", "b"), next)
    }
}
