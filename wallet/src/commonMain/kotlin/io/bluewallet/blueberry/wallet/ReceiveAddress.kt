package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.Transaction

fun firstUnusedExternalAddress(wallet: WatchWallet, usedExternal: List<Int>): WatchAddress? {
    val used = usedExternal.toSet()
    return wallet.addresses
        .filter { !it.change }
        .sortedBy { it.index }
        .firstOrNull { it.index !in used }
}

fun firstUnusedInternalAddress(wallet: WatchWallet, usedInternal: List<Int>): WatchAddress? {
    val used = usedInternal.toSet()
    return wallet.addresses
        .filter { it.change }
        .sortedBy { it.index }
        .firstOrNull { it.index !in used }
}

data class WifReceiveTxRow(
    val height: Int,
    val txIndex: Int,
    val tx: ByteArray,
)

fun preferredWifReceiveAddress(
    wallet: WatchWallet,
    txs: List<WifReceiveTxRow>,
): WatchAddress {
    require(wallet.kind == WatchWalletKind.WIF) {
        "preferredWifReceiveAddress requires a WIF wallet"
    }
    val byScript = wallet.addresses.associateBy { scriptHex(it.scriptPubKey) }
    val native = wallet.addresses.firstOrNull { it.scriptType == AddressScriptType.P2WPKH }
        ?: error("WIF wallet missing native segwit address")

    data class DecodedRow(val height: Int, val txIndex: Int, val decoded: Transaction)

    val decodedRows = txs.map { row ->
        DecodedRow(row.height, row.txIndex, Transaction.read(row.tx))
    }

    val watchOutpoints = mutableMapOf<String, WatchAddress>()
    for (row in decodedRows) {
        val txid = row.decoded.txid.toString()
        row.decoded.txOut.forEachIndexed { vout, out ->
            byScript[scriptHex(out.publicKeyScript.toByteArray())]?.let { hit ->
                watchOutpoints[outpointKey(txid, vout)] = hit
            }
        }
    }

    val ordered = decodedRows.sortedWith(compareBy({ it.height }, { it.txIndex }))

    for (row in ordered) {
        val decoded = row.decoded
        for (out in decoded.txOut) {
            byScript[scriptHex(out.publicKeyScript.toByteArray())]?.let { return it }
        }
        if (!Transaction.isCoinbase(decoded)) {
            for (inn in decoded.txIn) {
                val hashBytes = inn.outPoint.hash.value.toByteArray()
                watchOutpoints[outpointKey(prevoutTxidDisplay(hashBytes), inn.outPoint.index.toInt())]
                    ?.let { return it }
            }
        }
    }
    return native
}

/** Same rules as helix3 `snapshotReceiveAddress`: unused external, else WIF preferred, else imported. */
fun resolveReceiveAddress(
    wallet: WatchWallet,
    usedExternal: List<Int> = emptyList(),
    wifTxs: List<WifReceiveTxRow> = emptyList(),
): WatchAddress? {
    return when (wallet.kind) {
        WatchWalletKind.BIP84 -> firstUnusedExternalAddress(wallet, usedExternal)
        WatchWalletKind.WIF -> preferredWifReceiveAddress(wallet, wifTxs)
        WatchWalletKind.ADDRESS -> wallet.addresses.firstOrNull()
    }
}
