package io.bluewallet.blueberry.peers

import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigTest {
    @Test
    fun helix3_probe_defaults() {
        assertEquals(3_000L, Config.peerProbeTimeoutMs)
        assertEquals(30, Config.peerConcurrency)
        assertEquals(30_000L, Config.headerSyncTimeoutMs)
        assertEquals(10, Config.headerRacePeers)
        assertEquals(30_000L, Config.filterSyncTimeoutMs)
        assertEquals(10, Config.filterConcurrency)
        assertEquals(2000, Config.filterHeaderBatchSize)
        assertEquals(100, Config.filterBatchSize)
        assertEquals(3_000L, Config.blockConnectTimeoutMs)
        assertEquals(30_000L, Config.blockSyncTimeoutMs)
        assertEquals(10, Config.blockConcurrency)
    }

    @Test
    fun header_idle_check_is_one_block_time() {
        assertEquals(10 * 60 * 1_000L, Config.headerIdleCheckMs)
    }
}
