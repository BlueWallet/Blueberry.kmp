package io.bluewallet.blueberry

/** Build the coin-control snapshot from a wallet txs snapshot. */
fun coinControlSnapshotFrom(
    snap: WalletTxsSnapshot,
    feeRateSatPerVb: Double? = null,
): CoinControlSnapshot {
    val frozen =
        snap.utxos
            .filter { it.isFrozen }
            .map { it.key }
            .toSet()
    return buildCoinControlSnapshot(snap.utxos, snap.coinGroupIds, frozen, feeRateSatPerVb)
}
