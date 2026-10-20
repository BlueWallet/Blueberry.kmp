package io.bluewallet.blueberry

import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.wallet.Wallet

/** Persist a UTXO label. Trim; empty clears. Patches the live snapshot. */
@Suppress("UnusedParameter")
fun setUtxoName(
    db: Database,
    wallet: Wallet,
    walletTxsStore: WalletTxsStore,
    outpoint: String,
    name: String,
) {
    val trimmed = name.trim()
    editLabels(db) {
        if (trimmed.isEmpty()) {
            db.utxoNames.delete(outpoint)
        } else {
            db.utxoNames.upsert(outpoint, trimmed)
        }
    }
    patchUtxoRow(walletTxsStore, outpoint) { it.copy(name = trimmed.ifEmpty { null }) }
}
