package io.bluewallet.blueberry.wallet

/** Bech32 (bc1…) is compared and stored in lowercase. Base58 is left as-is. */
fun canonicalBitcoinAddress(address: String): String {
    val trimmed = address.trim()
    return if (trimmed.startsWith("bc1", ignoreCase = true)) trimmed.lowercase() else trimmed
}
