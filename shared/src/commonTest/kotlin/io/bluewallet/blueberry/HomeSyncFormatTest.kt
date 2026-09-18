package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeSyncFormatTest {
    @Test
    fun connectedPeersCount_sums_live_sessions() {
        assertEquals(0, connectedPeersCount(0, 0, 0, 0))
        assertEquals(21, connectedPeersCount(20, 1, 0, 0))
        assertEquals(26, connectedPeersCount(20, 1, 2, 3))
    }

    @Test
    fun connectedPeersLabel_includes_known_on_same_line() {
        assertEquals("Connected Peers (0 known)", connectedPeersLabel(0))
        assertEquals("Connected Peers (25,685 known)", connectedPeersLabel(25_685))
    }

    @Test
    fun overallSyncEtaMs_is_slowest_incomplete_bar() {
        assertNull(
            overallSyncEtaMs(
                percent = 100,
                chainEtaMs = 5_000,
                filtersEtaMs = 4_000,
                matchEtaMs = 3_000,
                blocksEtaMs = 2_000,
            ),
        )
        assertEquals(
            8_000,
            overallSyncEtaMs(
                percent = 40,
                chainEtaMs = 8_000,
                filtersEtaMs = 1_000,
                matchEtaMs = null,
                blocksEtaMs = 2_000,
            ),
        )
        assertNull(
            overallSyncEtaMs(
                percent = 40,
                chainEtaMs = null,
                filtersEtaMs = null,
                matchEtaMs = null,
                blocksEtaMs = null,
            ),
        )
        assertNull(
            overallSyncEtaMs(
                percent = 40,
                chainEtaMs = 0,
                filtersEtaMs = null,
                matchEtaMs = null,
                blocksEtaMs = null,
            ),
        )
        assertEquals(
            5_000,
            overallSyncEtaMs(
                percent = 40,
                chainEtaMs = 0,
                filtersEtaMs = 5_000,
                matchEtaMs = null,
                blocksEtaMs = 0,
            ),
        )
    }

    @Test
    fun sync_dock_is_idle_only_when_fully_synced_and_parse_is_done() {
        assertEquals(false, isSyncDockIdle(percent = 99, parseBusy = false))
        assertEquals(false, isSyncDockIdle(percent = 100, parseBusy = true))
        assertEquals(true, isSyncDockIdle(percent = 100, parseBusy = false))
        assertEquals(true, isSyncDockIdle(percent = 140, parseBusy = false))
    }

    @Test
    fun sync_dock_stays_hidden_when_already_idle_unless_always_show() {
        assertEquals(false, syncDockShownImmediately(idle = true, alwaysShow = false))
        assertEquals(true, syncDockShownImmediately(idle = true, alwaysShow = true))
        assertEquals(true, syncDockShownImmediately(idle = false, alwaysShow = false))
        assertEquals(true, syncDockShownImmediately(idle = false, alwaysShow = true))
    }
}
