package io.bluewallet.blueberry.parse

import fr.acinq.bitcoin.Transaction
import io.bluewallet.blueberry.storage.PrivateSendCoin
import io.bluewallet.blueberry.wallet.hexToBytes
import io.bluewallet.blueberry.wallet.outputScriptFromAddress

fun inferPendingSendNetDelta(
    coins: List<PrivateSendCoin>,
    destination: String,
    txHex: String,
    keepAddresses: List<String> = emptyList(),
): Long {
    val destScript = runCatching { outputScriptFromAddress(destination) }.getOrNull()
    val keepScripts =
        keepAddresses.mapNotNull { address ->
            runCatching { outputScriptFromAddress(address) }.getOrNull()
        }
    val tx = Transaction.read(hexToBytes(txHex))
    val inputs = coins.sumOf { it.valueSats }
    val destInTx =
        destScript != null &&
            tx.txOut.any { out -> out.publicKeyScript.toByteArray().contentEquals(destScript) }
    val ours =
        tx.txOut.filter { out ->
            val script = out.publicKeyScript.toByteArray()
            when {
                keepScripts.any { script.contentEquals(it) } -> true
                destScript != null && script.contentEquals(destScript) -> false
                destInTx -> true
                else -> false
            }
        }
    return ours.sumOf { it.amount.toLong() } - inputs
}
