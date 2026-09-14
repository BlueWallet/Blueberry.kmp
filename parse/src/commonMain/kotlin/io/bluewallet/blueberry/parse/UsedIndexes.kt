package io.bluewallet.blueberry.parse

import fr.acinq.bitcoin.Transaction
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.StoredTx
import io.bluewallet.blueberry.wallet.AddressScriptType
import io.bluewallet.blueberry.wallet.MAX_WATCH_COUNT
import io.bluewallet.blueberry.wallet.UsedChainIndexes
import io.bluewallet.blueberry.wallet.UsedHdIndexes
import io.bluewallet.blueberry.wallet.Wallet
import io.bluewallet.blueberry.wallet.WalletSecretKind
import io.bluewallet.blueberry.wallet.WatchGaps
import io.bluewallet.blueberry.wallet.WatchWallet
import io.bluewallet.blueberry.wallet.WatchWalletKind
import io.bluewallet.blueberry.wallet.WifReceiveTxRow
import io.bluewallet.blueberry.wallet.compactFilterFrom
import io.bluewallet.blueberry.wallet.firstUnusedExternalAddress
import io.bluewallet.blueberry.wallet.firstUnusedIndex
import io.bluewallet.blueberry.wallet.growWatchGapsIfNeeded
import io.bluewallet.blueberry.wallet.loadHdWatchGaps
import io.bluewallet.blueberry.wallet.loadReceiveScriptType
import io.bluewallet.blueberry.wallet.parseWalletSecret
import io.bluewallet.blueberry.wallet.resolveReceiveAddress
import io.bluewallet.blueberry.wallet.resolvedScriptType
import io.bluewallet.blueberry.wallet.saveWatchGaps

private typealias WatchIndex = Triple<AddressScriptType, Boolean, Int>

private fun scriptToWatchIndex(wallet: WatchWallet): Map<String, WatchIndex> =
    wallet.addresses.associate { address ->
        scriptHex(address.scriptPubKey) to
            WatchIndex(address.resolvedScriptType(), address.change, address.index)
    }

private fun watchOutpoints(
    txs: List<Transaction>,
    scriptToIndex: Map<String, WatchIndex>,
): Map<String, WatchIndex> {
    val result = mutableMapOf<String, WatchIndex>()
    for (tx in txs) {
        val txid = tx.txid.toString()
        tx.txOut.forEachIndexed { vout, output ->
            val index = scriptToIndex[scriptHex(output.publicKeyScript.toByteArray())]
            if (index != null) result[outpointKey(txid, vout)] = index
        }
    }
    return result
}

private fun markWatchIndex(
    index: WatchIndex?,
    external: MutableMap<AddressScriptType, MutableSet<Int>>,
    internal: MutableMap<AddressScriptType, MutableSet<Int>>,
) {
    if (index == null) return
    val destination = if (index.second) internal else external
    destination.getOrPut(index.first) { mutableSetOf() }.add(index.third)
}

private fun markTransactionIndexes(
    tx: Transaction,
    scriptToIndex: Map<String, WatchIndex>,
    watchOutpoints: Map<String, WatchIndex>,
    external: MutableMap<AddressScriptType, MutableSet<Int>>,
    internal: MutableMap<AddressScriptType, MutableSet<Int>>,
) {
    for (output in tx.txOut) {
        markWatchIndex(
            scriptToIndex[scriptHex(output.publicKeyScript.toByteArray())],
            external,
            internal,
        )
    }
    if (tx.isCoinbase()) return
    for (input in tx.txIn) {
        markWatchIndex(watchOutpoints[prevoutKey(input)], external, internal)
        for (script in watchedScriptsFromInput(input)) {
            markWatchIndex(scriptToIndex[scriptHex(script)], external, internal)
        }
    }
}

fun usedWatchIndexes(
    txs: List<ByteArray>,
    wallet: WatchWallet,
): UsedHdIndexes = usedWatchIndexesFromTxs(txs.map { Transaction.read(it) }, wallet)

internal fun usedWatchIndexesFromTxs(
    txs: List<Transaction>,
    wallet: WatchWallet,
): UsedHdIndexes {
    val scriptToIndex = scriptToWatchIndex(wallet)
    val external = mutableMapOf<AddressScriptType, MutableSet<Int>>()
    val internal = mutableMapOf<AddressScriptType, MutableSet<Int>>()
    val watchOutpoints = watchOutpoints(txs, scriptToIndex)
    for (tx in txs) {
        markTransactionIndexes(tx, scriptToIndex, watchOutpoints, external, internal)
    }

    val types = external.keys + internal.keys
    return UsedHdIndexes(
        types.associateWith { type ->
            UsedChainIndexes(
                external = external[type]?.sorted().orEmpty(),
                internal = internal[type]?.sorted().orEmpty(),
            )
        },
    )
}

fun receiveAddressFromWallet(
    wallet: WatchWallet,
    txs: List<StoredTx>,
    receiveType: AddressScriptType = AddressScriptType.P2WPKH,
): String? {
    val used = usedWatchIndexes(txs.map { it.tx }, wallet)
    val wifTxs = txs.map { WifReceiveTxRow(it.height, it.txIndex, it.tx) }
    return resolveReceiveAddress(wallet, used.get(receiveType).external, wifTxs, receiveType)?.address
}

fun snapshotReceiveAddress(
    db: Database,
    wallet: Wallet,
): String? {
    var watch = wallet.refresh()
    val txs = db.transactions.list()
    val receiveType =
        if (parseWalletSecret(watch.secret).kind == WalletSecretKind.MNEMONIC) {
            loadReceiveScriptType(db)
        } else {
            AddressScriptType.P2WPKH
        }
    if (watch.kind != WatchWalletKind.BIP84) {
        return receiveAddressFromWallet(watch, txs, receiveType)
    }
    val decoded = txs.map { Transaction.read(it.tx) }
    var current = loadHdWatchGaps(db)[receiveType]
    var grew = false
    while (true) {
        val used = usedWatchIndexesFromTxs(decoded, watch).get(receiveType)
        val unused = firstUnusedExternalAddress(watch, used.external, receiveType)
        if (unused != null) {
            if (grew) queueReceiveRescan(db)
            return unused.address
        }
        val gapGrown = growWatchGapsIfNeeded(current, used.external, used.internal)
        val nextExternal =
            minOf(maxOf(current.external, firstUnusedIndex(used.external) + 1, gapGrown.gaps.external), MAX_WATCH_COUNT)
        if (nextExternal <= current.external) {
            if (grew) queueReceiveRescan(db)
            return watch.addresses
                .filter { !it.change && it.resolvedScriptType() == receiveType }
                .maxByOrNull { it.index }
                ?.address
        }
        db.transaction {
            val latest = loadHdWatchGaps(db)[receiveType]
            val merged =
                WatchGaps(
                    maxOf(latest.external, nextExternal),
                    maxOf(latest.internal, gapGrown.gaps.internal),
                )
            saveWatchGaps(db, receiveType, merged)
            current = merged
        }
        grew = true
        watch = wallet.refresh()
    }
}

private fun queueReceiveRescan(db: Database) {
    val fromHeight = compactFilterFrom(db) ?: db.transactions.minHeight()
    if (fromHeight != null) {
        db.filters.markUnscannedFrom(fromHeight)
        db.parsedBlocks.clearFrom(fromHeight)
    }
}
