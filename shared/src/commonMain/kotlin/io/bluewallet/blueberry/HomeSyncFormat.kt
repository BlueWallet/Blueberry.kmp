package io.bluewallet.blueberry

fun connectedPeersCount(
    probe: Int,
    hdr: Int,
    filt: Int,
    blk: Int,
): Int = probe + hdr + filt + blk

fun connectedPeersLabel(known: Int): String = "Connected Peers (${formatGrouped(known)} known)"

const val SYNC_DOCK_HIDE_DELAY_MS = 2_000L

fun isSyncDockIdle(
    percent: Int,
    parseBusy: Boolean,
): Boolean = percent >= 100 && !parseBusy

fun syncDockShownImmediately(
    idle: Boolean,
    alwaysShow: Boolean,
): Boolean = alwaysShow || !idle

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
