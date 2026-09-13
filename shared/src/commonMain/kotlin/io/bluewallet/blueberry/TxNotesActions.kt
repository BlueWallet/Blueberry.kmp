package io.bluewallet.blueberry

import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.TxPaymentLabelRow
import io.bluewallet.blueberry.wallet.Wallet

fun setTxNote(
    db: Database,
    wallet: Wallet?,
    walletTxsStore: WalletTxsStore,
    txid: String,
    note: String,
) {
    val trimmed = note.trim()
    if (trimmed.isEmpty()) {
        db.txPaymentLabels.delete(txid)
    } else {
        db.txPaymentLabels.upsert(TxPaymentLabelRow(txid, trimmed))
    }
    val at = nowMillis()
    walletTxsStore.apply(snapshotFromDb(db, at, at, wallet))
}
