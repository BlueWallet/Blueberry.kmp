package io.bluewallet.blueberry

fun connectedPeersCount(
    probe: Int,
    hdr: Int,
    filt: Int,
    blk: Int,
): Int = probe + hdr + filt + blk

fun connectedPeersLabel(known: Int): String = "Connected Peers (${formatGrouped(known)} known)"

fun showSyncMesh(unifiedPercent: Int): Boolean = unifiedPercent < 100

const val SYNC_MESH_HEIGHT_DP = 187

fun overallSyncEtaMs(
    percent: Int,
    chainEtaMs: Long?,
    filtersEtaMs: Long?,
    matchEtaMs: Long?,
    blocksEtaMs: Long?,
): Long? {
    if (percent >= 100) return null
    return listOfNotNull(chainEtaMs, filtersEtaMs, matchEtaMs, blocksEtaMs)
        .filter { it > 0 }
        .maxOrNull()
}
