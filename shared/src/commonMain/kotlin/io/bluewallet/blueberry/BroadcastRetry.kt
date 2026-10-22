package io.bluewallet.blueberry

import io.bluewallet.blueberry.broadcast.decodeBroadcastTx
import io.bluewallet.blueberry.wallet.outpointKey
import io.bluewallet.blueberry.wallet.prevoutTxidDisplay

/** Any finished failure can leave the list. A live or sent job stays. */
fun broadcastCanBeForgotten(phase: String?): Boolean = phase == "error"

fun liveBroadcastIds(store: BroadcastStore): Set<String> =
    store
        .sessions()
        .mapNotNull { session ->
            val id = session.snapshot.id
            if (id != null && broadcastJobInFlight(session.snapshot.phase)) id else null
        }.toSet()

const val RETRY_COINS_GONE = "Those coins are no longer in the wallet. This send cannot be retried."
const val RETRY_COINS_HELD = "Those coins are in another payment that can still confirm. This send cannot be retried."
const val RETRY_WALLET_LOADING = "The wallet is still loading. Wait, then try again."

/** Why Retry must not publish this transaction again, or null when its inputs are still free. */
fun retryRefusal(
    txHex: String?,
    utxoKeys: Set<String>,
    utxosReady: Boolean,
    reserved: Set<String>,
): String? {
    val inputs = if (utxosReady) txHex?.let(::txInputKeys) else null
    return when {
        !utxosReady -> RETRY_WALLET_LOADING
        inputs == null || inputs.any { it !in utxoKeys } -> RETRY_COINS_GONE
        inputs.any { it in reserved } -> RETRY_COINS_HELD
        else -> null
    }
}

private fun txInputKeys(txHex: String): Set<String>? =
    runCatching {
        decodeBroadcastTx(txHex)
            .inputs
            .map { input ->
                outpointKey(prevoutTxidDisplay(input.previousOutput.hash), input.previousOutput.index.toInt())
            }.toSet()
    }.getOrNull()
