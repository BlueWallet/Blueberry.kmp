package io.bluewallet.blueberry

import io.bluewallet.blueberry.bus.BroadcastDonePayload
import io.bluewallet.blueberry.bus.BroadcastPhase
import io.bluewallet.blueberry.bus.BroadcastProgressPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BroadcastStoreTest {
    @Test
    fun progress_and_done_update_the_same_job() {
        val store = createBroadcastStore()
        store.start("1", "deadbeef")
        store.applyProgress(
            BroadcastProgressPayload("1", BroadcastPhase.ATTEMPT, attempt = 1, maxAttempts = 20, peer = "1.1.1.1:8333"),
        )
        assertEquals("attempt", store.get("1")?.phase)
        assertEquals(1, store.get("1")?.attempt)
        store.applyProgress(
            BroadcastProgressPayload(
                "1",
                BroadcastPhase.ATTEMPT,
                attempt = 1,
                maxAttempts = 20,
                peer = "1.1.1.1:8333",
                percent = 55,
                stage = "Building circuit",
            ),
        )
        assertEquals(55, store.get("1")?.percent)
        assertEquals("Building circuit", store.get("1")?.stage)
        assertEquals("Tor: Building circuit", broadcastTorCaption("Building circuit"))
        store.applyDone(BroadcastDonePayload.Ok("1", "1.1.1.1:8333"))
        assertEquals("success", store.get("1")?.phase)
        assertTrue(!broadcastJobInFlight(store.get("1")?.phase))
        store.reset()
        assertNull(store.get("1"))
    }

    @Test
    fun a_stop_stays_put_when_the_circuit_reports_more_progress() {
        val store = createBroadcastStore()
        store.start("1", "aa")
        store.applyProgress(BroadcastProgressPayload("1", BroadcastPhase.ATTEMPT, percent = 20, stage = "Building circuit"))
        store.requestStop("1")
        store.applyProgress(BroadcastProgressPayload("1", BroadcastPhase.ATTEMPT, percent = 80, stage = "Building circuit"))
        val snap = store.get("1")
        assertEquals(true, snap?.stopRequested)
        assertEquals("Stopping", broadcastStatus(checkNotNull(snap)).title)
        assertEquals("This send will not go out", broadcastStatus(snap).line)
    }

    @Test
    fun progress_for_one_job_does_not_change_another() {
        val store = createBroadcastStore()
        store.start("1", "aa")
        store.start("2", "bb")
        store.applyProgress(
            BroadcastProgressPayload("1", BroadcastPhase.ATTEMPT, attempt = 3, peer = "1.1.1.1:8333"),
        )
        assertEquals("attempt", store.get("1")?.phase)
        assertEquals(3, store.get("1")?.attempt)
        assertEquals("starting", store.get("2")?.phase)
        assertEquals("bb", store.get("2")?.txHex)
    }

    @Test
    fun idle_store_ignores_stale_progress_and_done() {
        val store = createBroadcastStore()
        store.start("old-job", "aa")
        store.reset()
        store.applyProgress(
            BroadcastProgressPayload("old-job", BroadcastPhase.ATTEMPT, attempt = 2, peer = "1.1.1.1:8333"),
        )
        assertNull(store.get("old-job"))
        store.applyDone(BroadcastDonePayload.Ok("old-job", "1.1.1.1:8333"))
        assertNull(store.get("old-job"))
    }

    @Test
    fun enqueue_armed_broadcast_leaves_another_job_running() {
        val store = createBroadcastStore()
        store.start("1", "aa")
        var armed: String? = null
        val req = enqueueArmedBroadcast(store, "bb") { id -> armed = id }
        assertEquals("bb", store.get(req!!.id)?.txHex)
        assertEquals(req.id, armed)
        assertEquals("starting", store.get("1")?.phase)
        assertEquals("aa", store.get("1")?.txHex)
    }

    @Test
    fun enqueue_does_not_arm_the_same_hex_twice() {
        val store = createBroadcastStore()
        store.start("1", "aa")
        var armed = false
        val req = enqueueArmedBroadcast(store, "aa") { armed = true }
        assertNull(req)
        assertFalse(armed)
    }

    @Test
    fun prepareUiBroadcast_starts_another_hex_and_refuses_the_same_success() {
        val store = createBroadcastStore()
        val first = prepareUiBroadcast(store, "aa")
        val other = prepareUiBroadcast(store, "bb")
        assertEquals(first, store.get(first!!)?.id)
        assertTrue(other != null && other != first)
        assertEquals("aa", store.get(first)?.txHex)
        assertEquals("bb", store.get(other)?.txHex)
        store.applyDone(BroadcastDonePayload.Ok(first, "1.1.1.1:8333"))
        assertNull(prepareUiBroadcast(store, "aa"))
    }

    @Test
    fun remembers_tried_peers_and_excludes_them_on_retry() {
        val store = createBroadcastStore()
        val first = prepareUiBroadcast(store, "aa")!!
        store.applyProgress(
            BroadcastProgressPayload(first, BroadcastPhase.ATTEMPT, peer = "1.1.1.1:8333"),
        )
        store.applyProgress(
            BroadcastProgressPayload(first, BroadcastPhase.FAILED_ATTEMPT, peer = "2.2.2.2:8333"),
        )
        store.applyDone(BroadcastDonePayload.Error(first, "eof"))
        assertEquals(setOf("1.1.1.1:8333", "2.2.2.2:8333"), store.get(first)?.triedPeers)
        val retry = enqueueBroadcast(store, "aa")!!
        assertTrue(retry.id != first)
        assertNull(store.get(first))
        assertEquals(setOf("1.1.1.1:8333", "2.2.2.2:8333"), retry.excludePeers)
        assertEquals(setOf("1.1.1.1:8333", "2.2.2.2:8333"), store.get(retry.id)?.triedPeers)
    }
}
