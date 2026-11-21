package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.Base58
import fr.acinq.bitcoin.Base58Check
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.MnemonicCode
import io.bluewallet.blueberry.storage.Database

enum class WalletSecretKind { MNEMONIC, XPUB, YPUB, ZPUB, DESCRIPTOR, WIF, ADDRESS }

private val WATCH_KEY_KINDS = mapOf("xpub" to WalletSecretKind.XPUB, "ypub" to WalletSecretKind.YPUB, "zpub" to WalletSecretKind.ZPUB)

data class ParsedWalletSecret(
    val kind: WalletSecretKind,
    val value: String,
) {
    /** Redacts [value] (mnemonic/WIF/zpub/address) so it never lands in logs or crash reports. */
    override fun toString(): String = "ParsedWalletSecret(kind=$kind, value=[redacted])"
}

sealed class WalletSecretInspection {
    data object Missing : WalletSecretInspection()

    data class Ok(
        val value: String,
    ) : WalletSecretInspection() {
        override fun toString(): String = "Ok(value=[redacted])"
    }

    data class Invalid(
        val detail: String,
    ) : WalletSecretInspection()
}

internal fun looksLikeWifCandidate(value: String): Boolean {
    if (value.any { it.isWhitespace() }) return false
    if (value.length !in 51..52) return false
    return value.first() in "5KL9c"
}

private fun looksLikeAddressCandidate(value: String): Boolean {
    if (Regex("^(?:bc1|tb1|bcrt1)", RegexOption.IGNORE_CASE).containsMatchIn(value)) return true
    val compact = value.replace("\\s".toRegex(), "")
    return compact.length in 26..35 && Regex("^[13mn2]", RegexOption.IGNORE_CASE).containsMatchIn(compact)
}

fun decodeWifPrivateKey(wif: String): ByteArray {
    val value = wif.trim()
    if (value.isEmpty()) throw IllegalArgumentException("invalid WIF")
    if (value.startsWith("c") || value.startsWith("9")) {
        throw IllegalArgumentException("only mainnet compressed WIF is supported (not testnet)")
    }
    if (value.startsWith("5")) {
        throw IllegalArgumentException("uncompressed WIF is not supported; use compressed WIF")
    }
    return try {
        val (prefix, payload) = Base58Check.decode(value)
        if (prefix != Base58.Prefix.SecretKey) throw IllegalArgumentException("invalid WIF")
        if (payload.size != 33 || payload.last() != 0x01.toByte()) {
            throw IllegalArgumentException("invalid WIF")
        }
        payload.copyOfRange(0, 32)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("invalid WIF")
    } catch (_: Exception) {
        throw IllegalArgumentException("invalid WIF")
    }
}

fun parseWalletSecret(raw: String): ParsedWalletSecret {
    val value = raw.trim()
    if (value.isEmpty()) throw IllegalArgumentException("wallet secret is empty")
    if (looksLikeDescriptor(value)) {
        parseDescriptor(value)
        return ParsedWalletSecret(WalletSecretKind.DESCRIPTOR, descriptorLines(value).joinToString("\n"))
    }
    WATCH_KEY_KINDS[value.take(4)]?.let { kind ->
        decodeAccountKey(value)
        return ParsedWalletSecret(kind, value)
    }
    if (Regex("^[xyzuvt]p(?:ub|rv)").containsMatchIn(value)) {
        throw IllegalArgumentException("only mainnet account xpub, ypub or zpub is supported")
    }
    if (looksLikeWifCandidate(value)) {
        decodeWifPrivateKey(value)
        return ParsedWalletSecret(WalletSecretKind.WIF, value)
    }
    if (isAddressValid(value)) {
        watchAddressScriptType(value)
        return ParsedWalletSecret(WalletSecretKind.ADDRESS, value)
    }
    if (looksLikeAddressCandidate(value)) {
        throw IllegalArgumentException("invalid mainnet address")
    }
    val mnemonic = value.replace(Regex("\\s+"), " ").lowercase()
    try {
        MnemonicCode.validate(mnemonic)
    } catch (_: Exception) {
        throw IllegalArgumentException("invalid BIP39 mnemonic")
    }
    return ParsedWalletSecret(WalletSecretKind.MNEMONIC, mnemonic)
}

/** The watch key of an xpub, ypub, zpub or descriptor secret; null for other kinds. */
fun ParsedWalletSecret.watchKey(): WatchKey? =
    when (kind) {
        WalletSecretKind.XPUB, WalletSecretKind.YPUB, WalletSecretKind.ZPUB, WalletSecretKind.DESCRIPTOR -> parseWatchKey(value)
        WalletSecretKind.MNEMONIC, WalletSecretKind.WIF, WalletSecretKind.ADDRESS -> null
    }

/** HD script types this secret watches: all four for a mnemonic, the key's one for a watch key, none otherwise. */
fun ParsedWalletSecret.hdScriptTypes(): List<AddressScriptType> =
    if (kind == WalletSecretKind.MNEMONIC) HD_SCRIPT_TYPES else listOfNotNull(watchKey()?.scriptType)

fun hasWalletSecret(db: Database): Boolean {
    val v = db.keyValue.get(WALLET_SECRET_KEY)
    return v != null && v.trim().isNotEmpty()
}

fun inspectWalletSecret(db: Database): WalletSecretInspection {
    val raw = db.keyValue.get(WALLET_SECRET_KEY)
    if (raw == null || raw.trim().isEmpty()) return WalletSecretInspection.Missing
    return try {
        val parsed = parseWalletSecret(raw)
        WalletSecretInspection.Ok(parsed.value)
    } catch (err: Exception) {
        WalletSecretInspection.Invalid(err.message ?: err.toString())
    }
}

fun loadWalletSecret(db: Database): String {
    val v = db.keyValue.get(WALLET_SECRET_KEY)
    if (v == null || v.trim().isEmpty()) throw IllegalArgumentException("wallet_secret missing")
    return v.trim()
}

fun saveWalletSecret(
    db: Database,
    raw: String,
): ParsedWalletSecret {
    val parsed = parseWalletSecret(raw)
    db.keyValue.set(WALLET_SECRET_KEY, parsed.value)
    return parsed
}
