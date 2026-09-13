package io.bluewallet.blueberry

import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.wallet.Wallet

/** Persist a UTXO label. Trim; empty clears. Refreshes wallet txs snapshot. */
fun setUtxoName(
    db: Database,
    wallet: Wallet,
    walletTxsStore: WalletTxsStore,
    outpoint: String,
    name: String,
) {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) {
        db.utxoNames.delete(outpoint)
    } else {
        db.utxoNames.upsert(outpoint, trimmed)
    }
    val at = nowMillis()
    walletTxsStore.apply(snapshotFromDb(db, at, at, wallet))
}
