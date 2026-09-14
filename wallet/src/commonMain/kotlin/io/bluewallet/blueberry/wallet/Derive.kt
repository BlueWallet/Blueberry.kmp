package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.XonlyPublicKey

private val WIF_SCRIPT_TYPES =
    listOf(
        AddressScriptType.P2PKH,
        AddressScriptType.P2SH_P2WPKH,
        AddressScriptType.P2WPKH,
        AddressScriptType.P2TR,
    )

private fun normalizeHdGaps(gaps: HdWatchGaps?): HdWatchGaps {
    if (gaps == null) return HdWatchGaps.initial()

    fun clip(g: WatchGaps) = WatchGaps(maxOf(0, g.external), maxOf(0, g.internal))
    return HdWatchGaps(clip(gaps.p2pkh), clip(gaps.p2shP2wpkh), clip(gaps.p2wpkh), clip(gaps.p2tr))
}

private fun scriptPubKeyForAddress(address: String): ByteArray = outputScriptFromAddress(address)

private fun p2pkhAddress(publicKey: PublicKey): String = Bitcoin.computeP2PkhAddress(publicKey, Block.LivenetGenesisBlock.hash)

private fun p2shP2wpkhAddress(publicKey: PublicKey): String = Bitcoin.computeP2ShOfP2WpkhAddress(publicKey, Block.LivenetGenesisBlock.hash)

private fun p2wpkhAddress(publicKey: PublicKey): String = Bitcoin.computeP2WpkhAddress(publicKey, Block.LivenetGenesisBlock.hash)

private fun p2trAddress(publicKey: PublicKey): String {
    val xOnly = XonlyPublicKey(publicKey)
    return Bitcoin.computeBIP86Address(xOnly, Block.LivenetGenesisBlock.hash)
}

private fun addressForType(
    scriptType: AddressScriptType,
    publicKey: PublicKey,
): String =
    when (scriptType) {
        AddressScriptType.P2PKH -> p2pkhAddress(publicKey)
        AddressScriptType.P2SH_P2WPKH -> p2shP2wpkhAddress(publicKey)
        AddressScriptType.P2WPKH -> p2wpkhAddress(publicKey)
        AddressScriptType.P2TR -> p2trAddress(publicKey)
    }

private fun deriveWifWatchWallet(wif: String): WatchWallet {
    val priv = decodeWifPrivateKey(wif)
    val publicKey = PrivateKey(priv).publicKey()

    val addresses =
        WIF_SCRIPT_TYPES.mapIndexed { index, scriptType ->
            val address =
                when (scriptType) {
                    AddressScriptType.P2PKH -> p2pkhAddress(publicKey)
                    AddressScriptType.P2SH_P2WPKH -> p2shP2wpkhAddress(publicKey)
                    AddressScriptType.P2WPKH -> p2wpkhAddress(publicKey)
                    AddressScriptType.P2TR -> p2trAddress(publicKey)
                }
            WatchAddress(
                path = "wif/${scriptType.wireName()}",
                index = index,
                change = false,
                address = address,
                scriptPubKey = scriptPubKeyForAddress(address),
                scriptType = scriptType,
            )
        }

    return WatchWallet(
        kind = WatchWalletKind.WIF,
        secret = wif,
        addresses = addresses,
        scripts = addresses.map { it.scriptPubKey },
    )
}

private fun deriveAddressWatchWallet(address: String): WatchWallet {
    val scriptPubKey = outputScriptFromAddress(address)
    val scriptType = watchAddressScriptType(address)
    val watchAddr =
        WatchAddress(
            path = "address/0",
            index = 0,
            change = false,
            address = address,
            scriptPubKey = scriptPubKey,
            scriptType = scriptType,
        )
    return WatchWallet(
        kind = WatchWalletKind.ADDRESS,
        secret = address,
        addresses = listOf(watchAddr),
        scripts = listOf(scriptPubKey),
    )
}

private fun deriveHdAddresses(
    scriptType: AddressScriptType,
    accountPath: String,
    gaps: WatchGaps,
    derivePublicKey: (String) -> PublicKey,
): List<WatchAddress> {
    val addresses = mutableListOf<WatchAddress>()
    val chains = listOf(false to gaps.external, true to gaps.internal)
    for ((change, count) in chains) {
        val chain = if (change) 1 else 0
        for (index in 0 until count) {
            val path = "$accountPath/$chain/$index"
            val address = addressForType(scriptType, derivePublicKey("m/$chain/$index"))
            addresses.add(
                WatchAddress(
                    path = path,
                    index = index,
                    change = change,
                    address = address,
                    scriptPubKey = scriptPubKeyForAddress(address),
                    scriptType = scriptType,
                ),
            )
        }
    }
    return addresses
}

private fun deriveHdWatchWallet(
    secret: String,
    kind: WalletSecretKind,
    gaps: HdWatchGaps,
): WatchWallet {
    val seed = if (kind == WalletSecretKind.MNEMONIC) MnemonicCode.toSeed(secret, "") else null
    val master = seed?.let { DeterministicWallet.generate(it) }
    val types =
        if (kind == WalletSecretKind.ZPUB) {
            listOf(AddressScriptType.P2WPKH)
        } else {
            HD_SCRIPT_TYPES
        }
    val addresses = mutableListOf<WatchAddress>()
    for (scriptType in types) {
        val accountPath = scriptType.accountPath()
        val typeGaps = if (kind == WalletSecretKind.ZPUB) gaps.p2wpkh else gaps[scriptType]
        val derivePublicKey: (String) -> PublicKey =
            when (kind) {
                WalletSecretKind.MNEMONIC -> {
                    val account = master!!.derivePrivateKey(accountPath)
                    val derive: (String) -> PublicKey = { path -> account.derivePrivateKey(path).publicKey }
                    derive
                }
                WalletSecretKind.ZPUB -> {
                    val account = DeterministicWallet.ExtendedPublicKey.decode(secret).second
                    val derive: (String) -> PublicKey = { path -> account.derivePublicKey(path).publicKey }
                    derive
                }
                else -> error("unsupported HD secret kind")
            }
        addresses.addAll(deriveHdAddresses(scriptType, accountPath, typeGaps, derivePublicKey))
    }
    return WatchWallet(
        kind = WatchWalletKind.BIP84,
        secret = secret,
        addresses = addresses,
        scripts = addresses.map { it.scriptPubKey },
    )
}

fun deriveWatchWallet(
    secret: String,
    gaps: HdWatchGaps? = null,
): WatchWallet {
    val parsed = parseWalletSecret(secret)
    return when (parsed.kind) {
        WalletSecretKind.WIF -> deriveWifWatchWallet(parsed.value)
        WalletSecretKind.ADDRESS -> deriveAddressWatchWallet(parsed.value)
        WalletSecretKind.MNEMONIC, WalletSecretKind.ZPUB ->
            deriveHdWatchWallet(parsed.value, parsed.kind, normalizeHdGaps(gaps))
    }
}

fun deriveWatchWallet(
    secret: String,
    gaps: WatchGaps,
): WatchWallet = deriveWatchWallet(secret, HdWatchGaps.uniform(gaps))

fun deriveWatchWallet(
    secret: String,
    gaps: Int,
): WatchWallet = deriveWatchWallet(secret, WatchGaps(maxOf(0, gaps), maxOf(0, gaps)))
