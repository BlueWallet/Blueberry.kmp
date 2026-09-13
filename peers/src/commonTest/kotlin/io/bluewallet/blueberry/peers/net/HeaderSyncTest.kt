package io.bluewallet.blueberry.peers.net

import io.bluewallet.blueberry.peers.currentTimeMillis
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HeaderSyncTest {
    @Test
    fun maps_connect_failure_to_err() =
        runBlocking {
            val result =
                fetchHeadersBatch(
                    "1.2.3.4",
                    8333,
                    HeaderSyncOptions(
                        connectTimeoutMs = 500,
                        locatorHashes = listOf(ByteArray(32)),
                        connect = { _, _ -> throw IllegalStateException("ECONNREFUSED") },
                    ),
                )
            val err = assertIs<HeaderBatchResult.Err>(result)
            assertTrue(err.error.contains("ECONNREFUSED"))
        }

    @Test
    fun connectTimeout_aborts_slow_connect_and_closes_duplex() =
        runBlocking {
            var closed = false
            val result =
                fetchHeadersBatch(
                    "1.2.3.4",
                    8333,
                    HeaderSyncOptions(
                        connectTimeoutMs = 20,
                        headersTimeoutMs = 5_000,
                        locatorHashes = listOf(ByteArray(32)),
                        connect = { _, _ ->
                            withContext(NonCancellable) { delay(200) }
                            val d = stubDuplex()
                            object : io.bluewallet.bip324.ByteDuplex by d {
                                override suspend fun close() {
                                    closed = true
                                    d.close()
                                }
                            }
                        },
                    ),
                )
            val err = assertIs<HeaderBatchResult.Err>(result)
            assertTrue(err.error.contains("timed out") || err.error.contains("aborted"))
            delay(250)
            assertTrue(closed)
        }

    @Test
    fun headersTimeout_applies_after_connect_for_injected_requestHeaders() =
        runBlocking {
            val started = currentTimeMillis()
            val result =
                fetchHeadersBatch(
                    "1.2.3.4",
                    8333,
                    HeaderSyncOptions(
                        connectTimeoutMs = 5_000,
                        headersTimeoutMs = 30,
                        locatorHashes = listOf(ByteArray(32)),
                        connect = { _, _ -> stubDuplex() },
                        requestHeaders = { _, _, _, _ ->
                            delay(200)
                            HeaderRequestResult(1, emptyList())
                        },
                    ),
                )
            assertIs<HeaderBatchResult.Err>(result)
            val err = result as HeaderBatchResult.Err
            assertTrue(err.error.contains("timed out") || err.error.contains("aborted"))
            assertTrue(currentTimeMillis() - started < 150)
        }

    @Test
    fun forwards_locator_and_stopHash_into_requestHeaders() =
        runBlocking {
            val locator = listOf(ByteArray(32) { 7 })
            val stop = ByteArray(32) { 9 }
            var seenPort: Int? = null
            var seenLocator: List<ByteArray>? = null
            var seenStop: ByteArray? = null

            val result =
                fetchHeadersBatch(
                    "1.2.3.4",
                    8333,
                    HeaderSyncOptions(
                        connectTimeoutMs = 500,
                        headersTimeoutMs = 500,
                        locatorHashes = locator,
                        stopHash = stop,
                        connect = { _, _ -> stubDuplex() },
                        requestHeaders = { _, port, locatorHashes, stopHash ->
                            seenPort = port
                            seenLocator = locatorHashes
                            seenStop = stopHash
                            HeaderRequestResult(1, emptyList())
                        },
                    ),
                )

            assertIs<HeaderBatchResult.Ok>(result)
            assertEquals(8333, seenPort)
            assertNotNull(seenLocator)
            assertTrue(seenLocator === locator)
            assertTrue(seenStop === stop)
        }
}
