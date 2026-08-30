package io.bluewallet.blueberry.sync

import io.bluewallet.blueberry.bus.SyncCatchupReason
import kotlin.test.Test
import kotlin.test.assertEquals

class EvaluateTest {
    private fun base(
        headersDownloaded: Int = 100,
        headersTotal: Int = 100,
        filterMissingRangeCount: Int = 0,
        filterWorkNeedsPeers: Boolean = false,
        matchingBehind: Boolean = false,
        blocksDownloaded: Int = 5,
        blocksMatched: Int = 5,
        needingDownloadCount: Int = 0,
        alivePeerCount: Int = 3,
    ) = SyncSnapshot(
        headersDownloaded = headersDownloaded,
        headersTotal = headersTotal,
        filterMissingRangeCount = filterMissingRangeCount,
        filterWorkNeedsPeers = filterWorkNeedsPeers,
        matchingBehind = matchingBehind,
        blocksDownloaded = blocksDownloaded,
        blocksMatched = blocksMatched,
        needingDownloadCount = needingDownloadCount,
        alivePeerCount = alivePeerCount,
    )

    @Test
    fun caught_up_is_idle() {
        assertEquals(SyncEvaluation.Idle, evaluateSyncState(base()))
    }

    @Test
    fun priority_no_peers_wins_over_headers_behind() {
        assertEquals(
            SyncEvaluation.Catchup(SyncCatchupReason.PEERS),
            evaluateSyncState(base(alivePeerCount = 0, headersDownloaded = 90, headersTotal = 100)),
        )
    }

    @Test
    fun locally_complete_with_no_peers_is_idle() {
        assertEquals(SyncEvaluation.Idle, evaluateSyncState(base(alivePeerCount = 0)))
    }

    @Test
    fun unknown_or_behind_tip_is_headers() {
        assertEquals(
            SyncEvaluation.Catchup(SyncCatchupReason.HEADERS),
            evaluateSyncState(base(headersTotal = 0)),
        )
        assertEquals(
            SyncEvaluation.Catchup(SyncCatchupReason.HEADERS),
            evaluateSyncState(base(headersDownloaded = 90, headersTotal = 100)),
        )
    }

    @Test
    fun filter_gaps_are_filters_thin_cf_pool_is_peers() {
        assertEquals(
            SyncEvaluation.Catchup(SyncCatchupReason.FILTERS),
            evaluateSyncState(base(filterMissingRangeCount = 2)),
        )
        assertEquals(
            SyncEvaluation.Catchup(SyncCatchupReason.PEERS),
            evaluateSyncState(base(filterMissingRangeCount = 2, filterWorkNeedsPeers = true)),
        )
    }

    @Test
    fun unscanned_filters_are_filters_not_idle() {
        assertEquals(
            SyncEvaluation.Catchup(SyncCatchupReason.FILTERS),
            evaluateSyncState(base(matchingBehind = true)),
        )
        assertEquals(
            SyncEvaluation.Idle,
            evaluateSyncState(base(matchingBehind = false)),
        )
        assertEquals(
            SyncEvaluation.Catchup(SyncCatchupReason.FILTERS),
            evaluateSyncState(base(matchingBehind = true, alivePeerCount = 0)),
        )
    }

    @Test
    fun pending_or_in_flight_blocks_are_blocks() {
        assertEquals(
            SyncEvaluation.Catchup(SyncCatchupReason.BLOCKS),
            evaluateSyncState(base(needingDownloadCount = 1)),
        )
        assertEquals(
            SyncEvaluation.Catchup(SyncCatchupReason.BLOCKS),
            evaluateSyncState(
                base(needingDownloadCount = 0, blocksDownloaded = 4, blocksMatched = 5),
            ),
        )
    }
}
