package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath

/** True when [value] is shaped like an output descriptor (`name(...)`), so descriptor errors apply. */
internal fun looksLikeDescriptor(value: String): Boolean = DESCRIPTOR_START.containsMatchIn(value)

/** Trimmed non-blank lines: how a descriptor secret is stored. */
internal fun descriptorLines(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

private const val PAIR_REQUIRED = "add the matching /0/* and /1/* descriptors, or use /<0;1>/*"
private const val UNSUPPORTED_SCRIPT = "only pkh, sh(wpkh), wpkh and tr descriptors are supported"

/**
 * Parses a single-sig descriptor: `pkh(KEY)`, `sh(wpkh(KEY))`, `wpkh(KEY)` or `tr(KEY)`, each with an
 * optional BIP380 `#checksum`. KEY is `[fingerprint/origin path]` (optional), an account xpub, and
 * a multipath receive/change suffix or nothing. A receive line and a change line also work.
 */
fun parseDescriptor(text: String): WatchKey {
    val lines = descriptorLines(text).map(::parseDescriptorLine)
    require(lines.size in 1..2) { "paste one descriptor, or its /0/* and /1/* pair" }
    val chains = lines.map { it.chain }
    require(chains == listOf(null) || chains.toSet() == setOf(0, 1)) { PAIR_REQUIRED }
    require(lines.all { it.key == lines[0].key }) { "receive and change descriptors must use the same key" }
    return lines[0].key
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
private val PUBLIC_KEY_PREFIXES = listOf("xpub", "ypub", "zpub")
private val PRIVATE_KEY_PREFIXES = listOf("xprv", "yprv", "zprv", "tprv", "uprv", "vprv")
private val TESTNET_KEY_PREFIXES = listOf("tpub", "upub", "vpub")

private fun parseDescriptorLine(line: String): DescriptorLine {
    val body = line.substringBefore('#')
    require('#' !in line || descriptorChecksum(body) == line.substringAfter('#')) { "descriptor checksum does not match" }
    val (scriptType, inner) = unwrapScript(body)
    val origin = if (inner.startsWith("[")) parseOrigin(inner.substringBefore(']').drop(1)) else null
    val keyAndPath = if (inner.startsWith("[")) inner.substringAfter(']') else inner
    val keyText = keyAndPath.substringBefore('/')
    val account = parseDescriptorKey(keyText)
    require(origin == null || origin.path.path.size == account.depth) { "key origin path must be the account path" }
    return DescriptorLine(WatchKey(account, scriptType, origin), parseChain(keyAndPath.drop(keyText.length)))
}

/** The script a descriptor pays to and the key expression inside its wrapper. */
private fun unwrapScript(body: String): Pair<AddressScriptType, String> {
    require("multi(" !in body && "multi_a(" !in body) { "multisig descriptors are not supported" }
    require(!(body.startsWith("tr(") && ',' in body)) { "taproot script trees are not supported" }
    val (open, close, scriptType) =
        requireNotNull(WRAPPERS.firstOrNull { (open, close) -> body.startsWith(open) && body.endsWith(close) }) {
            UNSUPPORTED_SCRIPT
        }
    val inner = body.substring(open.length, body.length - close.length)
    require('(' !in inner && ')' !in inner) { UNSUPPORTED_SCRIPT }
    return scriptType to inner
}

private fun parseOrigin(text: String): KeyOrigin {
    val match = requireNotNull(ORIGIN.matchEntire(text)) { "invalid key origin" }
    val steps =
        match.groupValues[2].split('/').drop(1).map { step ->
            val n = step.trimEnd('\'', 'h', 'H').toLongOrNull() ?: Long.MAX_VALUE
            require(n < DeterministicWallet.hardenedKeyIndex) { "invalid key origin" }
            if (step.last() in "'hH") DeterministicWallet.hardened(n) else n
        }
    return KeyOrigin(match.groupValues[1].toLong(16), KeyPath(steps))
}

private fun parseDescriptorKey(text: String): DeterministicWallet.ExtendedPublicKey {
    val prefix = text.take(4)
    val isPrivate = prefix in PRIVATE_KEY_PREFIXES || runCatching { decodeWifPrivateKey(text) }.isSuccess
    require(!isPrivate) { "descriptors with private keys are not supported" }
    require(prefix !in TESTNET_KEY_PREFIXES) { "only mainnet descriptor keys are supported" }
    require(prefix in PUBLIC_KEY_PREFIXES) { "descriptor key must be an xpub" }
    val (key, _) = decodeExtendedKey(text)
    require(key.depth == ACCOUNT_DEPTH) { "descriptor key must be account-level (depth 3)" }
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
