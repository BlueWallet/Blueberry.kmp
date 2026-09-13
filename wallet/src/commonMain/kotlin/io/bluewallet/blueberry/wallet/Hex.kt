package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.Crypto

fun scriptHex(script: ByteArray): String = hexFromBytes(script)

fun sha256Hex(bytes: ByteArray): String = hexFromBytes(Crypto.sha256(bytes))

fun hexFromBytes(bytes: ByteArray): String =
    bytes.joinToString("") { b ->
        val v = b.toInt() and 0xff
        val hex = "0123456789abcdef"
        "${hex[v shr 4]}${hex[v and 0x0f]}"
    }

fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex length must be even" }
    return ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

fun outpointKey(
    txidDisplay: String,
    vout: Int,
): String = "$txidDisplay:$vout"

fun prevoutTxidDisplay(inputHash: ByteArray): String = hexFromBytes(inputHash.reversedArray())
