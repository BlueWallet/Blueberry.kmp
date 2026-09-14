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
    val types =
        when (parseWalletSecret(wallet.secret).kind) {
            WalletSecretKind.MNEMONIC -> HD_SCRIPT_TYPES
            WalletSecretKind.ZPUB -> listOf(AddressScriptType.P2WPKH)
            else -> error("pickMnemonicChange requires a mnemonic or zpub wallet")
        }
    val scriptType = types[random.nextInt(types.size)]
    val index = firstUnusedIndex(usedInternalByType[scriptType] ?: emptyList())
    val address =
        wallet.addresses.firstOrNull {
            it.change && it.resolvedScriptType() == scriptType && it.index == index
        }
    return MnemonicChangePick(scriptType, index, address)
}
