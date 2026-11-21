package io.bluewallet.blueberry.wallet

import kotlin.random.Random

data class MnemonicChangePick(
    val scriptType: AddressScriptType,
    val index: Int,
    val address: WatchAddress?,
)

fun pickMnemonicChange(
    wallet: WatchWallet,
    usedInternalByType: Map<AddressScriptType, List<Int>>,
    random: Random,
): MnemonicChangePick {
    val types = parseWalletSecret(wallet.secret).hdScriptTypes()
    if (types.isEmpty()) error("pickMnemonicChange requires a mnemonic or watch-key wallet")
    val scriptType = types[random.nextInt(types.size)]
    val index = firstUnusedIndex(usedInternalByType[scriptType] ?: emptyList())
    val address =
        wallet.addresses.firstOrNull {
            it.change && it.resolvedScriptType() == scriptType && it.index == index
        }
    return MnemonicChangePick(scriptType, index, address)
}
