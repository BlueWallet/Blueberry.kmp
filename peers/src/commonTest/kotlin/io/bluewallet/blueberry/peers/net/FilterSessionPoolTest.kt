package io.bluewallet.blueberry.peers.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val unusedConnect: TcpConnect = stubPlatformNet().connect

private fun fakeSession(onClose: () -> Unit = {}): FilterSessionApi =
    object : FilterSessionApi {
        override val services = 64uL

        override suspend fun getCFCheckpt(stopHash: ByteArray) = emptyList<ByteArray>()

        override suspend fun getCFHeaders(
            startHeight: Int,
            stopHash: ByteArray,
        ) = CFHeadersResult(0, ByteArray(32), ByteArray(32), emptyList())

        override suspend fun getCFilters(
            startHeight: Int,
            stopHash: ByteArray,
            expectCount: Int,
            onFilter: (suspend (CFilterItem) -> Unit)?,
        ) = emptyList<CFilterItem>()

        override suspend fun close() = onClose()
    }

class FilterSessionPoolTest {
    @Test
    fun reuses_an_idle_session_across_withSession_calls() =
        runBlocking {
            var opens = 0
            val pool =
                createFilterSessionPool(
                    FilterSessionPoolOptions(
                        connect = unusedConnect,
                        max = 2,
                        openSession = { _, _, _ ->
                            opens++
                            FilterBatchResult.Ok(fakeSession())
                        },
                    ),
                )
            pool.setPeers(listOf(FilterPoolPeer("1.1.1.1", 8333)))
            pool.withSession { _, _ -> "a" }
            pool.withSession { _, _ -> "b" }
            assertEquals(1, opens)
            pool.closeAll()
        }

    @Test
    fun cools_a_failed_peer_and_opens_another() =
        runBlocking {
            var opens = 0
            val pool =
                createFilterSessionPool(
                    FilterSessionPoolOptions(
                        connect = unusedConnect,
                        max = 2,
                        coolMs = 60_000,
                        openSession = { _, _, _ ->
                            opens++
                            FilterBatchResult.Ok(fakeSession())
                        },
                    ),
                )
            pool.setPeers(
                listOf(
                    FilterPoolPeer("1.1.1.1", 8333),
                    FilterPoolPeer("2.2.2.2", 8333),
                ),
            )
            runCatching {
                pool.withSession<Unit> { _, _ -> error("boom") }
            }
            val second = pool.withSession { _, peer -> peer.host }
            assertEquals("2.2.2.2", second)
            assertEquals(2, opens)
            pool.closeAll()
        }

    @Test
    fun onOpenCount_rises_while_leased_and_drops_to_zero_on_closeAll() =
        runBlocking {
            val counts = mutableListOf<Int>()
            val pool =
                createFilterSessionPool(
                    FilterSessionPoolOptions(
                        connect = unusedConnect,
                        max = 2,
                        openSession = { _, _, _ -> FilterBatchResult.Ok(fakeSession()) },
                        onOpenCount = { counts.add(it) },
                    ),
                )
            pool.setPeers(listOf(FilterPoolPeer("1.1.1.1", 8333)))
            var mid = -1
            pool.withSession { _, _ ->
                mid = counts.lastOrNull() ?: -1
                "ok"
            }
            assertEquals(1, mid)
            assertEquals(1, counts.last())
            pool.closeAll()
            assertEquals(0, counts.last())
        }

    @Test
    fun honors_coolMs_when_only_one_peer_is_available() =
        runBlocking {
            var t = 1_000L
            var opens = 0
            val pool =
                createFilterSessionPool(
                    FilterSessionPoolOptions(
                        connect = unusedConnect,
                        max = 1,
                        coolMs = 5_000,
                        now = { t },
                        openSession = { _, _, _ ->
                            opens++
                            FilterBatchResult.Err("down")
                        },
                    ),
                )
            pool.setPeers(listOf(FilterPoolPeer("1.1.1.1", 8333)))
            assertNull(pool.withSession { _, _ -> "x" })
            assertEquals(1, opens)
            assertNull(pool.withSession { _, _ -> "x" })
            assertEquals(1, opens)
            assertTrue(pool.coolDelayMs() > 0)
            t += 5_000
            assertNull(pool.withSession { _, _ -> "x" })
            assertEquals(2, opens)
            pool.closeAll()
        }

    @Test
    fun closeAll_during_open_closes_the_session_that_finishes_later() =
        runBlocking {
            val held = CompletableDeferred<Unit>()
            var closed = 0
            var ran = false
            val pool =
                createFilterSessionPool(
                    FilterSessionPoolOptions(
                        connect = unusedConnect,
                        max = 1,
                        openSession = { _, _, _ ->
                            held.await()
                            FilterBatchResult.Ok(fakeSession { closed++ })
                        },
                    ),
                )
            pool.setPeers(listOf(FilterPoolPeer("1.1.1.1", 8333)))
            coroutineScope {
                val lease =
                    async {
                        pool.withSession { _, _ ->
                            ran = true
                            "ran"
                        }
                    }
                delay(20)
                val closing = async { pool.closeAll() }
                held.complete(Unit)
                closing.await()
                assertNull(lease.await())
                assertFalse(ran)
                assertEquals(1, closed)
            }
        }

    @OptIn(ExperimentalAtomicApi::class)
    @Test
    fun overlapping_withSession_does_not_open_the_same_peer_twice() =
        runBlocking {
            val opens = AtomicInt(0)
            val pool =
                createFilterSessionPool(
                    FilterSessionPoolOptions(
                        connect = unusedConnect,
                        max = 2,
                        openSession = { _, _, _ ->
                            opens.incrementAndFetch()
                            delay(30)
                            FilterBatchResult.Ok(fakeSession())
                        },
                    ),
                )
            pool.setPeers(listOf(FilterPoolPeer("1.1.1.1", 8333)))
            coroutineScope {
                List(8) {
                    async(Dispatchers.Default) {
                        pool.withSession { _, _ -> "ok" }
                    }
                }.awaitAll()
            }
            assertEquals(1, opens.load())
            pool.closeAll()
        }
}
