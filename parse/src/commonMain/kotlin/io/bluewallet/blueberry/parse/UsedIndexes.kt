package io.bluewallet.blueberry.parse

import fr.acinq.bitcoin.Transaction
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.StoredTx
import io.bluewallet.blueberry.wallet.Wallet
import io.bluewallet.blueberry.wallet.WatchWallet
import io.bluewallet.blueberry.wallet.WatchWalletKind
import io.bluewallet.blueberry.wallet.WifReceiveTxRow
import io.bluewallet.blueberry.wallet.compactFilterFrom
import io.bluewallet.blueberry.wallet.growWatchGapsIfNeeded
import io.bluewallet.blueberry.wallet.loadWatchGaps
import io.bluewallet.blueberry.wallet.resolveReceiveAddress
import io.bluewallet.blueberry.wallet.saveWatchGaps

fun usedWatchIndexes(
    txs: List<ByteArray>,
    wallet: WatchWallet,
): UsedWatchIndexes {
    val scriptToIndex = LinkedHashMap<String, Pair<Boolean, Int>>()
    for (addr in wallet.addresses) {
        scriptToIndex[scriptHex(addr.scriptPubKey)] = addr.change to addr.index
    }

    val externalUsed = mutableSetOf<Int>()
    val internalUsed = mutableSetOf<Int>()

    fun markUsed(
        change: Boolean,
        index: Int,
    ) {
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
        external = externalUsed.sorted(),
        internal = internalUsed.sorted(),
    )
}

fun receiveAddressFromWallet(
    wallet: WatchWallet,
    txs: List<StoredTx>,
): String? {
    val used = usedWatchIndexes(txs.map { it.tx }, wallet)
    val wifTxs = txs.map { WifReceiveTxRow(it.height, it.txIndex, it.tx) }
    return resolveReceiveAddress(wallet, used.external, wifTxs)?.address
}

fun snapshotReceiveAddress(
    db: Database,
    wallet: Wallet,
): String? {
    wallet.refresh()
    val txs = db.transactions.list()
    val unused = receiveAddressFromWallet(wallet.snapshot(), txs)
    if (unused != null) return unused
    val snap = wallet.snapshot()
    if (snap.kind != WatchWalletKind.BIP84) return null
    val used = usedWatchIndexes(txs.map { it.tx }, snap)
    val grown = growWatchGapsIfNeeded(loadWatchGaps(db), used.external, used.internal)
    if (!grown.grew) return null
    saveWatchGaps(db, grown.gaps)
    val fromHeight = compactFilterFrom(db) ?: db.transactions.minHeight()
    if (fromHeight != null) {
        db.filters.markUnscannedFrom(fromHeight)
        db.parsedBlocks.clearFrom(fromHeight)
    }
    return receiveAddressFromWallet(wallet.refresh(), txs)
}
