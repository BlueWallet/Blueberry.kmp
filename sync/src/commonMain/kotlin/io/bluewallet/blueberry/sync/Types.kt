package io.bluewallet.blueberry.sync

import io.bluewallet.blueberry.bus.SyncCatchupReason

enum class SyncMode {
    IDLE,
    CATCHUP,
}

data class SyncSnapshot(
    /** From last headers:progress (0/0 = unknown tip). */
    val headersDownloaded: Int,
    val headersTotal: Int,
    /**
     * 0 if filters cover birthday→header tip, else 1.
     * Not `missingRanges(headersMin, headersTip).length` (that includes
     * pre-birthday heights this node never downloads).
     */
    val filterMissingRangeCount: Int,
    /** Filter work pending and CF peer pool below threshold. */
    val filterWorkNeedsPeers: Boolean,
    /** Whether compact-filter matching still has durable work queued. */
    val matchingBehind: Boolean,
    val blocksDownloaded: Int,
    val blocksMatched: Int,
    val needingDownloadCount: Int,
    val alivePeerCount: Int,
)

sealed class SyncEvaluation {
    data object Idle : SyncEvaluation()

    data class Catchup(
        val reason: SyncCatchupReason,
    ) : SyncEvaluation()
}
