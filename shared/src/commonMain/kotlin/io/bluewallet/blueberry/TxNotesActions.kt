package io.bluewallet.blueberry

import fr.acinq.bitcoin.Transaction
import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.parse.outpointKey
import io.bluewallet.blueberry.parse.scriptHex
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.TxPaymentLabelRow
import io.bluewallet.blueberry.wallet.Wallet
import io.bluewallet.blueberry.wallet.hexToBytes

fun setTxNote(
    db: Database,
    wallet: Wallet?,
    walletTxsStore: WalletTxsStore,
    txid: String,
    note: String,
) {
    val previous =
        db.txPaymentLabels
            .get(txid)
            ?.label
            .orEmpty()
    val trimmed = note.trim()
    val targets = coinNamesToUpdate(walletOutputNames(db, wallet, txid), previous)
    editLabels(db) {
        if (trimmed.isEmpty()) {
            db.txPaymentLabels.delete(txid)
        } else {
            db.txPaymentLabels.upsert(TxPaymentLabelRow(txid, trimmed))
        }
        targets.forEach { outpoint ->
            if (trimmed.isEmpty()) db.utxoNames.delete(outpoint) else db.utxoNames.upsert(outpoint, trimmed)
        }
    }
    val at = nowMillis()
    walletTxsStore.applyFromDb(db, snapshotFromDb(db, at, at, wallet, walletTxsStore.get().linkageCache()))
}

private fun walletOutputNames(
    db: Database,
    wallet: Wallet?,
    txid: String,
): Map<String, String?> {
    val scripts =
        wallet
            ?.snapshot()
            ?.addresses
            ?.map { scriptHex(it.scriptPubKey) }
            ?.toSet()
            .orEmpty()
    val bytes =
        db.transactions.get(txid)?.tx
            ?: db.sends
                .get(txid)
                ?.txHex
                ?.let(::hexToBytes)
            ?: db.privateSends
                .get(txid)
                ?.txHex
                ?.let(::hexToBytes)
    val tx = bytes?.let { runCatching { Transaction.read(it) }.getOrNull() }
    val names = db.utxoNames.list().associate { it.outpoint to it.name }
    if (tx == null || scripts.isEmpty()) return emptyMap()
    return tx.txOut
        .mapIndexedNotNull { vout, output ->
            val key = outpointKey(txid, vout)
            if (scriptHex(output.publicKeyScript.toByteArray()) in scripts) key to names[key] else null
        }.toMap()
}
