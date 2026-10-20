package io.bluewallet.blueberry.parse

import io.bluewallet.blueberry.wallet.AddressScriptType
import io.bluewallet.blueberry.wallet.WatchAddress
import io.bluewallet.blueberry.wallet.WatchWallet
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

/** The receive address and, for HD wallets, the chain it sits on; both come from one pass over the stored txs. */
data class ReceiveSnapshot(
    val address: String?,
    val chain: ReceiveChain?,
)

/** Chain of [type] external addresses positioned at [start]; null if [start] isn't on it. */
internal fun receiveChain(
    watch: WatchWallet,
    type: AddressScriptType,
    usedExternal: List<Int>,
    start: String?,
): ReceiveChain? {
    val addresses =
        watch.addresses
            .filter { !it.change && it.resolvedScriptType() == type }
            .sortedBy { it.index }
    val startIndex = addresses.indexOfFirst { it.address == start }.takeIf { it >= 0 } ?: return null
    return ReceiveChain(addresses, usedExternal.toSet(), startIndex)
}
