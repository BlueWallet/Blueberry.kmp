package io.bluewallet.blueberry

import io.bluewallet.blueberry.parse.CoinLinkage

/** Build the coin-control snapshot from a wallet txs snapshot. */
fun coinControlSnapshotFrom(
    snap: WalletTxsSnapshot,
    feeRateSatPerVb: Double? = null,
    sending: Set<String> = emptySet(),
): CoinControlSnapshot {
    val frozen =
        snap.utxos
            .filter { it.isFrozen }
            .map { it.key }
            .toSet()
    return buildCoinControlSnapshot(
        snap.utxos,
        CoinLinkage(certain = snap.coinCertainGroupIds, likely = snap.coinGroupIds),
        frozen,
        feeRateSatPerVb,
    ).markedSending(sending)
}
