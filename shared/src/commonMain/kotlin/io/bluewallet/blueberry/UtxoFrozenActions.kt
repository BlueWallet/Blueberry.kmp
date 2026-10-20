package io.bluewallet.blueberry

import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.wallet.Wallet

/** Persist whether a UTXO is frozen and refresh stored metadata on the live snapshot; linkage is unchanged. */
@Suppress("UnusedParameter")
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
    walletTxsStore.update { withStoredUtxoMeta(db, it) }
}
