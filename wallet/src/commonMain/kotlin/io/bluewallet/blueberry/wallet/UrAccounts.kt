package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath

/** UR types an account export comes in: BCR-2020-015 `crypto-account`, Keystone's `crypto-multi-accounts`, BCR-2020-007 `crypto-hdkey`. */
val ACCOUNT_UR_TYPES: Set<String> = setOf("crypto-account", "crypto-multi-accounts", "crypto-hdkey")

const val UR_NOT_ACCOUNT = "This QR code isn't an account export."

/** One single-sig Bitcoin account from an account export: `m/purpose'/0'/account'` of the master key [fingerprint]. */
data class UrAccount(
    val scriptType: AddressScriptType,
    val fingerprint: Long,
    val path: KeyPath,
    val xpub: String,
) {
    /** The account as a checksummed descriptor with its key origin and `<0;1>` multipath, e.g. `wpkh([73c5da0a/84'/0'/0']xpub…)`. */
    val descriptor: String
        get() {
            val (open, close) = DESCRIPTOR_WRAPPERS.getValue(scriptType)
            val origin = fingerprint.toString(16).padStart(8, '0') + path.asString('\'').removePrefix("m")
            val body = "$open[$origin]$xpub/<0;1>/*$close"
            return "$body#${checkNotNull(descriptorChecksum(body))}"
        }

    override fun toString(): String = "UrAccount(scriptType=$scriptType, path=${path.asString('\'')}, xpub=[redacted])"
}

private val DESCRIPTOR_WRAPPERS =
    mapOf(
        AddressScriptType.P2PKH to ("pkh(" to ")"),
        AddressScriptType.P2SH_P2WPKH to ("sh(wpkh(" to "))"),
        AddressScriptType.P2WPKH to ("wpkh(" to ")"),
        AddressScriptType.P2TR to ("tr(" to ")"),
    )

private const val TAG_HDKEY = 303L
private const val TAG_KEYPATH = 304L
private const val TAG_EC_KEY = 306L

// Script tags (BCR-2020-010) from the outside in: sh 400, pkh 403, wpkh 404, tr 409.
private val SCRIPT_TAGS =
    mapOf(
        listOf(403L) to AddressScriptType.P2PKH,
        listOf(400L, 404L) to AddressScriptType.P2SH_P2WPKH,
        listOf(404L) to AddressScriptType.P2WPKH,
        listOf(409L) to AddressScriptType.P2TR,
    )

private val PURPOSES =
    mapOf(
        44L to AddressScriptType.P2PKH,
        49L to AddressScriptType.P2SH_P2WPKH,
        84L to AddressScriptType.P2WPKH,
        86L to AddressScriptType.P2TR,
    )

private const val COMPRESSED_KEY_SIZE = 33
private const val CHAIN_CODE_SIZE = 32

/**
 * The single-sig Bitcoin accounts in a complete UR [message] of one of [ACCOUNT_UR_TYPES], in payload order.
 * Empty when none is usable, or when a `crypto-account` output with a supported script has no usable HD key
 * (the whole account is rejected, as BlueWallet does). Throws [IllegalArgumentException] on malformed CBOR.
 */
fun parseUrAccounts(
    type: String,
    message: ByteArray,
): List<UrAccount> {
    val root = CborReader(message).readItem()
    return when (type) {
        "crypto-account" -> cryptoAccount(root.asMap())
        "crypto-multi-accounts" -> {
            val map = root.asMap()
            val fingerprint = map.uint(1)
            map.list(2).mapNotNull { key -> usableKey(key.tagged(TAG_HDKEY).asMap(), fingerprint)?.let(::byPurpose) }
        }
        "crypto-hdkey" -> listOfNotNull(usableKey(root.asMap(), null)?.let(::byPurpose))
        else -> throw IllegalArgumentException("not an account UR type: $type")
    }
}

private fun cryptoAccount(map: Map<*, *>): List<UrAccount> {
    val fingerprint = map.uint(1)
    val accounts = mutableListOf<UrAccount>()
    for (output in map.list(2)) {
        val tags = mutableListOf<Long>()
        var inner = output
        while (inner is CborTag && inner.tag != TAG_HDKEY && inner.tag != TAG_EC_KEY) {
            tags += inner.tag
            inner = inner.item
        }
        val scriptType = SCRIPT_TAGS[tags] ?: continue
        val hdKey = (inner as? CborTag)?.takeIf { it.tag == TAG_HDKEY }?.item as? Map<*, *>
        val key = hdKey?.let { usableKey(it, fingerprint) } ?: return emptyList()
        account(key, scriptType)?.let(accounts::add)
    }
    return accounts
}

/** A usable HD key (spec decision 5): `steps` are the origin's `(index, hardened)` pairs. */
private class HdKey(
    val key: ByteArray,
    val chainCode: ByteArray,
    val steps: List<Pair<Long, Boolean>>,
    val depth: Long?,
    val parentFingerprint: Long,
    val fingerprint: Long,
)

private fun usableKey(
    map: Map<*, *>,
    masterFingerprint: Long?,
): HdKey? {
    val key = (map[3L] as? ByteArray)?.takeIf { it.size == COMPRESSED_KEY_SIZE && it[0].toInt() in 2..3 }
    val chainCode = (map[4L] as? ByteArray)?.takeIf { it.size == CHAIN_CODE_SIZE }
    val origin = (map[6L] as? CborTag)?.takeIf { it.tag == TAG_KEYPATH }?.item as? Map<*, *>
    val steps = originSteps(origin?.get(1L))
    val fingerprint = masterFingerprint ?: origin?.get(2L) as? Long
    return when {
        map[1L] == true || map[2L] == true -> null
        key == null || chainCode == null || steps.isNullOrEmpty() || fingerprint == null -> null
        else -> HdKey(key, chainCode, steps, origin?.get(3L) as? Long, map[8L] as? Long ?: 0L, fingerprint)
    }
}

/** Keypath components as `(index, hardened)` pairs; null for a wildcard, a range or an odd count. */
private fun originSteps(components: Any?): List<Pair<Long, Boolean>>? {
    val list = components as? List<*> ?: return null
    val steps = list.chunked(2).mapNotNull { pair -> (pair.getOrNull(0) as? Long)?.let { index -> (pair.getOrNull(1) as? Boolean)?.let { index to it } } }
    return steps.takeIf { it.size * 2 == list.size }
}

private fun byPurpose(key: HdKey): UrAccount? = PURPOSES[key.steps.first().first]?.let { account(key, it) }

/** The account when the origin is `purpose'/0'/account'` with the purpose of [scriptType] (spec decision 6). */
private fun account(
    key: HdKey,
    scriptType: AddressScriptType,
): UrAccount? {
    val steps = key.steps
    val isAccountPath =
        steps.size == ACCOUNT_DEPTH &&
            steps.all { (index, hardened) -> hardened && index < DeterministicWallet.hardenedKeyIndex } &&
            PURPOSES[steps[0].first] == scriptType &&
            steps[1].first == 0L &&
            (key.depth ?: ACCOUNT_DEPTH.toLong()) == ACCOUNT_DEPTH.toLong()
    if (!isAccountPath) return null
    val path = KeyPath(steps.map { DeterministicWallet.hardened(it.first) })
    val account = DeterministicWallet.ExtendedPublicKey(ByteVector(key.key), ByteVector32(key.chainCode), ACCOUNT_DEPTH, path, key.parentFingerprint)
    return UrAccount(scriptType, key.fingerprint, path, account.encode(DeterministicWallet.xpub))
}

private fun Any?.asMap(): Map<*, *> = this as? Map<*, *> ?: throw IllegalArgumentException("expected a CBOR map")

private fun Any?.tagged(tag: Long): Any? = (this as? CborTag)?.takeIf { it.tag == tag }?.item ?: throw IllegalArgumentException("expected CBOR tag $tag")

private fun Map<*, *>.uint(key: Long): Long = this[key] as? Long ?: throw IllegalArgumentException("expected a CBOR uint at $key")

private fun Map<*, *>.list(key: Long): List<*> = this[key] as? List<*> ?: throw IllegalArgumentException("expected a CBOR array at $key")
