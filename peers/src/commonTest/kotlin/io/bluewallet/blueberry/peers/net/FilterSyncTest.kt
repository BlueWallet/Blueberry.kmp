package io.bluewallet.blueberry.peers.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FilterSyncTest {
    @Test
    fun maps_connect_failure_to_err() =
        runBlocking {
            val result =
                openFilterSession(
                    "1.2.3.4",
                    8333,
                    FilterSyncOptions(
                        connectTimeoutMs = 100,
                        syncTimeoutMs = 100,
                        connect = { _, _ -> error("ECONNREFUSED") },
                    ),
                )
            assertIs<FilterBatchResult.Err>(result)
            Unit
        }

    @Test
    fun uses_injected_runSession() =
        runBlocking {
            val stop = ByteArray(32)
            val result =
                openFilterSession(
                    "1.2.3.4",
                    8333,
                    FilterSyncOptions(
                        connect = { _, _ -> stubDuplex() },
                        runSession = { _, _ ->
                            object : FilterSessionApi {
                                override val services = 64uL

                                override suspend fun getCFCheckpt(stopHash: ByteArray) = listOf(ByteArray(32))

                                override suspend fun getCFHeaders(
                                    startHeight: Int,
                                    stopHash: ByteArray,
                                ) = CFHeadersResult(0, stop, ByteArray(32), listOf(ByteArray(32)))

                                override suspend fun getCFilters(
                                    startHeight: Int,
                                    stopHash: ByteArray,
                                    expectCount: Int,
                                    onFilter: (suspend (CFilterItem) -> Unit)?,
                                ) = listOf(CFilterItem(stop, byteArrayOf(1)))

                                override suspend fun close() {}
                            }
                        },
                    ),
                )
            val ok = assertIs<FilterBatchResult.Ok<FilterSessionApi>>(result)
            assertEquals(64uL, ok.value.services)
            assertEquals(1, ok.value.getCFCheckpt(stop).size)
            Unit
        }

    @Test
    fun refreshes_the_cfilter_timeout_whenever_activity_arrives() =
        runBlocking {
            val timeout = createInactivityTimeout(100, "cfilters")
            delay(60)
            timeout.refresh()
            delay(60)
            assertFalse(timeout.expired)
            delay(60)
            assertTrue(timeout.expired)
            assertEquals("cfilters inactive for 100ms", timeout.error?.message)
            timeout.clear()
        }

    @Test
    fun allows_a_request_to_outlive_its_timeout_while_activity_continues() =
        runBlocking {
            val result =
                runWithInactivityTimeout(100, "cfilters") { activity ->
                    delay(60)
                    activity()
                    delay(60)
                    activity()
                    "complete"
                }
            assertEquals("complete", result)
        }
}
