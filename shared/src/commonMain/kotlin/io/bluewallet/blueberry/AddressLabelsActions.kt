package io.bluewallet.blueberry

import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.wallet.Wallet

/** Persist a label for one of our addresses. Trim; empty clears. Marks labels dirty for sync and refreshes the tx list. */
fun setAddressLabel(
    db: Database,
    wallet: Wallet?,
    walletTxsStore: WalletTxsStore,
    address: String,
    label: String,
) {
    val trimmed = label.trim()
    editLabels(db) {
        if (trimmed.isEmpty()) {
            db.addressLabels.delete(address)
        } else {
            db.addressLabels.upsert(address, trimmed)
        }
    }
    val at = nowMillis()
    walletTxsStore.applyFromDb(db, snapshotFromDb(db, at, at, wallet))
}
