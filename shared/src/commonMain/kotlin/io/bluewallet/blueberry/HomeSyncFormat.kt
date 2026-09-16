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

/** First stop of the teal wash in [SyncMeshGradient]. */
internal const val SYNC_MESH_TEAL_T = 0.485577f

/**
 * Fraction of a bottom-aligned dock overlay that may become opaque black.
 * `1f` means it never does — required so the teal mesh can show through.
 */
const val HOME_SYNC_DOCK_OPAQUE_FROM = 1f

/** True when a bottom-aligned overlay's opaque band covers the mesh teal. */
fun dockOverlayHidesSyncMeshTeal(
    overlayHeightDp: Float = SYNC_MESH_HEIGHT_DP.toFloat(),
    overlayOpaqueFrom: Float = HOME_SYNC_DOCK_OPAQUE_FROM,
    meshHeightDp: Float = SYNC_MESH_HEIGHT_DP.toFloat(),
    tealT: Float = SYNC_MESH_TEAL_T,
): Boolean {
    if (overlayOpaqueFrom >= 1f || overlayHeightDp <= 0f || meshHeightDp <= 0f) return false
    val opaqueTop = overlayHeightDp * overlayOpaqueFrom.coerceIn(0f, 1f)
    val meshTop = overlayHeightDp - meshHeightDp
    val tealTop = meshTop + meshHeightDp * tealT.coerceIn(0f, 1f)
    val meshBottom = meshTop + meshHeightDp
    val overlapStart = maxOf(opaqueTop, tealTop)
    val overlapEnd = minOf(overlayHeightDp, meshBottom)
    return overlapStart < overlapEnd
}

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
