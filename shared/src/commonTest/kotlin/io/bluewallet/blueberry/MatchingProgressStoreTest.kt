package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class MatchingProgressStoreTest {
    @Test
    fun applyEvent_updates_get_before_any_subscribe() {
        val store = createMatchingProgressStore()
        store.applyEvent(at = 1000, scanned = 340250, total = 412390)
        assertEquals(340250, store.get().scanned)
        assertEquals(412390, store.get().total)
        assertEquals(82, store.get().percent)
    }

    @Test
    fun subscribe_after_applyEvent_still_sees_seeded_values() {
        val store = createMatchingProgressStore()
        store.applyEvent(at = 1000, scanned = 10, total = 100)
        val before = store.get()
        var seen = 0
        val unsub = store.subscribe { seen++ }
        assertSame(before, store.get())
        assertEquals(10, store.get().scanned)
        assertEquals(100, store.get().total)
        assertEquals(0, seen)
        unsub()
    }

    @Test
    fun get_keeps_referential_equality_when_values_unchanged() {
        val store = createMatchingProgressStore()
        store.applyEvent(at = 1000, scanned = 10, total = 100)
        val a = store.get()
        store.applyEvent(at = 1000, scanned = 10, total = 100)
        assertSame(a, store.get())
    }

    @Test
    fun eta_ignores_seed_to_first_progress_dead_time() {
        val store = createMatchingProgressStore()
        store.applyEvent(at = 1000, scanned = 1000, total = 5000)
        assertNull(store.get().etaMs)
        store.applyEvent(at = 121_000, scanned = 1100, total = 5000)
        assertNull(store.get().etaMs)
        store.applyEvent(at = 122_000, scanned = 1600, total = 5000)
        assertNull(store.get().etaMs)
        store.applyEvent(at = 123_000, scanned = 2100, total = 5000)
        assertEquals(5800, store.get().etaMs)
    }

    @Test
    fun eta_ignores_completion_idle_dead_time_when_matching_resumes() {
        val store = createMatchingProgressStore()
        store.applyEvent(at = 1000, scanned = 500, total = 1000)
        store.applyEvent(at = 2000, scanned = 1000, total = 1000)
        assertEquals(0, store.get().etaMs)

        store.applyEvent(at = 1_000_000, scanned = 1000, total = 5000)
        assertEquals(20, store.get().percent)
        assertNull(store.get().etaMs)

        store.applyEvent(at = 1_001_000, scanned = 1100, total = 5000)
        assertNull(store.get().etaMs)

        store.applyEvent(at = 1_002_000, scanned = 1200, total = 5000)
        assertNull(store.get().etaMs)

        store.applyEvent(at = 1_003_000, scanned = 1300, total = 5000)
        assertEquals(37_000, store.get().etaMs)
    }

    @Test
    fun empty_queue_is_complete() {
        val store = createMatchingProgressStore()
        assertEquals(100, store.get().percent)
        store.applyEvent(at = 1, scanned = 0, total = 0)
        assertEquals(0, store.get().scanned)
        assertEquals(0, store.get().total)
        assertEquals(100, store.get().percent)
    }

    @Test
    fun eta_eases_after_the_first_estimate() {
        val store = createMatchingProgressStore()
        store.applyEvent(at = 1_000, scanned = 1_000, total = 5_000)
        store.applyEvent(at = 121_000, scanned = 1_100, total = 5_000)
        store.applyEvent(at = 123_000, scanned = 2_100, total = 5_000)
        assertEquals(5_800L, store.get().etaMs)

        // The raw estimate rises to 6_000 here.
        store.applyEvent(at = 124_000, scanned = 2_400, total = 5_000)
        assertEquals(4_941L, store.get().etaMs)
    }

    @Test
    fun a_rewind_drops_the_eta_until_the_new_run_has_one() {
        val store = createMatchingProgressStore()
        store.applyEvent(at = 1_000, scanned = 0, total = 5_000)
        store.applyEvent(at = 2_000, scanned = 100, total = 5_000)
        store.applyEvent(at = 4_000, scanned = 300, total = 5_000)
        assertEquals(47_000L, store.get().etaMs)

        store.applyEvent(at = 5_000, scanned = 50, total = 5_000)
        assertNull(store.get().etaMs)

        store.applyEvent(at = 6_000, scanned = 150, total = 5_000)
        assertNull(store.get().etaMs)

        store.applyEvent(at = 8_000, scanned = 350, total = 5_000)
        assertEquals(46_500L, store.get().etaMs)
    }

    @Test
    fun eta_never_shows_done_while_work_is_left() {
        val store = createMatchingProgressStore()
        store.applyEvent(at = 0, scanned = 0, total = 5_000_000)
        store.applyEvent(at = 1, scanned = 1, total = 5_000_000)
        store.applyEvent(at = 2_001, scanned = 4_999_999, total = 5_000_000)
        assertEquals(1L, store.get().etaMs)
    }
}
