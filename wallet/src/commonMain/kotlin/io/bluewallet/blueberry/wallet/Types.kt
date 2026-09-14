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

fun WatchWallet.hd(
    type: AddressScriptType,
    index: Int,
    change: Boolean = false,
): WatchAddress = addresses.first { it.resolvedScriptType() == type && it.index == index && it.change == change }

data class WatchGaps(
    val external: Int,
    val internal: Int,
)

val HD_SCRIPT_TYPES =
    listOf(
        AddressScriptType.P2PKH,
        AddressScriptType.P2SH_P2WPKH,
        AddressScriptType.P2WPKH,
        AddressScriptType.P2TR,
    )

fun AddressScriptType.accountPath(): String =
    when (this) {
        AddressScriptType.P2PKH -> BIP44_ACCOUNT_PATH
        AddressScriptType.P2SH_P2WPKH -> BIP49_ACCOUNT_PATH
        AddressScriptType.P2WPKH -> BIP84_ACCOUNT_PATH
        AddressScriptType.P2TR -> BIP86_ACCOUNT_PATH
    }

fun AddressScriptType.watchKeys(): Pair<String, String> =
    when (this) {
        AddressScriptType.P2PKH -> WATCH_EXTERNAL_P2PKH_KEY to WATCH_INTERNAL_P2PKH_KEY
        AddressScriptType.P2SH_P2WPKH -> WATCH_EXTERNAL_P2SH_P2WPKH_KEY to WATCH_INTERNAL_P2SH_P2WPKH_KEY
        AddressScriptType.P2WPKH -> WATCH_EXTERNAL_P2WPKH_KEY to WATCH_INTERNAL_P2WPKH_KEY
        AddressScriptType.P2TR -> WATCH_EXTERNAL_P2TR_KEY to WATCH_INTERNAL_P2TR_KEY
    }

fun AddressScriptType.receiveLabel(): String =
    when (this) {
        AddressScriptType.P2PKH -> "Legacy (BIP44)"
        AddressScriptType.P2SH_P2WPKH -> "Nested SegWit (BIP49)"
        AddressScriptType.P2WPKH -> "Native SegWit (BIP84)"
        AddressScriptType.P2TR -> "Taproot (BIP86)"
    }

data class HdWatchGaps(
    val p2pkh: WatchGaps,
    val p2shP2wpkh: WatchGaps,
    val p2wpkh: WatchGaps,
    val p2tr: WatchGaps,
) {
    operator fun get(type: AddressScriptType): WatchGaps =
        when (type) {
            AddressScriptType.P2PKH -> p2pkh
            AddressScriptType.P2SH_P2WPKH -> p2shP2wpkh
            AddressScriptType.P2WPKH -> p2wpkh
            AddressScriptType.P2TR -> p2tr
        }

    fun with(
        type: AddressScriptType,
        gaps: WatchGaps,
    ): HdWatchGaps =
        when (type) {
            AddressScriptType.P2PKH -> copy(p2pkh = gaps)
            AddressScriptType.P2SH_P2WPKH -> copy(p2shP2wpkh = gaps)
            AddressScriptType.P2WPKH -> copy(p2wpkh = gaps)
            AddressScriptType.P2TR -> copy(p2tr = gaps)
        }

    fun mergeMax(other: HdWatchGaps): HdWatchGaps {
        var out = this
        for (type in HD_SCRIPT_TYPES) {
            val a = out[type]
            val b = other[type]
            out = out.with(type, WatchGaps(maxOf(a.external, b.external), maxOf(a.internal, b.internal)))
        }
        return out
    }

    companion object {
        fun uniform(gaps: WatchGaps): HdWatchGaps = HdWatchGaps(gaps, gaps, gaps, gaps)

        fun initial(): HdWatchGaps = uniform(WatchGaps(INITIAL_WATCH_COUNT, INITIAL_WATCH_COUNT))
    }
}

data class UsedChainIndexes(
    val external: List<Int> = emptyList(),
    val internal: List<Int> = emptyList(),
)

data class UsedHdIndexes(
    val byType: Map<AddressScriptType, UsedChainIndexes> = emptyMap(),
) {
    fun get(type: AddressScriptType): UsedChainIndexes = byType[type] ?: UsedChainIndexes()
}

sealed class SendAmount {
    data class Exact(
        val sats: Long,
    ) : SendAmount()

    data object Max : SendAmount()
}

fun WatchAddress.resolvedScriptType(): AddressScriptType = scriptType ?: AddressScriptType.P2WPKH
