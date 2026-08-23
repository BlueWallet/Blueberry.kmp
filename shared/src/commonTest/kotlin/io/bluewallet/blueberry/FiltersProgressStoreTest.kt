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

        store.applyEvent(at = 2000, downloaded = 200, total = 1000)
        assertEquals(8000, store.get().etaMs)

        store.applyEvent(at = 3000, downloaded = 1000, total = 1000)
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

        store.applyEvent(at = 1_001_000, downloaded = 1100, total = 5000)
        assertEquals(39_000, store.get().etaMs)
    }

    @Test
    fun empty_queue_is_complete() {
        val store = createFiltersProgressStore()
        store.applyEvent(at = 1, downloaded = 0, total = 0)
        assertEquals(0, store.get().downloaded)
        assertEquals(0, store.get().total)
        assertEquals(100, store.get().percent)
    }
}
