package io.bluewallet.blueberry.boot

import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlinx.coroutines.CompletableDeferred
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
}
