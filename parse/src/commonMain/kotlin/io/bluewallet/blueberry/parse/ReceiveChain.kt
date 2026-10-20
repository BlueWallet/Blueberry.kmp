package io.bluewallet.blueberry.parse

import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.wallet.Wallet
import io.bluewallet.blueberry.wallet.WatchAddress
import io.bluewallet.blueberry.wallet.WatchWalletKind
import io.bluewallet.blueberry.wallet.lastReceiveIndex
import io.bluewallet.blueberry.wallet.resolvedScriptType

/** External addresses (with their full derivation paths) of the receive script type, by index, plus the indexes any stored tx touched. */
data class ReceiveChain(
    val addresses: List<WatchAddress>,
    val used: Set<Int>,
    val start: Int,
) {
    val lastIndex: Int get() = lastReceiveIndex(used, addresses.size)
}

/** Chain for stepping through receive addresses from [start]; null for WIF/address wallets or an address off the chain. */
fun receiveChain(
    db: Database,
    wallet: Wallet,
    start: String,
): ReceiveChain? {
    val watch = wallet.snapshot().takeIf { it.kind == WatchWalletKind.BIP84 } ?: return null
    val type = receiveScriptType(db, watch)
    val addresses =
        watch.addresses
            .filter { !it.change && it.resolvedScriptType() == type }
            .sortedBy { it.index }
    return addresses.indexOfFirst { it.address == start }.takeIf { it >= 0 }?.let { startIndex ->
        val used = usedWatchIndexes(db.transactions.list().map { it.tx }, watch).get(type).external
        ReceiveChain(addresses, used.toSet(), startIndex)
    }
}
