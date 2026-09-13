package io.bluewallet.blueberry.peers.net

import io.bluewallet.bip157.NODE_COMPACT_FILTERS
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeerProbeServicesTest {
    @Test
    fun returns_services_from_injected_handshake() =
        runBlocking {
            val result =
                probePeer(
                    "1.2.3.4",
                    8333,
                    ProbeOptions(
                        timeoutMs = 500,
                        connect = { _, _ -> stubDuplex() },
                        handshakeAndGetAddr = { _, _ ->
                            HandshakeResult(emptyList(), NODE_COMPACT_FILTERS.toULong())
                        },
                    ),
                )
            assertTrue(result is ProbeResult.Ok)
            assertEquals(NODE_COMPACT_FILTERS.toULong(), (result as ProbeResult.Ok).services)
        }
}
