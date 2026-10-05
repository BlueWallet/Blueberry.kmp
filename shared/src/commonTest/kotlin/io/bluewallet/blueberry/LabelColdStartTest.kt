package io.bluewallet.blueberry

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LabelColdStartTest {
    @Test
    fun peers_start_while_label_restore_is_still_waiting() =
        runBlocking {
            val releaseRestore = CompletableDeferred<Unit>()
            val peersStarted = CompletableDeferred<Unit>()
            var refreshed = false
            val job =
                launch {
                    startPeersThenRestoreLabels(
                        startPeers = { peersStarted.complete(Unit) },
                        restoreLabels = {
                            releaseRestore.await()
                            true
                        },
                        onRestored = { refreshed = true },
                    )
                }
            withTimeout(1_000) { peersStarted.await() }
            assertFalse(refreshed)
            releaseRestore.complete(Unit)
            job.join()
            assertTrue(refreshed)
        }

    @Test
    fun label_server_failure_does_not_stop_peers_or_refresh() =
        runBlocking {
            var started = false
            var refreshed = false
            startPeersThenRestoreLabels(
                startPeers = { started = true },
                restoreLabels = { error("bytes store down") },
                onRestored = { refreshed = true },
            )
            assertTrue(started)
            assertFalse(refreshed)
        }

    @Test
    fun cancelling_startup_does_not_finish_after_a_stuck_restore() =
        runBlocking {
            var finished = false
            val job =
                launch {
                    startPeersThenRestoreLabels(
                        startPeers = {},
                        restoreLabels = { awaitCancellation() },
                        onRestored = { error("refreshed") },
                    )
                    finished = true
                }
            job.cancel()
            job.join()
            assertFalse(finished)
            assertEquals(true, job.isCancelled)
        }
}
