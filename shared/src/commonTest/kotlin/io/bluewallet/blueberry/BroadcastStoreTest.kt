package io.bluewallet.blueberry

import io.bluewallet.blueberry.bus.BroadcastDonePayload
import io.bluewallet.blueberry.bus.BroadcastPhase
import io.bluewallet.blueberry.bus.BroadcastProgressPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BroadcastStoreTest {
    @Test
    fun progress_and_done_update_the_same_job() {
        val store = createBroadcastStore()
        store.start("1", "deadbeef")
        store.applyProgress(
            BroadcastProgressPayload("1", BroadcastPhase.ATTEMPT, attempt = 1, maxAttempts = 20, peer = "1.1.1.1:8333"),
        )
        assertEquals("attempt", store.get().phase)
        assertEquals(1, store.get().attempt)
        store.applyDone(BroadcastDonePayload.Ok("1", "1.1.1.1:8333"))
        assertEquals("success", store.get().phase)
        assertTrue(!broadcastJobInFlight(store.get().phase))
        store.reset()
        assertEquals(null, store.get().id)
    }

    @Test
    fun idle_store_ignores_stale_progress_and_done() {
        val store = createBroadcastStore()
        store.start("old-job", "aa")
        store.reset()
        store.applyProgress(
            BroadcastProgressPayload("old-job", BroadcastPhase.ATTEMPT, attempt = 2, peer = "1.1.1.1:8333"),
        )
        assertEquals(null, store.get().id)
        assertEquals(null, store.get().phase)
        store.applyDone(BroadcastDonePayload.Ok("old-job", "1.1.1.1:8333"))
        assertEquals(null, store.get().id)
        assertEquals(null, store.get().phase)
    }

    @Test
    fun prepareUiBroadcast_ignores_in_flight_and_same_success_hex() {
        val store = createBroadcastStore()
        val first = prepareUiBroadcast(store, "aa")
        assertEquals(store.get().id, first)
        assertEquals(null, prepareUiBroadcast(store, "bb"))
        assertEquals(first, store.get().id)
        store.applyDone(BroadcastDonePayload.Ok(first!!, "1.1.1.1:8333"))
        assertEquals(null, prepareUiBroadcast(store, "aa"))
        val second = prepareUiBroadcast(store, "bb")
        assertTrue(second != null && second != first)
        assertEquals("bb", store.get().txHex)
    }

    @Test
    fun inFlightBroadcastEscape_cancels_then_force_closes() {
        assertEquals(BroadcastEscape.Cancel, inFlightBroadcastEscape("starting", "job-1", null))
        assertEquals(BroadcastEscape.ForceClose, inFlightBroadcastEscape("attempt", "job-1", "job-1"))
        assertEquals(BroadcastEscape.Ignore, inFlightBroadcastEscape("success", "job-1", "job-1"))
        assertEquals(BroadcastEscape.Ignore, inFlightBroadcastEscape("attempt", null, null))
    }
}
