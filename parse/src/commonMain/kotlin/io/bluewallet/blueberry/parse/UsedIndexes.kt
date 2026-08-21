package io.bluewallet.blueberry.parse

import fr.acinq.bitcoin.Transaction
import io.bluewallet.blueberry.wallet.WatchWallet

fun usedWatchIndexes(
    txs: List<ByteArray>,
    wallet: WatchWallet,
): UsedWatchIndexes {
    val scriptToIndex = LinkedHashMap<String, Pair<Boolean, Int>>()
    for (addr in wallet.addresses) {
        scriptToIndex[scriptHex(addr.scriptPubKey)] = addr.change to addr.index
    }

    val externalUsed = sortedSetOf<Int>()
    val internalUsed = sortedSetOf<Int>()

    fun markUsed(change: Boolean, index: Int) {
        if (change) internalUsed.add(index) else externalUsed.add(index)
    }

    val watchOutpoints = mutableMapOf<String, Pair<Boolean, Int>>()
    val decodedTxs = txs.map { Transaction.read(it) }
    for (decoded in decodedTxs) {
        val txid = decoded.txid.toString()
        decoded.txOut.forEachIndexed { vout, o ->
            val info = scriptToIndex[scriptHex(o.publicKeyScript.toByteArray())]
            if (info != null) watchOutpoints[outpointKey(txid, vout)] = info
        }
    }

    for (tx in decodedTxs) {
        for (outp in tx.txOut) {
            val info = scriptToIndex[scriptHex(outp.publicKeyScript.toByteArray())]
            if (info != null) markUsed(info.first, info.second)
        }
        if (!tx.isCoinbase()) {
            for (inn in tx.txIn) {
                val outInfo = watchOutpoints[prevoutKey(inn)]
                if (outInfo != null) markUsed(outInfo.first, outInfo.second)
                for (script in watchedScriptsFromInput(inn)) {
                    val info = scriptToIndex[scriptHex(script)]
                    if (info != null) markUsed(info.first, info.second)
                }
            }
        }
    }

    return UsedWatchIndexes(
        external = externalUsed.toList(),
        internal = internalUsed.toList(),
    )
}
