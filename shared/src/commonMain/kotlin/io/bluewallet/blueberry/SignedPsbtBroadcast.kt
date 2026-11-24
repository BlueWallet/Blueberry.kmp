package io.bluewallet.blueberry

import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.parse.movePaymentLabel
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.SendRow
import io.bluewallet.blueberry.wallet.SignedPsbt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

const val SIGNED_PSBT_GONE = "This payment is no longer waiting to be signed."
const val SIGNED_PSBT_NOT_STORED = "The signed payment couldn't be saved. Try again."
const val SIGNED_PSBT_NOT_STARTED = "The signed payment is saved, but its broadcast couldn't start. Try again."

sealed class SignedPsbtSent {
    data class Started(
        val id: String,
    ) : SignedPsbtSent()

    /** [stored]: the signed payment was stored (its row may have moved to the signed txid) before the refusal. */
    data class Refused(
        val reason: String,
        val stored: Boolean = false,
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
 * Broadcasts a payment [storeSignedPsbt] stored, through the same path as Broadcast again. Its stored row holds
 * its coins whatever the job hears, since the signing device may have published it too. No run of ours has
 * given nodes it yet, so a run that reaches none ends as not sent.
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
        // Tor is recorded once a node is given it (the runtime's hand-off hook), not here: this run may reach none.
        else -> SignedPsbtSent.Started(id)
    }
}

/**
 * Stores the signed payment, then starts its broadcast, as one step its caller can't cancel halfway: a stored
 * (possibly moved) row always reaches the start. A throw becomes a refusal that says whether the row was stored.
 */
suspend fun broadcastSignedPsbt(
    db: Database,
    runtime: PeersRuntime,
    storedTxid: String,
    signed: SignedPsbt,
    receipt: BroadcastReceipt,
): SignedPsbtSent =
    withContext(NonCancellable) {
        runCatching {
            val stored = withContext(Dispatchers.Default) { storeSignedPsbt(db, runtime, storedTxid, signed) }
            when {
                // Hydrating may have found the stored row confirmed: it holds the signed payment, so Back leaves to it.
                !stored -> SignedPsbtSent.Refused(SIGNED_PSBT_GONE, signedRowStored(db, signed))
                else ->
                    when (val sent = startSignedPsbtBroadcast(runtime, db, signed, receipt)) {
                        is SignedPsbtSent.Refused -> sent.copy(stored = true)
                        is SignedPsbtSent.Started -> sent
                    }
            }
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            // The row commits before hydrating and starting, so a later throw leaves it stored: say so, and Back leaves.
            val stored = runCatching { signedRowStored(db, signed) }.getOrDefault(false)
            SignedPsbtSent.Refused(if (stored) SIGNED_PSBT_NOT_STARTED else SIGNED_PSBT_NOT_STORED, stored)
        }
    }

/** The signed payment's row exists on its txid, open or already confirmed. */
private fun signedRowStored(
    db: Database,
    signed: SignedPsbt,
): Boolean = db.sends.get(signed.txid)?.txHex == signed.txHex
