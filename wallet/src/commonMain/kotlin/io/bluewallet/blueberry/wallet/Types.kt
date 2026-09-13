package io.bluewallet.blueberry.wallet

enum class AddressScriptType {
    P2PKH,
    P2SH_P2WPKH,
    P2WPKH,
    P2TR,
}

fun AddressScriptType.wireName(): String =
    when (this) {
        AddressScriptType.P2PKH -> "p2pkh"
        AddressScriptType.P2SH_P2WPKH -> "p2sh-p2wpkh"
        AddressScriptType.P2WPKH -> "p2wpkh"
        AddressScriptType.P2TR -> "p2tr"
    }

fun addressScriptTypeFromWire(value: String): AddressScriptType =
    when (value) {
        "p2pkh" -> AddressScriptType.P2PKH
        "p2sh-p2wpkh" -> AddressScriptType.P2SH_P2WPKH
        "p2wpkh" -> AddressScriptType.P2WPKH
        "p2tr" -> AddressScriptType.P2TR
        else -> error("unsupported script type $value")
    }

enum class WatchWalletKind { BIP84, WIF, ADDRESS }

data class WatchAddress(
    val path: String,
    val index: Int,
    val change: Boolean,
    val address: String,
    val scriptPubKey: ByteArray,
    val scriptType: AddressScriptType? = null,
)

data class WatchWallet(
    val kind: WatchWalletKind,
    val secret: String,
    val addresses: List<WatchAddress>,
    val scripts: List<ByteArray>,
) {
    /** Redacts [secret] (mnemonic/WIF/zpub/address) so it never lands in logs or crash reports. */
    override fun toString(): String = "WatchWallet(kind=$kind, secret=[redacted], addresses=$addresses, scripts=$scripts)"
}

data class WatchGaps(
    val external: Int,
    val internal: Int,
)

sealed class SendAmount {
    data class Exact(
        val sats: Long,
    ) : SendAmount()

    data object Max : SendAmount()
}

fun WatchAddress.resolvedScriptType(): AddressScriptType = scriptType ?: AddressScriptType.P2WPKH
