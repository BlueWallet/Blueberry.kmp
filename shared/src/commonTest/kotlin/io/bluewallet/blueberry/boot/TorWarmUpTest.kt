package io.bluewallet.blueberry.boot

import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.echalote.Echalote
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TorWarmUpTest {
    @Test
    fun off_by_default_and_round_trips() {
        val db = createSqliteDatabase(":memory:")
        assertFalse(loadTorWarmUp(db))
        db.keyValue.set(TOR_WARM_UP_KEY, "yes")
        assertFalse(loadTorWarmUp(db))
        saveTorWarmUp(db, true)
        assertEquals("1", db.keyValue.get(TOR_WARM_UP_KEY))
        assertTrue(loadTorWarmUp(db))
        saveTorWarmUp(db, false)
        assertFalse(loadTorWarmUp(db))
        db.close()
    }

    @Test
    fun direct_relay_off_by_default_and_round_trips() {
        val db = createSqliteDatabase(":memory:")
        assertFalse(loadTorDirectRelay(db))
        saveTorDirectRelay(db, true)
        assertEquals("1", db.keyValue.get(TOR_DIRECT_RELAY_KEY))
        assertTrue(loadTorDirectRelay(db))
        saveTorDirectRelay(db, false)
        assertFalse(loadTorDirectRelay(db))
        db.close()
    }

    @Test
    fun direct_relay_switch_applies_to_echalote_now_in_tap_order() =
        runBlocking {
            val applied = mutableListOf<Boolean>()
            val jobs = listOf(true, false, true).map { on -> launchTorDirectRelay(on) { applied += it } }
            jobs.forEach { it.join() }
            assertEquals(listOf(true, false, true), applied)
        }

    @Test
    fun applying_direct_relay_sets_echalote_without_a_restart() =
        runBlocking {
            try {
                applyTorDirectRelay(true)
                assertTrue(Echalote.directRelay)
                applyTorDirectRelay(false)
                assertFalse(Echalote.directRelay)
            } finally {
                Echalote.directRelay = false
            }
        }

    @Test
    fun a_second_start_while_running_reuses_the_first() =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val warm: suspend (String) -> Unit = {
                calls++
                release.await()
            }
            val first = startTorWarmUp("https://example.invalid/", warm)
            val second = startTorWarmUp("https://example.invalid/", warm)
            assertSame(first, second)
            release.complete(Unit)
            first.join()
            assertEquals(1, calls)
        }

    @Test
    fun a_failed_warm_up_is_swallowed() =
        runBlocking {
            val job = startTorWarmUp("https://example.invalid/") { error("boom") }
            job.join()
            assertFalse(job.isCancelled)
        }

    @Test
    fun cancelling_the_caller_does_not_stop_a_route_switch_halfway() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var finished = false
            val caller =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    applyTorDirectRelay(true) {
                        started.complete(Unit)
                        release.await()
                        finished = true
                    }
                }
            started.await()
            caller.cancel()
            release.complete(Unit)
            caller.join()
            assertTrue(finished)
            assertTrue(caller.isCancelled)
        }
}
