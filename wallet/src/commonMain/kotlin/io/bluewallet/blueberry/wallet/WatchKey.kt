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
    val accountPath: String get() = origin?.path?.asString('\'') ?: scriptType.accountPath()

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

private fun decodeExtendedKey(value: String): Pair<DeterministicWallet.ExtendedPublicKey, AddressScriptType> {
    val name = value.take(4)
    val (prefix, key) =
        try {
            DeterministicWallet.ExtendedPublicKey.decode(value)
        } catch (_: Exception) {
            throw IllegalArgumentException("invalid $name")
        }
    val scriptType = ACCOUNT_KEY_PREFIXES[prefix] ?: throw IllegalArgumentException("invalid $name")
    return key to scriptType
}

/** Decodes a mainnet account-level (depth 3) xpub, ypub or zpub. The prefix gives the script type. */
internal fun decodeAccountKey(value: String): Pair<DeterministicWallet.ExtendedPublicKey, AddressScriptType> {
    val (key, scriptType) = decodeExtendedKey(value)
    if (key.depth != 3) throw IllegalArgumentException("${value.take(4)} must be account-level (${scriptType.accountPath()})")
    return key to scriptType
}

/** True when [value] is shaped like an output descriptor (`name(...)`), so descriptor errors apply. */
internal fun looksLikeDescriptor(value: String): Boolean = DESCRIPTOR_START.containsMatchIn(value)

/** The watch key of an XPUB, YPUB, ZPUB or DESCRIPTOR secret value. */
fun parseWatchKey(value: String): WatchKey {
    if (looksLikeDescriptor(value.trim())) return parseDescriptor(value)
    val (key, scriptType) = decodeAccountKey(value.trim())
    return WatchKey(key, scriptType)
}

/** Trimmed non-blank lines: how a descriptor secret is stored. */
internal fun descriptorLines(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

private const val PAIR_REQUIRED = "add the matching /0/* and /1/* descriptors, or use /<0;1>/*"

/**
 * Parses a single-sig descriptor: `pkh(KEY)`, `sh(wpkh(KEY))`, `wpkh(KEY)` or `tr(KEY)`, each with an
 * optional BIP380 `#checksum`. KEY is `[fingerprint/origin path]` (optional), an account xpub, and
 * a multipath receive/change suffix or nothing. A receive line and a change line also work.
 */
fun parseDescriptor(text: String): WatchKey {
    val lines = descriptorLines(text).map(::parseDescriptorLine)
    return when (lines.size) {
        1 -> {
            val only = lines.single()
            if (only.chain != null) throw IllegalArgumentException(PAIR_REQUIRED)
            only.key
        }
        2 -> {
            val (a, b) = lines
            if (setOf(a.chain, b.chain) != setOf(0, 1)) throw IllegalArgumentException(PAIR_REQUIRED)
            if (a.key != b.key) throw IllegalArgumentException("receive and change descriptors must use the same key")
            a.key
        }
        else -> throw IllegalArgumentException("paste one descriptor, or its /0/* and /1/* pair")
    }
}

/** One descriptor line. [chain] is 0 (receive) or 1 (change) for a single-chain line, null when it covers both. */
private data class DescriptorLine(
    val key: WatchKey,
    val chain: Int?,
)

private val DESCRIPTOR_START = Regex("^[a-z_]+\\(")
private val ORIGIN = Regex("^([0-9a-fA-F]{8})((?:/[0-9]+['hH]?)*)$")
private val WRAPPERS =
    listOf(
        Triple("sh(wpkh(", "))", AddressScriptType.P2SH_P2WPKH),
        Triple("pkh(", ")", AddressScriptType.P2PKH),
        Triple("wpkh(", ")", AddressScriptType.P2WPKH),
        Triple("tr(", ")", AddressScriptType.P2TR),
    )
private val PRIVATE_KEY_PREFIXES = listOf("xprv", "yprv", "zprv", "tprv", "uprv", "vprv")
private val TESTNET_KEY_PREFIXES = listOf("tpub", "upub", "vpub")

private fun parseDescriptorLine(line: String): DescriptorLine {
    val body = line.substringBefore('#')
    if ('#' in line && descriptorChecksum(body) != line.substringAfter('#')) {
        throw IllegalArgumentException("descriptor checksum does not match")
    }
    if ("multi(" in body || "multi_a(" in body) throw IllegalArgumentException("multisig descriptors are not supported")
    if (body.startsWith("tr(") && ',' in body) throw IllegalArgumentException("taproot script trees are not supported")
    val (open, close, scriptType) =
        WRAPPERS.firstOrNull { (open, close) -> body.startsWith(open) && body.endsWith(close) }
            ?: throw IllegalArgumentException("only pkh, sh(wpkh), wpkh and tr descriptors are supported")
    val inner = body.substring(open.length, body.length - close.length)
    if ('(' in inner || ')' in inner) throw IllegalArgumentException("only pkh, sh(wpkh), wpkh and tr descriptors are supported")

    val origin = if (inner.startsWith("[")) parseOrigin(inner.substringBefore(']').drop(1)) else null
    val keyAndPath = if (inner.startsWith("[")) inner.substringAfter(']') else inner
    val account = parseDescriptorKey(keyAndPath.substringBefore('/'))
    if (origin != null && origin.path.path.size != account.depth) {
        throw IllegalArgumentException("key origin path must be the account path")
    }
    val path = keyAndPath.drop(keyAndPath.substringBefore('/').length)
    return DescriptorLine(WatchKey(account, scriptType, origin), parseChain(path))
}

private fun parseOrigin(text: String): KeyOrigin {
    val match = ORIGIN.matchEntire(text) ?: throw IllegalArgumentException("invalid key origin")
    val steps =
        match.groupValues[2].split('/').drop(1).map { step ->
            val hardened = step.last() in "'hH"
            val n = step.trimEnd('\'', 'h', 'H').toLongOrNull()
            if (n == null || n >= DeterministicWallet.hardenedKeyIndex) throw IllegalArgumentException("invalid key origin")
            if (hardened) DeterministicWallet.hardened(n) else n
        }
    return KeyOrigin(match.groupValues[1].toLong(16), KeyPath(steps))
}

private fun parseDescriptorKey(text: String): DeterministicWallet.ExtendedPublicKey {
    val prefix = text.take(4)
    when {
        prefix in PRIVATE_KEY_PREFIXES || looksLikeWifCandidate(text) ->
            throw IllegalArgumentException("descriptors with private keys are not supported")
        prefix in TESTNET_KEY_PREFIXES -> throw IllegalArgumentException("only mainnet descriptor keys are supported")
        prefix !in listOf("xpub", "ypub", "zpub") -> throw IllegalArgumentException("descriptor key must be an xpub")
    }
    val (key, _) = decodeExtendedKey(text)
    if (key.depth != 3) throw IllegalArgumentException("descriptor key must be account-level (depth 3)")
    return key
}

private fun parseChain(path: String): Int? =
    when {
        path.isEmpty() || path == "/<0;1>/*" -> null
        path == "/0/*" -> 0
        path == "/1/*" -> 1
        path.any { it in "'hH" } -> throw IllegalArgumentException("hardened derivation after the xpub is not supported")
        else -> throw IllegalArgumentException("descriptor path must be /<0;1>/* or none")
    }
