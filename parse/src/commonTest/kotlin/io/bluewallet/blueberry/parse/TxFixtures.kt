package io.bluewallet.blueberry.parse

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxHash
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import io.bluewallet.bip158.encodeCompactSize
import io.bluewallet.blueberry.wallet.hexToBytes

const val ABANDON_MNEMONIC =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

fun watchKey0(path: String = "m/84'/0'/0'/0/0"): PublicKey {
    val seed = MnemonicCode.toSeed(ABANDON_MNEMONIC, "")
    return DeterministicWallet.generate(seed).derivePrivateKey(path).publicKey
}

fun watchPubkey0(path: String = "m/84'/0'/0'/0/0"): ByteArray =
    watchKey0(path).value.toByteArray()

fun p2wpkhScript(pubkey: PublicKey = watchKey0()): ByteArray =
    Script.write(Script.pay2wpkh(pubkey))

fun p2pkhScript(pubkey: PublicKey = watchKey0()): ByteArray =
    Script.write(Script.pay2pkh(pubkey))

fun p2shP2wpkhScript(pubkey: PublicKey = watchKey0()): ByteArray =
    Script.write(Script.pay2sh(Script.pay2wpkh(pubkey)))

fun unrelatedScript(): ByteArray = byteArrayOf(0x00, 0x14) + ByteArray(20)

fun compilePushes(vararg chunks: ByteArray): ByteArray =
    Script.write(chunks.map { OP_PUSHDATA(it) })

fun coinbaseLikeReceive(script: ByteArray, valueSats: Long, prevSalt: Byte = 0): Transaction =
    Transaction(
        2L,
        listOf(
            TxIn(
                OutPoint(TxHash(ByteArray(32) { if (it == 0) prevSalt else 0 }), 0xffffffffL),
                0xffffffffL,
            ),
        ),
        listOf(TxOut(Satoshi(valueSats), script)),
        0L,
    )

fun witnessSpend(
    pubkey: ByteArray,
    prevHash: ByteArray = ByteArray(32) { 1 },
    valueSats: Long = 900,
): Transaction =
    Transaction(
        2L,
        listOf(
            TxIn(
                OutPoint(TxHash(prevHash), 0L),
                ByteVector.empty,
                0xffffffffL,
                ScriptWitness(listOf(ByteVector(ByteArray(64)), ByteVector(pubkey))),
            ),
        ),
        listOf(TxOut(Satoshi(valueSats), unrelatedScript())),
        0L,
    )

fun knownOutpointSpend(prevTxidDisplay: String, valueSats: Long = 900): Transaction =
    Transaction(
        2L,
        listOf(
            TxIn(
                OutPoint(TxHash(hexToBytes(prevTxidDisplay).reversedArray()), 0L),
                0xffffffffL,
            ),
        ),
        listOf(TxOut(Satoshi(valueSats), unrelatedScript())),
        0L,
    )

fun wrapBlock(txs: List<Transaction>): ByteArray {
    val header = ByteArray(80)
    header[0] = 1
    val bodies = txs.map { Transaction.write(it) }
    var total = header.size + encodeCompactSize(txs.size).size
    for (body in bodies) total += body.size
    val out = ByteArray(total)
    header.copyInto(out)
    var offset = header.size
    val count = encodeCompactSize(txs.size)
    count.copyInto(out, offset)
    offset += count.size
    for (body in bodies) {
        body.copyInto(out, offset)
        offset += body.size
    }
    return out
}

fun wrapBlock(vararg txs: Transaction): ByteArray = wrapBlock(txs.toList())
