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

        for (i in 2..8) store.applyEvent(at = i * 1000L, downloaded = i * 100, matched = 1000)
        assertNull(store.get().etaMs)

        store.applyEvent(at = 9000, downloaded = 900, matched = 1000)
        assertEquals(1000, store.get().etaMs)

        store.applyEvent(at = 10_000, downloaded = 1000, matched = 1000)
        assertEquals(100, store.get().percent)
        assertEquals(0, store.get().etaMs)
    }

    @Test
    fun eta_ignores_the_wait_before_pending_downloads_start() {
        val store = createBlocksMatchedStore()
        store.applyEvent(at = 0, downloaded = 40, matched = 100)
        for (i in 0..6) store.applyEvent(at = 30_000L + i * 1000, downloaded = 41 + i, matched = 100)
        assertNull(store.get().etaMs)

        store.applyEvent(at = 37_000, downloaded = 48, matched = 100)
        assertEquals(52_000L, store.get().etaMs)
    }

    @Test
    fun a_stale_lower_reading_keeps_the_speed() {
        val store = createBlocksMatchedStore()
        store.applyEvent(at = 0, downloaded = 40, matched = 100)
        for (i in 0..7) store.applyEvent(at = 30_000L + i * 1000, downloaded = 41 + i, matched = 100)
        assertEquals(52_000L, store.get().etaMs)

        store.applyEvent(at = 37_500, downloaded = 47, matched = 100)
        assertEquals(53_000L, store.get().etaMs)

        store.applyEvent(at = 38_000, downloaded = 49, matched = 100)
        assertEquals(51_000L, store.get().etaMs)
    }

    @Test
    fun new_matched_blocks_after_done_drop_the_eta() {
        val store = createBlocksMatchedStore()
        for (i in 0..7) store.applyEvent(at = i * 1000L, downloaded = 1 + i, matched = 8)
        assertEquals(0L, store.get().etaMs)

        store.applyEvent(at = 60_000, downloaded = 8, matched = 20)
        assertNull(store.get().etaMs)
    }
}
