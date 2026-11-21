package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath

/** Where an account key sits under its wallet: the master key fingerprint and the full account path. */
data class KeyOrigin(
    val fingerprint: Long,
    val path: KeyPath,
)

/**
 * A watch-only HD account: one account-level extended public key, the one script it pays to, and
 * its key origin when the import named one. Addresses are `account/chain/index`.
 */
data class WatchKey(
    val account: DeterministicWallet.ExtendedPublicKey,
    val scriptType: AddressScriptType,
    val origin: KeyOrigin? = null,
) {
    /** Path shown for this account's addresses: the origin path when known, else the script's standard path. */
    val accountPath: String get() = origin?.path?.let { "m/" + it.asString('\'') } ?: scriptType.accountPath()

    /** PSBT master fingerprint: the origin's, else the account key's own (paths are then account-relative). */
    fun bip32Fingerprint(): Long = origin?.fingerprint ?: account.fingerprint()

    /** PSBT key path of `chain/index`: prefixed by the origin path when known. */
    fun bip32Path(
        change: Boolean,
        index: Int,
    ): KeyPath = (origin?.path ?: KeyPath.empty).append(relativePath(change, index))

    fun publicKeyAt(
        change: Boolean,
        index: Int,
    ) = account.derivePublicKey(relativePath(change, index)).publicKey
}

private fun relativePath(
    change: Boolean,
    index: Int,
): List<Long> = listOf(if (change) 1L else 0L, index.toLong())

private val ACCOUNT_KEY_PREFIXES =
    mapOf(
        DeterministicWallet.xpub to AddressScriptType.P2PKH,
        DeterministicWallet.ypub to AddressScriptType.P2SH_P2WPKH,
        DeterministicWallet.zpub to AddressScriptType.P2WPKH,
    )

/** Decodes a mainnet account-level (depth 3) xpub, ypub or zpub. The prefix gives the script type. */
internal fun decodeAccountKey(value: String): Pair<DeterministicWallet.ExtendedPublicKey, AddressScriptType> {
    val name = value.take(4)
    val (prefix, key) =
        try {
            DeterministicWallet.ExtendedPublicKey.decode(value)
        } catch (_: Exception) {
            throw IllegalArgumentException("invalid $name")
        }
    val scriptType = ACCOUNT_KEY_PREFIXES[prefix] ?: throw IllegalArgumentException("invalid $name")
    if (key.depth != 3) throw IllegalArgumentException("$name must be account-level (${scriptType.accountPath()})")
    return key to scriptType
}

/** The watch key of an XPUB, YPUB, ZPUB or DESCRIPTOR secret value. */
fun parseWatchKey(value: String): WatchKey {
    val (key, scriptType) = decodeAccountKey(value.trim())
    return WatchKey(key, scriptType)
}
