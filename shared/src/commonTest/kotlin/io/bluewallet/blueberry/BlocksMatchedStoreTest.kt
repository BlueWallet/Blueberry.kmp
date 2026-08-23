package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BlocksMatchedStoreTest {
    @Test
    fun empty_queue_is_complete() {
        val store = createBlocksMatchedStore()
        store.applyEvent(at = 1, downloaded = 0, matched = 0)
        assertEquals(0, store.get().downloaded)
        assertEquals(0, store.get().matched)
        assertEquals(100, store.get().percent)
        assertEquals(0, store.get().etaMs)
    }

    @Test
    fun percent_eta_and_ignore_non_advancing_samples() {
        val store = createBlocksMatchedStore()
        assertEquals(0, store.get().downloaded)
        assertEquals(0, store.get().matched)
        assertNull(store.get().at)
        assertNull(store.get().etaMs)
        assertEquals(100, store.get().percent)

        store.applyEvent(at = 1000, downloaded = 100, matched = 1000)
        assertEquals(10, store.get().percent)
        assertNull(store.get().etaMs)

        store.applyEvent(at = 1500, downloaded = 100, matched = 1000)
        assertNull(store.get().etaMs)
        assertEquals(1500, store.get().at)

        store.applyEvent(at = 2000, downloaded = 200, matched = 1000)
        assertEquals(8000, store.get().etaMs)

        store.applyEvent(at = 3000, downloaded = 1000, matched = 1000)
        assertEquals(100, store.get().percent)
        assertEquals(0, store.get().etaMs)
    }
}
