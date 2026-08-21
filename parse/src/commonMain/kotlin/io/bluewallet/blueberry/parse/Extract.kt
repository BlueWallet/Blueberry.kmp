package io.bluewallet.blueberry.parse

import fr.acinq.bitcoin.BtcSerializer
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.io.ByteArrayInput
fun outpointKey(txidDisplay: String, vout: Int): String =
    io.bluewallet.blueberry.wallet.outpointKey(txidDisplay, vout)

fun scriptHex(script: ByteArray): String = io.bluewallet.blueberry.wallet.scriptHex(script)

fun prevoutTxidDisplay(inputHash: ByteArray): String =
    io.bluewallet.blueberry.wallet.prevoutTxidDisplay(inputHash)

/** Same floor as bip158's block decoder — a valid tx cannot be smaller. */
private const val MIN_TRANSACTION_BYTES = 60

fun decodeBlockTxs(blockBytes: ByteArray): List<Transaction> {
    if (blockBytes.size < 80) {
        throw IllegalArgumentException("block too short")
    }
    val input = ByteArrayInput(blockBytes)
    val header = ByteArray(80)
    if (input.read(header, 0, 80) != 80) {
        throw IllegalArgumentException("unexpected end of block header")
    }
    val count = BtcSerializer.varint(input).toLong()
    if (count < 0L || count > Int.MAX_VALUE.toLong()) {
        throw IllegalArgumentException("invalid transaction count")
    }
    val remaining = input.availableBytes
    if (count > remaining / MIN_TRANSACTION_BYTES) {
        throw IllegalArgumentException(
            "transaction count $count cannot fit in $remaining remaining bytes",
        )
    }
    val txs = ArrayList<Transaction>(count.toInt())
    repeat(count.toInt()) {
        txs.add(Transaction.read(input))
    }
    if (input.availableBytes != 0) {
        throw IllegalArgumentException("trailing block data")
    }
    return txs
}

fun p2wpkhScriptFromPubkey(pubkey: ByteArray): ByteArray {
    val h = Crypto.hash160(pubkey)
    return byteArrayOf(0x00, 0x14) + h
}

private fun p2pkhScriptFromPubkey(pubkey: ByteArray): ByteArray {
    val h = Crypto.hash160(pubkey)
    return byteArrayOf(0x76, 0xa9.toByte(), 0x14) + h + byteArrayOf(0x88.toByte(), 0xac.toByte())
}

private fun p2shP2wpkhScriptFromPubkey(pubkey: ByteArray): ByteArray {
    val redeemHash = Crypto.hash160(p2wpkhScriptFromPubkey(pubkey))
    return byteArrayOf(0xa9.toByte(), 0x14) + redeemHash + byteArrayOf(0x87.toByte())
}

private fun isPubkeyBytes(value: ByteArray): Boolean = value.size == 33 || value.size == 65

/** Best-effort pubkey from a legacy scriptSig (`<sig> <pubkey>`). */
private fun pubkeyFromScriptSig(scriptSig: ByteArray): ByteArray? {
    return try {
        val chunks = Script.parse(scriptSig)
        for (i in chunks.lastIndex downTo 0) {
            val written = Script.write(listOf(chunks[i]))
            val payload = pushPayload(written) ?: continue
            if (isPubkeyBytes(payload)) return payload
        }
        null
    } catch (_: Throwable) {
        null
    }
}

private fun pushPayload(written: ByteArray): ByteArray? {
    if (written.isEmpty()) return null
    val op = written[0].toInt() and 0xff
    return when {
        op in 1..75 -> {
            if (written.size != 1 + op) return null
            written.copyOfRange(1, written.size)
        }
        else -> null
    }
}

/**
 * Watched scriptPubKeys implied by an input's unlocking data, independent of
 * whether the spent outpoint is already in the UTXO map.
 */
fun watchedScriptsFromInput(input: TxIn): List<ByteArray> {
    val out = mutableListOf<ByteArray>()
    val wit = input.witness.stack
    if (wit.size >= 2) {
        val pk = wit.last().toByteArray()
        if (pk.size == 33) {
            out.add(p2wpkhScriptFromPubkey(pk))
            out.add(p2shP2wpkhScriptFromPubkey(pk))
        }
    }
    val fromSig = pubkeyFromScriptSig(input.signatureScript.toByteArray())
    if (fromSig != null) out.add(p2pkhScriptFromPubkey(fromSig))
    return out
}

private fun inputMatchesWatch(input: TxIn, watch: Set<String>): Boolean {
    for (script in watchedScriptsFromInput(input)) {
        if (watch.contains(scriptHex(script))) return true
    }
    return false
}

/** Mutates `utxos` for same-block chaining / subsequent blocks. */
fun extractWatchTxs(
    txs: List<Transaction>,
    watchScripts: List<ByteArray>,
    utxos: MutableMap<String, WatchUtxo>,
): List<ExtractedWatchTx> {
    val watch = watchScripts.map(::scriptHex).toSet()
    val out = mutableListOf<ExtractedWatchTx>()
    for (i in txs.indices) {
        val tx = txs[i]
        var relevant = false
        if (!tx.isCoinbase()) {
            for (inn in tx.txIn) {
                if (utxos.containsKey(prevoutKey(inn)) || inputMatchesWatch(inn, watch)) {
                    relevant = true
                    break
                }
            }
        }
        for (outp in tx.txOut) {
            if (watch.contains(scriptHex(outp.publicKeyScript.toByteArray()))) relevant = true
        }
        if (!relevant) continue
        out.add(ExtractedWatchTx(tx.txid.toString(), i, Transaction.write(tx)))
        if (!tx.isCoinbase()) {
            for (inn in tx.txIn) {
                utxos.remove(prevoutKey(inn))
            }
        }
        tx.txOut.forEachIndexed { vout, o ->
            if (watch.contains(scriptHex(o.publicKeyScript.toByteArray()))) {
                utxos[outpointKey(tx.txid.toString(), vout)] = WatchUtxo(
                    value = o.amount.toLong(),
                    scriptPubKey = o.publicKeyScript.toByteArray(),
                )
            }
        }
    }
    return out
}
