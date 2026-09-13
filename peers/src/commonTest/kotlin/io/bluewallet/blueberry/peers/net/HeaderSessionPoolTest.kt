package io.bluewallet.blueberry.peers.net

import io.bluewallet.bip324.HeadersPayload
import io.bluewallet.bip324.InventoryPayload
import io.bluewallet.bip324.InventoryVector
import io.bluewallet.bip324.Message
import io.bluewallet.blueberry.peers.waitFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HeaderSessionPoolTest {
    @Test
    fun reuses_a_session_across_successful_batches() =
        runBlocking {
            var opens = 0
            var requests = 0
            val pool =
                createHeaderSessionPool(
                    HeaderSessionPoolOptions(
                        openSession = { _, _ ->
                            opens++
                            OpenedHeaderSession(
                                startHeight = 700_000,
                                requestHeaders = { _, _ ->
                                    requests++
                                    HeaderRequestResult(700_000, emptyList())
                                },
                                close = {},
                            )
                        },
                    ),
                )

            val a = pool.fetchBatch("1.1.1.1", 8333, HeaderFetchOptions(listOf(ByteArray(32))))
            val b = pool.fetchBatch("1.1.1.1", 8333, HeaderFetchOptions(listOf(ByteArray(32))))
            assertIs<HeaderBatchResult.Ok>(a)
            assertIs<HeaderBatchResult.Ok>(b)
            assertEquals(1, opens)
            assertEquals(2, requests)
            assertTrue(pool.has("1.1.1.1", 8333))
            pool.closeAll()
            assertFalse(pool.has("1.1.1.1", 8333))
        }

    @Test
    fun reports_busy_without_dropping_the_session() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val pool =
                createHeaderSessionPool(
                    HeaderSessionPoolOptions(
                        openSession = { _, _ ->
                            OpenedHeaderSession(
                                startHeight = 1,
                                requestHeaders = { _, _ ->
                                    gate.await()
                                    HeaderRequestResult(1, emptyList())
                                },
                                close = {},
                            )
                        },
                    ),
                )

            val first = async { pool.fetchBatch("3.3.3.3", 8333, HeaderFetchOptions(listOf(ByteArray(32)))) }
            waitFor { pool.isBusy("3.3.3.3", 8333) }
            val busy = pool.fetchBatch("3.3.3.3", 8333, HeaderFetchOptions(listOf(ByteArray(32))))
            assertEquals(HeaderBatchResult.Err(SESSION_BUSY_ERROR), busy)
            assertTrue(pool.has("3.3.3.3", 8333))
            gate.complete(Unit)
            assertIs<HeaderBatchResult.Ok>(first.await())
            assertFalse(pool.isBusy("3.3.3.3", 8333))
            pool.closeAll()
        }

    @Test
    fun drops_session_after_a_failed_getheaders() =
        runBlocking {
            var opens = 0
            val pool =
                createHeaderSessionPool(
                    HeaderSessionPoolOptions(
                        openSession = { _, _ ->
                            opens++
                            OpenedHeaderSession(
                                startHeight = 1,
                                requestHeaders = { _, _ -> error("peer reset") },
                                close = {},
                            )
                        },
                    ),
                )

            val first = pool.fetchBatch("2.2.2.2", 8333, HeaderFetchOptions(listOf(ByteArray(32))))
            assertIs<HeaderBatchResult.Err>(first)
            assertFalse(pool.has("2.2.2.2", 8333))
            pool.fetchBatch("2.2.2.2", 8333, HeaderFetchOptions(listOf(ByteArray(32))))
            assertEquals(2, opens)
            pool.closeAll()
        }

    @Test
    fun closeAll_drops_ipv6_sessions() =
        runBlocking {
            var closed = 0
            val pool =
                createHeaderSessionPool(
                    HeaderSessionPoolOptions(
                        openSession = { _, _ ->
                            OpenedHeaderSession(
                                startHeight = 1,
                                requestHeaders = { _, _ -> HeaderRequestResult(1, emptyList()) },
                                close = { closed++ },
                            )
                        },
                    ),
                )
            val host = "2001:db8::1"
            assertIs<HeaderBatchResult.Ok>(
                pool.fetchBatch(host, 8333, HeaderFetchOptions(listOf(ByteArray(32)))),
            )
            assertTrue(pool.has(host, 8333))
            pool.closeAll()
            assertFalse(pool.has(host, 8333))
            assertEquals(1, closed)
        }

    @Test
    fun closeAll_does_not_leak_a_session_that_was_still_opening() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            var hitOpen = false
            var closed = 0
            val pool =
                createHeaderSessionPool(
                    HeaderSessionPoolOptions(
                        openSession = { _, _ ->
                            hitOpen = true
                            gate.await()
                            OpenedHeaderSession(
                                startHeight = 1,
                                requestHeaders = { _, _ -> HeaderRequestResult(1, emptyList()) },
                                close = { closed++ },
                            )
                        },
                    ),
                )

            val fetch =
                async {
                    pool.fetchBatch("2001:db8::2", 8333, HeaderFetchOptions(listOf(ByteArray(32))))
                }
            waitFor { hitOpen }
            val close = async { pool.closeAll() }
            gate.complete(Unit)
            val result = fetch.await()
            close.await()
            assertIs<HeaderBatchResult.Err>(result)
            assertFalse(pool.has("2001:db8::2", 8333))
            assertEquals(1, closed)
        }

    @Test
    fun in_flight_open_marks_the_peer_busy() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            var opens = 0
            var closed = 0
            val pool =
                createHeaderSessionPool(
                    HeaderSessionPoolOptions(
                        openSession = { _, _ ->
                            opens++
                            gate.await()
                            OpenedHeaderSession(
                                startHeight = 1,
                                requestHeaders = { _, _ -> HeaderRequestResult(1, emptyList()) },
                                close = { closed++ },
                            )
                        },
                    ),
                )

            val first =
                async {
                    pool.fetchBatch("8.8.8.8", 8333, HeaderFetchOptions(listOf(ByteArray(32))))
                }
            waitFor { opens == 1 }
            val second =
                async {
                    pool.fetchBatch("8.8.8.8", 8333, HeaderFetchOptions(listOf(ByteArray(32))))
                }
            waitFor { pool.isBusy("8.8.8.8", 8333) }
            assertEquals(HeaderBatchResult.Err(SESSION_BUSY_ERROR), second.await())
            assertEquals(1, opens)
            gate.complete(Unit)
            assertIs<HeaderBatchResult.Ok>(first.await())
            pool.closeAll()
            assertEquals(1, closed)
        }

    @Test
    fun handshake_timeout_closes_the_tcp_duplex() =
        runBlocking {
            var closed = false
            val pool =
                createHeaderSessionPool(
                    HeaderSessionPoolOptions(
                        connectTimeoutMs = 40,
                        connect = { _, _ ->
                            object : io.bluewallet.bip324.ByteDuplex {
                                override suspend fun read(n: Int): ByteArray {
                                    CompletableDeferred<ByteArray>().await()
                                    return ByteArray(0)
                                }

                                override suspend fun write(bytes: ByteArray) {}

                                override suspend fun close() {
                                    closed = true
                                }
                            }
                        },
                    ),
                )

            val result =
                pool.fetchBatch(
                    "9.9.9.9",
                    8333,
                    HeaderFetchOptions(locatorHashes = listOf(ByteArray(32)), connectTimeoutMs = 40),
                )
            assertIs<HeaderBatchResult.Err>(result)
            assertTrue(closed)
            pool.closeAll()
        }

    @Test
    fun refuses_to_open_past_max_sessions() =
        runBlocking {
            var opens = 0
            val pool =
                createHeaderSessionPool(
                    HeaderSessionPoolOptions(
                        max = 1,
                        openSession = { _, _ ->
                            opens++
                            OpenedHeaderSession(
                                startHeight = 1,
                                requestHeaders = { _, _ -> HeaderRequestResult(1, emptyList()) },
                                close = {},
                            )
                        },
                    ),
                )

            val first = pool.fetchBatch("1.1.1.1", 8333, HeaderFetchOptions(listOf(ByteArray(32))))
            val second = pool.fetchBatch("2.2.2.2", 8333, HeaderFetchOptions(listOf(ByteArray(32))))
            assertIs<HeaderBatchResult.Ok>(first)
            assertEquals(HeaderBatchResult.Err(SESSION_BUSY_ERROR), second)
            assertEquals(1, opens)
            assertTrue(pool.has("1.1.1.1", 8333))
            assertFalse(pool.has("2.2.2.2", 8333))
            assertTrue(pool.isFull())
            pool.closeAll()
            assertFalse(pool.isFull())
        }
}

class HeaderTipHintTest {
    @Test
    fun inv_block_and_headers_hint_a_new_tip() {
        assertTrue(
            headerMessageSuggestsNewTip(
                Message.Inv(InventoryPayload(listOf(InventoryVector(MSG_BLOCK, ByteArray(32))))),
            ),
        )
        assertTrue(
            headerMessageSuggestsNewTip(
                Message.Headers(
                    HeadersPayload(
                        listOf(
                            io.bluewallet.bip324.BlockHeader(
                                version = 1,
                                previousBlockHash = ByteArray(32),
                                merkleRoot = ByteArray(32),
                                timestamp = 0u,
                                bits = 0u,
                                nonce = 0u,
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertFalse(headerMessageSuggestsNewTip(Message.Ping(ByteArray(8))))
        assertFalse(
            headerMessageSuggestsNewTip(
                Message.Inv(InventoryPayload(listOf(InventoryVector(1u, ByteArray(32))))),
            ),
        )
    }
}
