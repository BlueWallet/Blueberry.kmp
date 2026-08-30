package io.bluewallet.blueberry.sync

import io.bluewallet.blueberry.bus.SyncCatchupReason

fun evaluateSyncState(s: SyncSnapshot): SyncEvaluation {
    val headersBehind = s.headersTotal <= 0 || s.headersDownloaded < s.headersTotal
    val filtersBehind = s.filterMissingRangeCount > 0
    val blocksBehind =
        s.needingDownloadCount > 0 || s.blocksDownloaded < s.blocksMatched
    val needsNetwork = headersBehind || filtersBehind || blocksBehind

    if (needsNetwork && s.alivePeerCount == 0) {
        return SyncEvaluation.Catchup(SyncCatchupReason.PEERS)
    }
    if (headersBehind) {
        return SyncEvaluation.Catchup(SyncCatchupReason.HEADERS)
    }
    if (filtersBehind && s.filterWorkNeedsPeers) {
        return SyncEvaluation.Catchup(SyncCatchupReason.PEERS)
    }
    if (filtersBehind) {
        return SyncEvaluation.Catchup(SyncCatchupReason.FILTERS)
    }
    if (s.matchingBehind) {
        return SyncEvaluation.Catchup(SyncCatchupReason.FILTERS)
    }
    if (blocksBehind) {
        return SyncEvaluation.Catchup(SyncCatchupReason.BLOCKS)
    }
    return SyncEvaluation.Idle
}
