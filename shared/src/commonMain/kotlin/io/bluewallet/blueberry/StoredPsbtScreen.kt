package io.bluewallet.blueberry

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import io.bluewallet.blueberry.parse.storedSendPsbt
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.wallet.Wallet
import io.bluewallet.blueberry.wallet.isSignedTxHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val NO_STORED_PSBT = "This payment has no PSBT left to sign."

internal sealed class StoredPsbt {
    data class Ready(
        val to: SendRecipient,
        val psbtHex: String,
    ) : StoredPsbt()

    data class Failed(
        val reason: String,
    ) : StoredPsbt()
}

/** True for an unconfirmed send stored as a PSBT, which can be shown again for signing. */
fun storedSendIsPsbt(
    db: Database,
    txid: String,
): Boolean = db.sends.get(txid)?.let { it.confirmedInBlock == 0L && !isSignedTxHex(it.txHex) } == true

/** Rebuilds the PSBT of a stored unsigned send. Reads the database, so it runs off the UI thread. */
internal fun loadStoredPsbt(
    db: Database,
    wallet: Wallet?,
    txid: String,
): StoredPsbt {
    val row = db.sends.get(txid)?.takeIf { storedSendIsPsbt(db, txid) }
    return when {
        row == null -> StoredPsbt.Failed(NO_STORED_PSBT)
        wallet == null -> StoredPsbt.Failed(WALLET_NOT_LOADED)
        else -> {
            val to =
                SendRecipient(
                    row.destination,
                    db.txPaymentLabels
                        .get(txid)
                        ?.label
                        .orEmpty(),
                )
            runCatching { StoredPsbt.Ready(to, storedSendPsbt(db, wallet, row.txHex, row.coins)) }
                .getOrElse { StoredPsbt.Failed("The PSBT could not be rebuilt: ${it.message ?: it}") }
        }
    }
}

/** A stored PSBT shown again from its payment, so it can still be signed after Send closed. */
@Composable
internal fun StoredPsbtScreen(
    runtime: PeersRuntime,
    db: Database,
    txid: String,
    onBack: () -> Unit,
) {
    val state by produceState<StoredPsbt?>(null, txid, runtime.walletRevision) {
        value = withContext(Dispatchers.Default) { loadStoredPsbt(db, runtime.wallet, txid) }
    }
    when (val s = state) {
        is StoredPsbt.Ready -> SendPsbtStep(s.to, s.psbtHex, onDone = onBack, onBack = onBack)
        else ->
            SendStepFrame(title = "Sign elsewhere", caption = null, onBack = onBack, bottom = {}) {
                HelperText((s as? StoredPsbt.Failed)?.reason ?: "Preparing the PSBT…", error = s != null)
            }
    }
}
