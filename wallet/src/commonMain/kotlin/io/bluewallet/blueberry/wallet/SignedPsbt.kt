package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.Psbt
import kotlin.io.encoding.Base64

const val PSBT_NOT_A_PSBT = "This QR isn't a PSBT."
const val PSBT_DIFFERENT_TX = "This PSBT is for a different transaction."
const val PSBT_NOT_SIGNED = "This PSBT isn't fully signed. Sign every input on your signing device."
const val PSBT_BAD_SIGNATURES = "The signatures in this PSBT don't check out. Sign it again on your signing device."

private val PSBT_MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())
private val HEX_CHARS = Regex("^(?:[0-9a-fA-F]{2})+$")

/** A PSBT written as hex or standard base64 (whitespace ignored), or null when the text is neither. */
fun psbtBytesFromText(text: String): ByteArray? {
    val compact = text.filterNot { it.isWhitespace() }
    val bytes =
        if (HEX_CHARS.matches(compact)) {
            hexToBytes(compact)
        } else {
            runCatching { Base64.decode(compact) }.getOrNull()
        }
    return bytes?.takeIf { it.size > PSBT_MAGIC.size && it.copyOfRange(0, PSBT_MAGIC.size).contentEquals(PSBT_MAGIC) }
}

/** The signed transaction a scanned PSBT finalizes to. [outputs] are (scriptPubKey hex, sats). */
data class SignedPsbt(
    val txHex: String,
    val txid: String,
    val feeSats: Long,
    val vsize: Int,
    val outputs: List<Pair<String, Long>>,
) {
    /** Sats this transaction pays to [address]. */
    fun paidTo(address: String): Long {
        val script = runCatching { scriptHex(outputScriptFromAddress(address)) }.getOrNull()
        return outputs.filter { it.first == script }.sumOf { it.second }
    }
}

sealed class SignedPsbtCheck {
    data class Ok(
        val signed: SignedPsbt,
    ) : SignedPsbtCheck()

    data class Failed(
        val reason: String,
    ) : SignedPsbtCheck()
}

/**
 * Checks a PSBT scanned back from a signing device against the unsigned one we built, then finalizes it.
 * The same unsigned txid means the same inputs, outputs and fee. Each input takes the signer's final
 * scriptSig/witness, or one built from its single-sig signature; the coins come from our PSBT, so a signer
 * that strips them still works. Every input script is verified before the transaction is returned.
 */
fun finalizeSignedPsbt(
    unsignedPsbtHex: String,
    scanned: ByteArray,
): SignedPsbtCheck {
    val theirs = runCatching { Psbt.read(scanned).right }.getOrNull()
    val ours = Psbt.read(hexToBytes(unsignedPsbtHex)).right ?: error("the unsigned PSBT doesn't parse")
    val same = theirs?.takeIf { it.global.tx.txid == ours.global.tx.txid && it.inputs.size == ours.inputs.size }
    val spent = spentOutputs(ours)
    val signatures = same?.let { inputSignatures(scanned, it.inputs.size) }
    val tx = signatures?.let { finalTx(ours, it, spent) }
    val valid = tx != null && runCatching { Transaction.correctlySpends(tx, spent, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }.isSuccess
    return when {
        theirs == null -> SignedPsbtCheck.Failed(PSBT_NOT_A_PSBT)
        same == null -> SignedPsbtCheck.Failed(PSBT_DIFFERENT_TX)
        tx == null -> SignedPsbtCheck.Failed(PSBT_NOT_SIGNED)
        !valid -> SignedPsbtCheck.Failed(PSBT_BAD_SIGNATURES)
        else -> SignedPsbtCheck.Ok(signedPsbt(tx, ours.computeFees()?.sat ?: 0L))
    }
}

private fun signedPsbt(
    tx: Transaction,
    feeSats: Long,
): SignedPsbt =
    SignedPsbt(
        txHex = hexFromBytes(Transaction.write(tx)),
        txid = tx.txid.toString(),
        feeSats = feeSats,
        vsize = (tx.weight() + 3) / 4,
        outputs = tx.txOut.map { scriptHex(it.publicKeyScript.toByteArray()) to it.amount.sat },
    )

/** The coin each input of our PSBT spends. We always attach it (witness UTXO or previous transaction). */
private fun spentOutputs(ours: Psbt): Map<OutPoint, TxOut> =
    ours.global.tx.txIn
        .zip(ours.inputs)
        .associate { (txIn, input) ->
            val out = input.witnessUtxo ?: input.nonWitnessUtxo?.txOut?.get(txIn.outPoint.index.toInt())
            txIn.outPoint to (out ?: error("our PSBT input has no coin"))
        }

/** The unsigned transaction with every input's final scripts, or null when one has no signature. */
private fun finalTx(
    ours: Psbt,
    signatures: List<InputSignatures>,
    spent: Map<OutPoint, TxOut>,
): Transaction? {
    val txIns =
        ours.global.tx.txIn.mapIndexed { index, txIn ->
            val final = finalInput(signatures[index], spent.getValue(txIn.outPoint)) ?: return null
            txIn.copy(signatureScript = final.first, witness = final.second)
        }
    return ours.global.tx.copy(txIn = txIns)
}

/** The signer's final scriptSig and witness, or ones built from its single-sig signature. */
private fun finalInput(
    input: InputSignatures,
    spent: TxOut,
): Pair<ByteVector, ScriptWitness>? =
    if (input.scriptSig != null || input.scriptWitness != null) {
        Pair(input.scriptSig ?: ByteVector.empty, input.scriptWitness ?: ScriptWitness.empty)
    } else {
        signedInput(input, scriptTypeOfScriptPubKey(spent.publicKeyScript.toByteArray()))
    }

private fun signedInput(
    input: InputSignatures,
    type: AddressScriptType,
): Pair<ByteVector, ScriptWitness>? {
    val partial = input.partialSigs.firstOrNull()
    return when {
        type == AddressScriptType.P2TR -> input.taprootKeySignature?.let { Pair(ByteVector.empty, ScriptWitness(listOf(it))) }
        partial == null -> null
        else -> runCatching { singleSigScripts(type, PublicKey(partial.first), partial.second) }.getOrNull()
    }
}

private fun singleSigScripts(
    type: AddressScriptType,
    pub: PublicKey,
    sig: ByteVector,
): Pair<ByteVector, ScriptWitness> {
    val witness = ScriptWitness(listOf(sig, pub.value))
    return when (type) {
        AddressScriptType.P2WPKH -> Pair(ByteVector.empty, witness)
        AddressScriptType.P2SH_P2WPKH -> Pair(ByteVector(Script.write(listOf(OP_PUSHDATA(Script.write(Script.pay2wpkh(pub)))))), witness)
        else -> Pair(ByteVector(Script.write(listOf(OP_PUSHDATA(sig), OP_PUSHDATA(pub)))), ScriptWitness.empty)
    }
}
