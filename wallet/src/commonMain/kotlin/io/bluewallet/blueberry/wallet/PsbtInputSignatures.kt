package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ScriptWitness

/** The signing fields of one PSBT input, read off the raw key-value map. */
internal class InputSignatures(
    /** (pubkey, signature) from PSBT_IN_PARTIAL_SIG entries. */
    val partialSigs: List<Pair<ByteVector, ByteVector>>,
    val scriptSig: ByteVector?,
    val scriptWitness: ScriptWitness?,
    val taprootKeySignature: ByteVector?,
)

private const val IN_PARTIAL_SIG = 0x02
private const val IN_FINAL_SCRIPTSIG = 0x07
private const val IN_FINAL_SCRIPTWITNESS = 0x08
private const val IN_TAP_KEY_SIG = 0x13
private const val PSBT_HEADER_SIZE = 5

/**
 * The signing fields of the first [inputCount] inputs of a raw PSBT, or null when the bytes are malformed.
 * Read at the byte level because bitcoin-kmp's parser drops partial signatures and final scripts from an
 * input that has no UTXO, or that kept other fields beside its final scripts; signers do both.
 */
internal fun inputSignatures(
    psbt: ByteArray,
    inputCount: Int,
): List<InputSignatures>? =
    runCatching {
        val reader = RawReader(psbt, PSBT_HEADER_SIZE)
        reader.entries()
        List(inputCount) { signaturesOf(reader.entries()) }
    }.getOrNull()

private fun signaturesOf(entries: List<Pair<ByteArray, ByteArray>>): InputSignatures {
    fun single(type: Int): ByteArray? = entries.firstOrNull { it.first.size == 1 && it.first[0].toInt() == type }?.second
    return InputSignatures(
        partialSigs =
            entries
                .filter { it.first.size > 1 && it.first[0].toInt() == IN_PARTIAL_SIG }
                .map { ByteVector(it.first.copyOfRange(1, it.first.size)) to ByteVector(it.second) },
        scriptSig = single(IN_FINAL_SCRIPTSIG)?.let { ByteVector(it) },
        scriptWitness = single(IN_FINAL_SCRIPTWITNESS)?.let(::readWitness),
        taprootKeySignature = single(IN_TAP_KEY_SIG)?.let { ByteVector(it) },
    )
}

private fun readWitness(bytes: ByteArray): ScriptWitness {
    val reader = RawReader(bytes, 0)
    val items = List(reader.compactSize().toInt()) { ByteVector(reader.bytes(reader.compactSize().toInt())) }
    require(reader.done)
    return ScriptWitness(items)
}

/** Reads BIP174 key-value maps; throws on bytes that run out. */
private class RawReader(
    private val data: ByteArray,
    private var at: Int,
) {
    val done: Boolean get() = at == data.size

    fun entries(): List<Pair<ByteArray, ByteArray>> {
        val out = mutableListOf<Pair<ByteArray, ByteArray>>()
        var keyLength = compactSize().toInt()
        while (keyLength != 0) {
            val key = bytes(keyLength)
            out += key to bytes(compactSize().toInt())
            keyLength = compactSize().toInt()
        }
        return out
    }

    fun bytes(count: Int): ByteArray {
        require(count >= 0 && count <= data.size - at)
        return data.copyOfRange(at, at + count).also { at += count }
    }

    fun compactSize(): Long {
        val first = bytes(1)[0].toInt() and 0xff
        val width =
            when (first) {
                0xfd -> 2
                0xfe -> 4
                0xff -> 8
                else -> return first.toLong()
            }
        val raw = bytes(width)
        val value = (0 until width).fold(0L) { acc, i -> acc or ((raw[i].toLong() and 0xff) shl (8 * i)) }
        require(value in 0..Int.MAX_VALUE.toLong())
        return value
    }
}
