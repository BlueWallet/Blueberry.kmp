package io.bluewallet.blueberry

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignedPsbtReviewTest {
    /** Both taps land before the button disables: the second must not start a second held run. */
    @Test
    fun a_second_broadcast_tap_while_one_runs_starts_nothing() =
        runBlocking {
            val one = OneBroadcast()
            val release = CompletableDeferred<Unit>()
            var runs = 0
            val first =
                one.launchIn(this) {
                    runs++
                    release.await()
                }
            val second = one.launchIn(this) { runs++ }
            yield()
            assertNotNull(first)
            assertNull(second)
            assertEquals(1, runs)
            assertTrue(one.running)
            release.complete(Unit)
            first.join()
            assertFalse(one.running)
            one.launchIn(this) { runs++ }?.join()
            assertEquals(2, runs)
        }
}
