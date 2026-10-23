package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SendDraftTest {
    private val abc = setOf("a", "b", "c")

    @Test
    fun send_side_selection_changes_replace_the_draft_set() {
        val draft = SendDraft(selectedKeys = abc, feeRateSatPerVb = 4.0).withSelection(setOf("a"))
        assertEquals(setOf("a"), draft.selectedKeys)
        assertEquals(4.0, draft.feeRateSatPerVb)
    }

    @Test
    fun freeze_drops_only_that_outpoint() {
        val draft = SendDraft(selectedKeys = abc).frozen("b")
        assertEquals(setOf("a", "c"), draft.selectedKeys)
    }

    @Test
    fun standalone_send_starts_a_fresh_draft() {
        val draft = SendDraft.fromStandalone(setOf("a"))
        assertEquals(setOf("a"), draft.selectedKeys)
        assertNull(draft.feeRateSatPerVb)
    }

    @Test
    fun preselected_coins_that_cover_skip_the_coin_step() {
        assertEquals(SendStep.Review, stepAfterAmount(setOf("a"), covered = true))
    }

    @Test
    fun preselected_coins_that_fall_short_stop_on_the_coin_step() {
        assertEquals(SendStep.Coins, stepAfterAmount(setOf("a"), covered = false))
    }

    @Test
    fun no_preselected_coins_go_to_the_coin_step() {
        assertEquals(SendStep.Coins, stepAfterAmount(emptySet(), covered = false))
    }

    @Test
    fun a_narrowed_filter_keeps_hidden_coins_in_the_picker_set() {
        val next = pickerSelection(abc, liveKeys = abc, frozenKeys = emptySet())
        assertEquals(abc, next)
    }

    @Test
    fun spent_coins_leave_the_picker_set() {
        val next = pickerSelection(abc, liveKeys = setOf("a", "b"), frozenKeys = emptySet())
        assertEquals(setOf("a", "b"), next)
    }

    @Test
    fun frozen_coins_always_leave_the_picker_set() {
        val next = pickerSelection(abc, liveKeys = abc, frozenKeys = setOf("c"))
        assertEquals(setOf("a", "b"), next)
    }

    @Test
    fun sending_coins_always_leave_the_picker_set() {
        val next = pickerSelection(abc, liveKeys = abc, frozenKeys = setOf("b"))
        assertEquals(setOf("a", "c"), next)
    }
}
