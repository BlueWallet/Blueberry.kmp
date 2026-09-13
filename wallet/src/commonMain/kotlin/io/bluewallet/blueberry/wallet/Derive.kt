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

private fun normalizeGaps(gaps: WatchGaps?): WatchGaps {
    if (gaps == null) {
        return WatchGaps(INITIAL_WATCH_COUNT, INITIAL_WATCH_COUNT)
    }
    return WatchGaps(
        external = maxOf(0, gaps.external),
        internal = maxOf(0, gaps.internal),
    )
}

private fun normalizeGaps(gaps: Int): WatchGaps = WatchGaps(maxOf(0, gaps), maxOf(0, gaps))

private fun scriptPubKeyForAddress(address: String): ByteArray = outputScriptFromAddress(address)

private fun p2pkhAddress(publicKey: PublicKey): String = Bitcoin.computeP2PkhAddress(publicKey, Block.LivenetGenesisBlock.hash)

private fun p2shP2wpkhAddress(publicKey: PublicKey): String = Bitcoin.computeP2ShOfP2WpkhAddress(publicKey, Block.LivenetGenesisBlock.hash)

private fun p2wpkhAddress(publicKey: PublicKey): String = Bitcoin.computeP2WpkhAddress(publicKey, Block.LivenetGenesisBlock.hash)

private fun p2trAddress(publicKey: PublicKey): String {
    val xOnly = XonlyPublicKey(publicKey)
    return Bitcoin.computeBIP86Address(xOnly, Block.LivenetGenesisBlock.hash)
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

private fun deriveBip84WatchWallet(
    secret: String,
    kind: WalletSecretKind,
    gaps: WatchGaps,
): WatchWallet {
    val account =
        when (kind) {
            WalletSecretKind.MNEMONIC -> {
                val seed = MnemonicCode.toSeed(secret, "")
                DeterministicWallet.generate(seed).derivePrivateKey(BIP84_ACCOUNT_PATH)
            }
            WalletSecretKind.ZPUB -> {
                DeterministicWallet.ExtendedPublicKey.decode(secret).second
            }
            else -> error("unsupported BIP84 secret kind")
        }

    val addresses = mutableListOf<WatchAddress>()
    val chains =
        listOf(
            false to gaps.external,
            true to gaps.internal,
        )
    for ((change, count) in chains) {
        val chain = if (change) 1 else 0
        for (index in 0 until count) {
            val path = "$BIP84_ACCOUNT_PATH/$chain/$index"
            val childKey =
                when (kind) {
                    WalletSecretKind.MNEMONIC ->
                        (account as DeterministicWallet.ExtendedPrivateKey).derivePrivateKey("m/$chain/$index").publicKey
                    WalletSecretKind.ZPUB ->
                        (account as DeterministicWallet.ExtendedPublicKey).derivePublicKey("m/$chain/$index").publicKey
                }
            val address = p2wpkhAddress(childKey)
            val scriptPubKey = scriptPubKeyForAddress(address)
            addresses.add(
                WatchAddress(
                    path = path,
                    index = index,
                    change = change,
                    address = address,
                    scriptPubKey = scriptPubKey,
                    scriptType = AddressScriptType.P2WPKH,
                ),
            )
        }
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
    gaps: WatchGaps? = null,
): WatchWallet {
    val parsed = parseWalletSecret(secret)
    return when (parsed.kind) {
        WalletSecretKind.WIF -> deriveWifWatchWallet(parsed.value)
        WalletSecretKind.ADDRESS -> deriveAddressWatchWallet(parsed.value)
        WalletSecretKind.MNEMONIC, WalletSecretKind.ZPUB ->
            deriveBip84WatchWallet(parsed.value, parsed.kind, normalizeGaps(gaps))
    }
}

fun deriveWatchWallet(
    secret: String,
    gaps: Int,
): WatchWallet = deriveWatchWallet(secret, normalizeGaps(gaps))
