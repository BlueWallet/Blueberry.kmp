package io.bluewallet.blueberry

import fr.acinq.bitcoin.Transaction
import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.parse.CHANGE_FROM_PREFIX
import io.bluewallet.blueberry.parse.outpointKey
import io.bluewallet.blueberry.parse.scriptHex
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.TxPaymentLabelRow
import io.bluewallet.blueberry.wallet.Wallet
import io.bluewallet.blueberry.wallet.hexToBytes

/** A paused draft is written only while it still has text. An empty draft waits for a finished clear. */
internal fun autosaveLabelDraft(
    draft: String,
    saved: String?,
): String? {
    val trimmed = draft.trim()
    if (saved == null || trimmed.isEmpty() || trimmed == saved.trim()) return null
    return draft
}

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
    val outputs = walletOutputs(db, wallet, txid)
    val names = outputs.names
    val hasChangeChain = wallet?.snapshot()?.addresses?.any { it.change } == true
    val targets = coinNamesToUpdate(names, previous, outputs.changeOutpoints, hasChangeChain)
    val derived = if (previous.isBlank()) null else "$CHANGE_FROM_PREFIX${previous.trim()}"
    editLabels(db) {
        if (trimmed.isEmpty()) {
            db.txPaymentLabels.delete(txid)
        } else {
            db.txPaymentLabels.upsert(TxPaymentLabelRow(txid, trimmed))
        }
        targets.forEach { outpoint ->
            if (trimmed.isEmpty()) {
                db.utxoNames.delete(outpoint)
            } else {
                val current = names[outpoint]?.trim().orEmpty()
                val next = if (current == derived) "$CHANGE_FROM_PREFIX$trimmed" else trimmed
                db.utxoNames.upsert(outpoint, next)
            }
        }
    }
    val at = nowMillis()
    walletTxsStore.applyFromDb(db, snapshotFromDb(db, at, at, wallet, walletTxsStore.get().linkageCache()))
}

private class WalletOutputs(
    val names: Map<String, String?>,
    val changeOutpoints: Set<String>,
)

private fun walletOutputs(
    db: Database,
    wallet: Wallet?,
    txid: String,
): WalletOutputs {
    val empty = WalletOutputs(emptyMap(), emptySet())
    val addresses = wallet?.snapshot()?.addresses.orEmpty()
    val scripts = addresses.map { scriptHex(it.scriptPubKey) }.toSet()
    val changeScripts = addresses.filter { it.change }.map { scriptHex(it.scriptPubKey) }.toSet()
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
    if (tx == null || scripts.isEmpty()) return empty
    val ours =
        tx.txOut.mapIndexedNotNull { vout, output ->
            val script = scriptHex(output.publicKeyScript.toByteArray())
            if (script !in scripts) {
                null
            } else {
                val key = outpointKey(txid, vout)
                Triple(key, names[key], script in changeScripts)
            }
        }
    return WalletOutputs(
        names = ours.associate { it.first to it.second },
        changeOutpoints = ours.mapNotNull { if (it.third) it.first else null }.toSet(),
    )
}
