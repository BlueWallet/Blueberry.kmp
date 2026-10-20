package io.bluewallet.blueberry

import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.wallet.Wallet

/** Persist whether a UTXO is frozen. Refreshes wallet txs snapshot. */
fun setUtxoFrozen(
    db: Database,
    wallet: Wallet,
    walletTxsStore: WalletTxsStore,
    outpoint: String,
    frozen: Boolean,
) {
    if (frozen) {
        db.utxoFrozen.freeze(outpoint)
    } else {
        db.utxoFrozen.unfreeze(outpoint)
    }
    val at = nowMillis()
    walletTxsStore.apply(snapshotFromDb(db, at, at, wallet))
}
