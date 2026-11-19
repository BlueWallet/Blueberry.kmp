package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FiltersProgressStoreTest {
    @Test
    fun percent_eta_and_ignore_non_advancing_samples() {
        val store = createFiltersProgressStore()
        assertEquals(0, store.get().downloaded)
        assertEquals(0, store.get().total)
        assertNull(store.get().at)
        assertNull(store.get().etaMs)
        assertEquals(100, store.get().percent)

        store.applyEvent(at = 1000, downloaded = 100, total = 1000)
        assertEquals(10, store.get().percent)
        assertNull(store.get().etaMs)

        store.applyEvent(at = 1500, downloaded = 100, total = 1000)
        assertNull(store.get().etaMs)
        assertEquals(1500, store.get().at)

        for (i in 2..7) store.applyEvent(at = i * 1000L, downloaded = i * 100, total = 1000)
        assertNull(store.get().etaMs)

        store.applyEvent(at = 8000, downloaded = 800, total = 1000)
        assertEquals(2000, store.get().etaMs)

        store.applyEvent(at = 9000, downloaded = 1000, total = 1000)
        assertEquals(100, store.get().percent)
        assertEquals(0, store.get().etaMs)
    }

    @Test
    fun eta_ignores_completion_idle_dead_time_when_work_resumes() {
        val store = createFiltersProgressStore()
        store.applyEvent(at = 1000, downloaded = 500, total = 1000)
        store.applyEvent(at = 2000, downloaded = 1000, total = 1000)
        assertEquals(0, store.get().etaMs)

        store.applyEvent(at = 1_000_000, downloaded = 1000, total = 5000)
        assertEquals(20, store.get().percent)
        assertNull(store.get().etaMs)

        for (i in 1..7) store.applyEvent(at = 1_000_000L + i * 1000, downloaded = 1000 + i * 100, total = 5000)
        assertNull(store.get().etaMs)

        store.applyEvent(at = 1_008_000, downloaded = 1800, total = 5000)
        assertEquals(32_000, store.get().etaMs)
    }

    @Test
    fun eta_ignores_the_wait_before_the_first_download() {
        val store = createFiltersProgressStore()
        store.applyEvent(at = 0, downloaded = 0, total = 0)
        store.applyEvent(at = 60_000, downloaded = 0, total = 600_000)
        for (i in 0..6) store.applyEvent(at = 180_000L + i * 250, downloaded = 25 * (i + 1), total = 600_000)
        assertNull(store.get().etaMs)

        store.applyEvent(at = 181_750, downloaded = 200, total = 600_000)
        assertEquals(5_998_000L, store.get().etaMs)
    }

    @Test
    fun empty_queue_is_complete() {
        val store = createFiltersProgressStore()
        store.applyEvent(at = 1, downloaded = 0, total = 0)
        assertEquals(0, store.get().downloaded)
        assertEquals(0, store.get().total)
        assertEquals(100, store.get().percent)
    }

    @Test
    fun eta_eases_a_one_second_speed_swing() {
        val store = createFiltersProgressStore()
        for (i in 0..7) store.applyEvent(at = i * 1000L, downloaded = 1000 * (i + 1), total = 600_000)
        assertEquals(592_000L, store.get().etaMs)

        // This second runs at twice the speed.
        store.applyEvent(at = 8000, downloaded = 10_000, total = 600_000)
        assertEquals(524_878L, store.get().etaMs)
    }

    @Test
    fun a_short_stall_counts_once() {
        val store = createFiltersProgressStore()
        for (i in 0..7) store.applyEvent(at = i * 100L, downloaded = 25 * (i + 1), total = 735_200)
        assertEquals(2_940_000L, store.get().etaMs)

        store.applyEvent(at = 2600, downloaded = 225, total = 735_200)
        assertEquals(3_765_615L, store.get().etaMs)
    }

    @Test
    fun eta_keeps_the_speed_through_pauses() {
        val store = createFiltersProgressStore()
        for (i in 0..7) store.applyEvent(at = i * 250L, downloaded = 25 * (i + 1), total = 600_000)
        assertEquals(5_998_000L, store.get().etaMs)

        store.applyEvent(at = 61_750, downloaded = 225, total = 600_000)
        assertEquals(5_997_750L, store.get().etaMs)

        store.applyEvent(at = 91_750, downloaded = 250, total = 600_000)
        assertEquals(5_997_500L, store.get().etaMs)

        for (i in 1..7) store.applyEvent(at = 91_750L + i * 250, downloaded = 250 + 25 * i, total = 600_000)
        assertEquals(5_995_750L, store.get().etaMs)
    }
}
