package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeadersProgressStoreTest {
    @Test
    fun percent_eta_and_ignore_non_advancing_samples() {
        val store = createHeadersProgressStore()
        assertEquals(0, store.get().downloaded)
        assertEquals(0, store.get().total)
        assertNull(store.get().at)
        assertNull(store.get().etaMs)
        assertEquals(0, store.get().percent)

        store.applyEvent(at = 1000, downloaded = 100, total = 1000, height = 100)
        assertEquals(10, store.get().percent)
        assertNull(store.get().etaMs)

        store.applyEvent(at = 1500, downloaded = 100, total = 1000, height = 100)
        assertNull(store.get().etaMs)
        assertEquals(1500, store.get().at)

        store.applyEvent(at = 2000, downloaded = 200, total = 1000, height = 200)
        assertEquals(8000, store.get().etaMs)

        store.applyEvent(at = 3000, downloaded = 1000, total = 1000, height = 1000)
        assertEquals(100, store.get().percent)
        assertEquals(0, store.get().etaMs)
    }

    @Test
    fun formatEta_and_progressBar_match_helix3() {
        assertEquals("—", formatEta(null))
        assertEquals("done", formatEta(0))
        assertEquals("done", formatEta(-1))
        assertEquals("2s", formatEta(1500))
        assertEquals("1m 5s", formatEta(65_000))

        assertEquals("[░░░░░░░░░░] 0%", progressBar(0, 10))
        assertEquals("[█████░░░░░] 50%", progressBar(50, 10))
        assertEquals("[██████████] 100%", progressBar(100, 10))
        assertEquals("[░░░░░░░░░░] 0%", progressBar(-10, 10))
        assertEquals("[██████████] 100%", progressBar(200, 10))
        assertEquals(10, progressBar(0).count { it == '░' || it == '█' })
    }

    @Test
    fun progressFillFraction_is_obvious_at_common_percents() {
        assertEquals(0f, progressFillFraction(0))
        assertEquals(0.15f, progressFillFraction(15))
        assertEquals(0.5f, progressFillFraction(50))
        assertEquals(1f, progressFillFraction(100))
        assertEquals(0f, progressFillFraction(-4))
        assertEquals(1f, progressFillFraction(140))
    }

    @Test
    fun progressPercent_treats_empty_total_as_complete() {
        assertEquals(100, progressPercent(0, 0))
        assertEquals(0, progressPercent(0, 10))
        assertEquals(50, progressPercent(5, 10))
        assertEquals(100, progressPercent(10, 10))
        assertEquals(100, progressPercent(12, 10))
    }

    @Test
    fun formatGrouped_inserts_thousands_separators() {
        assertEquals("0", formatGrouped(0))
        assertEquals("963,482", formatGrouped(963_482))
        assertEquals("-12,345", formatGrouped(-12_345))
    }
}
