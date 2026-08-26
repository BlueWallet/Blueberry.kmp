package io.bluewallet.blueberry.headers.modules

import io.bluewallet.blueberry.peers.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeaderOfflineRecoverTest {
    @Test
    fun at_tip_naps_when_any_watcher_is_live() {
        val idle = Config.headerIdleCheckMs
        assertEquals(idle, atTipWaitMs(idle, hasLiveWatchers = true))
        assertEquals(HEADER_WATCHER_FILL_MS, atTipWaitMs(idle, hasLiveWatchers = false))
    }

    @Test
    fun quiet_peer_kicks_are_ignored_only_while_a_watcher_is_up() {
        assertTrue(ignoreQuietPeerKick(quiet = true, waitingForPeers = false, hasLiveWatchers = true))
        assertFalse(ignoreQuietPeerKick(quiet = true, waitingForPeers = false, hasLiveWatchers = false))
        assertFalse(ignoreQuietPeerKick(quiet = true, waitingForPeers = true, hasLiveWatchers = true))
        assertFalse(ignoreQuietPeerKick(quiet = false, waitingForPeers = false, hasLiveWatchers = true))
    }

    @Test
    fun last_watcher_drop_is_the_offline_edge() {
        assertTrue(lostLastWatcher(prevOpen = 1, nextOpen = 0))
        assertFalse(lostLastWatcher(prevOpen = 0, nextOpen = 0))
        assertFalse(lostLastWatcher(prevOpen = 4, nextOpen = 3))
    }
}
