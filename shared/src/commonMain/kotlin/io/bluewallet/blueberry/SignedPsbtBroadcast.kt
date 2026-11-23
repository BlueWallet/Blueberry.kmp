package io.bluewallet.blueberry

import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.parse.movePaymentLabel
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.SendRow
import io.bluewallet.blueberry.wallet.SignedPsbt

const val SIGNED_PSBT_GONE = "This payment is no longer waiting to be signed."

sealed class SignedPsbtSent {
    data class Started(
        val id: String,
    ) : SignedPsbtSent()

    data class Refused(
        val reason: String,
    ) : SignedPsbtSent()
}

/**
 * Replaces the stored unsigned send [storedTxid] with its signed transaction, keeping its coins held.
 * Segwit-only spends keep their txid. Legacy and nested-segwit signatures change it, so the row and its
 * note move to the signed txid. False when the payment is gone (forgotten or confirmed), including when the
 * signed transaction turns out to be in a block already. Runs off the UI thread.
 */
fun storeSignedPsbt(
    db: Database,
    runtime: PeersRuntime,
    storedTxid: String,
    signed: SignedPsbt,
): Boolean {
    val row = openSend(db, storedTxid) ?: openSend(db, signed.txid) ?: return false
    if (storedTxid == signed.txid) {
        db.sends.upsert(row.copy(txHex = signed.txHex))
    } else {
        // The row, its note and its change names move together. A row already on the signed txid is a move that
        // was cut short before; copying the labels again repairs it (movePaymentLabel never overwrites).
        editLabels(db) {
            db.transaction {
                if (row.txid != signed.txid) db.sends.delete(row.txid)
                db.sends.upsert(row.copy(txid = signed.txid, txHex = signed.txHex))
                movePaymentLabel(db, storedTxid, signed.txid)
            }
        }
    }
    runtime.storedHolds.clear()
    hydrateWallet(db, runtime.walletTxsStore, runtime.wallet, nowMillis())
    // Hydrating settles the row when the signed txid is already in a block, so it's no longer waiting.
    return openSend(db, signed.txid) != null
}

private fun openSend(
    db: Database,
    txid: String,
): SendRow? = db.sends.get(txid)?.takeIf { it.confirmedInBlock == 0L }

/**
 * Broadcasts a payment [storeSignedPsbt] stored, through the same path as Broadcast again: the job starts
 * handed off, since the signing device may have published it too, so no outcome frees its coins.
 */
fun startSignedPsbtBroadcast(
    runtime: PeersRuntime,
    db: Database,
    signed: SignedPsbt,
    receipt: BroadcastReceipt,
): SignedPsbtSent {
    val blocked = sendStoredAgain(runtime, db, signed.txid, receipt.copy(txid = signed.txid))
    val id = storedSendSession(runtime.broadcastStore, signed.txid)?.snapshot?.id
    return when {
        blocked != null -> SignedPsbtSent.Refused(blocked)
        id == null -> SignedPsbtSent.Refused(BROADCAST_BUSY)
        else -> {
            // Like a hot-wallet send, so transaction details name Tor as how it was published.
            db.broadcastVia.upsert(signed.txid, BROADCAST_VIA_TOR)
            SignedPsbtSent.Started(id)
        }
    }
}
