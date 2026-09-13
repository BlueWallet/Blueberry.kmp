package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.SigVersion
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxHash
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.psbt.KeyPathWithMaster
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.secp256k1.Secp256k1
import kotlin.math.ceil

/** A UTXO the caller wants spent. `txid` is display-order hex (as shown by explorers). */
data class SendInputUtxo(
    val txid: String,
    val vout: Int,
    val valueSats: Long,
    val scriptPubKey: ByteArray,
    val nonWitnessUtxo: ByteArray? = null,
)

data class BuildSendTxParams(
    val secret: String,
    val wallet: WatchWallet,
    val utxos: List<SendInputUtxo>,
    val toAddress: String,
    val amountSats: SendAmount,
    /** sats per vbyte (may be fractional). With change: fee = ceil(rate * vsize). */
    val feeRateSatPerVb: Double,
    val changeAddress: String,
) {
    /** Redacts [secret] (mnemonic/WIF/zpub/address) so it never lands in logs or crash reports. */
    override fun toString(): String =
        "BuildSendTxParams(secret=[redacted], wallet=$wallet, utxos=$utxos, toAddress=$toAddress, " +
            "amountSats=$amountSats, feeRateSatPerVb=$feeRateSatPerVb, changeAddress=$changeAddress)"
}

data class BuildSendTxResult(
    val kind: String = "signed",
    val txHex: String,
    val txid: String,
    val feeSats: Long,
    val vsize: Int,
    val changeSats: Long,
    val changeVouts: List<Int>,
)

data class BuildSendPsbtResult(
    val kind: String = "psbt",
    val psbtHex: String,
    val txid: String,
    val feeSats: Long,
    val vsize: Int,
    val changeSats: Long,
    val changeVouts: List<Int>,
)

sealed class BuildSendResult

data class SignedSendResult(
    val txHex: String,
    val txid: String,
    val feeSats: Long,
    val vsize: Int,
    val changeSats: Long,
    val changeVouts: List<Int>,
) : BuildSendResult()

data class PsbtSendResult(
    val psbtHex: String,
    val txid: String,
    val feeSats: Long,
    val vsize: Int,
    val changeSats: Long,
    val changeVouts: List<Int>,
) : BuildSendResult()

// Matches the legacy-sized dust threshold BlueWallet/helix3 use: (inputsDust + outputDust) * 3 sat/vB.
private const val DUST_SATS = 546L
private const val SEQUENCE_FINAL = 0xffffffffL

private const val SIGNING_SECRET_REQUIRED = "signing requires a mnemonic or WIF wallet secret"
private const val LEGACY_PREV_TX_REQUIRED =
    "legacy p2pkh input requires nonWitnessUtxo (previous transaction)"

// libsecp256k1 always returns low-S signatures; we additionally grind for low-R so the DER-encoded
// signature is always exactly 70 bytes (71 with the sighash byte appended). This keeps the pre-sign
// fee/vsize estimate exactly equal to the real signed transaction's vsize, which the fee math needs.
private const val LOW_R_SIG_LEN = 71
private const val COMPRESSED_PUBKEY_LEN = 33
private const val SCHNORR_SIG_LEN = 64

/**
 * Stand-in key used only to size the unlock data of inputs whose real key is unknown (address
 * watches expose a script hash or a tweaked output key, never a public key). Same key as helix3's
 * ESTIMATION_PUBLIC_KEY: secp256k1 applied to 31 zero bytes followed by 0x01.
 */
private val ESTIMATION_PUBLIC_KEY: PublicKey =
    PrivateKey(ByteArray(32).also { it[31] = 1 }).publicKey()

private val ESTIMATION_REDEEM_SCRIPT: ByteArray = Script.write(Script.pay2wpkh(ESTIMATION_PUBLIC_KEY))

private fun pushData(data: ByteArray): ByteArray = Script.write(listOf(OP_PUSHDATA(data)))

/** Signature script an input of this type carries once signed, sized but zero-filled. */
private fun placeholderSignatureScript(scriptType: AddressScriptType): ByteArray =
    when (scriptType) {
        AddressScriptType.P2PKH ->
            pushData(ByteArray(LOW_R_SIG_LEN)) + pushData(ByteArray(COMPRESSED_PUBKEY_LEN))
        AddressScriptType.P2SH_P2WPKH -> pushData(ESTIMATION_REDEEM_SCRIPT)
        AddressScriptType.P2WPKH, AddressScriptType.P2TR -> ByteArray(0)
    }

/** Witness an input of this type carries once signed, sized but zero-filled. */
private fun placeholderWitness(scriptType: AddressScriptType): ScriptWitness =
    when (scriptType) {
        AddressScriptType.P2PKH -> ScriptWitness()
        AddressScriptType.P2SH_P2WPKH, AddressScriptType.P2WPKH ->
            ScriptWitness(
                listOf(ByteVector(ByteArray(LOW_R_SIG_LEN)), ByteVector(ByteArray(COMPRESSED_PUBKEY_LEN))),
            )
        AddressScriptType.P2TR -> ScriptWitness(listOf(ByteVector(ByteArray(SCHNORR_SIG_LEN))))
    }

private fun outPointFor(utxo: SendInputUtxo): OutPoint = OutPoint(TxHash(hexToBytes(utxo.txid).reversedArray()), utxo.vout.toLong())

private fun estimateTxIn(
    utxo: SendInputUtxo,
    scriptType: AddressScriptType,
): TxIn =
    TxIn(
        outPointFor(utxo),
        ByteVector(placeholderSignatureScript(scriptType)),
        SEQUENCE_FINAL,
        placeholderWitness(scriptType),
    )

private fun ceilDiv4(weight: Int): Int = (weight + 3) / 4

private fun watchAddressesByScript(wallet: WatchWallet): Map<String, WatchAddress> = wallet.addresses.associateBy { scriptHex(it.scriptPubKey) }

private fun addressForScript(
    byScript: Map<String, WatchAddress>,
    scriptPubKey: ByteArray,
): WatchAddress =
    byScript[scriptHex(scriptPubKey)]
        ?: throw IllegalArgumentException("UTXO is not a watched address")

/** Bech32 is case-insensitive; base58 is not. */
private fun addressesEqual(
    a: String,
    b: String,
): Boolean {
    val aBech32 = a.lowercase().startsWith("bc1")
    val bBech32 = b.lowercase().startsWith("bc1")
    return if (aBech32 && bBech32) a.lowercase() == b.lowercase() else a == b
}

private fun derLowR(
    hash: ByteArray,
    privateKey: PrivateKey,
): ByteArray {
    val keyBytes = privateKey.value.toByteArray()
    var compact = Secp256k1.sign(hash, keyBytes)
    var counter = 0
    while ((compact[0].toInt() and 0x80) != 0) {
        require(counter < 1_000_000) { "low-R grinding failed to converge" }
        val extraEntropy = ByteArray(32)
        extraEntropy[0] = (counter and 0xff).toByte()
        extraEntropy[1] = ((counter ushr 8) and 0xff).toByte()
        extraEntropy[2] = ((counter ushr 16) and 0xff).toByte()
        extraEntropy[3] = ((counter ushr 24) and 0xff).toByte()
        compact = Secp256k1.sign(hash, keyBytes, extraEntropy)
        counter++
    }
    return Secp256k1.compact2der(compact) + SigHash.SIGHASH_ALL.toByte()
}

private fun signWitnessV0LowR(
    tx: Transaction,
    inputIndex: Int,
    scriptCode: ByteArray,
    amount: Satoshi,
    privateKey: PrivateKey,
): ByteArray =
    derLowR(
        tx.hashForSigning(inputIndex, scriptCode, SigHash.SIGHASH_ALL, amount, SigVersion.SIGVERSION_WITNESS_V0),
        privateKey,
    )

private fun signLegacyLowR(
    tx: Transaction,
    inputIndex: Int,
    previousOutputScript: ByteArray,
    privateKey: PrivateKey,
): ByteArray =
    derLowR(
        tx.hashForSigning(inputIndex, previousOutputScript, SigHash.SIGHASH_ALL),
        privateKey,
    )

/**
 * The key material a wallet secret gives us. Mnemonics and WIFs can sign; zpub and single-address
 * watches can only describe their inputs, so they stop at an unsigned PSBT. Mnemonics and zpubs
 * also supply PSBT origin metadata (account fingerprint + derivation path).
 */
private sealed class SendAccount {
    data class Mnemonic(
        val master: DeterministicWallet.ExtendedPrivateKey,
    ) : SendAccount()

    data class Zpub(
        val account: DeterministicWallet.ExtendedPublicKey,
    ) : SendAccount()

    data class Wif(
        val privateKey: PrivateKey,
    ) : SendAccount()

    data object AddressWatch : SendAccount()
}

private fun sendAccountFor(parsed: ParsedWalletSecret): SendAccount =
    when (parsed.kind) {
        WalletSecretKind.MNEMONIC ->
            SendAccount.Mnemonic(DeterministicWallet.generate(MnemonicCode.toSeed(parsed.value, "")))
        WalletSecretKind.ZPUB ->
            SendAccount.Zpub(DeterministicWallet.ExtendedPublicKey.decode(parsed.value).second)
        WalletSecretKind.WIF ->
            SendAccount.Wif(PrivateKey(decodeWifPrivateKey(parsed.value)))
        WalletSecretKind.ADDRESS -> SendAccount.AddressWatch
    }

private fun SendAccount.fingerprint(): Long? =
    when (this) {
        is SendAccount.Mnemonic -> master.fingerprint()
        is SendAccount.Zpub -> account.fingerprint()
        is SendAccount.Wif, SendAccount.AddressWatch -> null
    }

/** zpub watches use a chain/index path relative to the account; mnemonics use the full BIP84 path. */
private fun relativePathNums(addr: WatchAddress): List<Long> = listOf(if (addr.change) 1L else 0L, addr.index.toLong())

private fun SendAccount.publicKeyAt(addr: WatchAddress): PublicKey? =
    when (this) {
        is SendAccount.Mnemonic -> master.derivePrivateKey(addr.path).publicKey
        is SendAccount.Zpub -> account.derivePublicKey(relativePathNums(addr)).publicKey
        is SendAccount.Wif -> privateKey.publicKey()
        SendAccount.AddressWatch -> null
    }

private fun SendAccount.bip32PathNums(addr: WatchAddress): List<Long>? =
    when (this) {
        is SendAccount.Mnemonic -> KeyPath.computePath(addr.path)
        is SendAccount.Zpub -> relativePathNums(addr)
        is SendAccount.Wif, SendAccount.AddressWatch -> null
    }

private fun SendAccount.privateKeyFor(signPath: String): PrivateKey =
    when (this) {
        is SendAccount.Mnemonic -> master.derivePrivateKey(signPath).privateKey
        is SendAccount.Wif -> privateKey
        is SendAccount.Zpub, SendAccount.AddressWatch ->
            throw IllegalArgumentException(SIGNING_SECRET_REQUIRED)
    }

private data class DraftInput(
    val utxo: SendInputUtxo,
    val scriptType: AddressScriptType,
    val signPath: String,
    val publicKey: PublicKey?,
    val bip32Path: List<Long>?,
)

private data class DraftSendTx(
    val unsignedTx: Transaction,
    val inputs: List<DraftInput>,
    val account: SendAccount,
    val vsize: Int,
    val feeSats: Long,
    val changeSats: Long,
    val changeVouts: List<Int>,
)

internal fun changeOutputVouts(
    tx: Transaction,
    changeAddress: String,
    toAddress: String,
    paymentAmount: SendAmount,
): List<Int> {
    if (paymentAmount is SendAmount.Max) return emptyList()
    val amount = (paymentAmount as SendAmount.Exact).sats
    val changeScript = outputScriptFromAddress(changeAddress)
    val destIsChange = addressesEqual(toAddress, changeAddress)
    val vouts = mutableListOf<Int>()
    tx.txOut.forEachIndexed { index, out ->
        if (!out.publicKeyScript.toByteArray().contentEquals(changeScript)) return@forEachIndexed
        if (destIsChange && out.amount.toLong() == amount) return@forEachIndexed
        vouts.add(index)
    }
    return vouts
}

/**
 * Shared draft transaction builder for signed sends and unsigned PSBTs. All caller UTXOs are spent
 * (an uneconomical one aborts the whole send); selection uses ceil(rate) sat/vB, then any excess is
 * moved into the change output so the reported fee becomes exactly ceil(rate * vsize). When the
 * destination equals the change address, the payment output is left untouched by that adjustment.
 */
private fun buildDraftSendTx(params: BuildSendTxParams): DraftSendTx {
    if (params.utxos.isEmpty()) throw IllegalArgumentException("no UTXOs selected")
    val sendMax = params.amountSats is SendAmount.Max
    val amount = (params.amountSats as? SendAmount.Exact)?.sats ?: 0L
    if (!sendMax && amount <= 0L) throw IllegalArgumentException("amount must be positive")
    if (!params.feeRateSatPerVb.isFinite() || params.feeRateSatPerVb <= 0.0) {
        throw IllegalArgumentException("fee rate must be positive")
    }
    if (!isAddressValid(params.toAddress)) throw IllegalArgumentException("invalid destination address")
    if (!sendMax && !isAddressValid(params.changeAddress)) throw IllegalArgumentException("invalid change address")

    val parsedSecret = parseWalletSecret(params.secret)
    val account = sendAccountFor(parsedSecret)

    // A single-address watch has nowhere to put change except the address it is watching, so a
    // partial send to that same address would collapse both outputs onto it.
    if (account is SendAccount.AddressWatch && !sendMax) {
        val watched =
            params.wallet.addresses.firstOrNull()
                ?: throw IllegalArgumentException("address wallet missing watched address")
        if (addressesEqual(params.toAddress, watched.address)) {
            throw IllegalArgumentException("cannot send back to the watched address")
        }
    }

    val byScript = watchAddressesByScript(params.wallet)
    val watchAddresses = params.utxos.map { addressForScript(byScript, it.scriptPubKey) }
    val scriptTypes = watchAddresses.map { it.resolvedScriptType() }

    params.utxos.forEachIndexed { index, utxo ->
        if (scriptTypes[index] == AddressScriptType.P2PKH && utxo.nonWitnessUtxo == null) {
            throw IllegalArgumentException(LEGACY_PREV_TX_REQUIRED)
        }
    }

    val feePerByteInt = ceil(params.feeRateSatPerVb).toLong()
    val estimateInputs = params.utxos.mapIndexed { index, utxo -> estimateTxIn(utxo, scriptTypes[index]) }
    estimateInputs.forEachIndexed { index, txIn ->
        val inputVsize = ceilDiv4(txIn.weight())
        if (params.utxos[index].valueSats <= feePerByteInt * inputVsize) {
            throw IllegalArgumentException("some selected UTXOs are uneconomical at this fee rate")
        }
    }

    val toScript = outputScriptFromAddress(params.toAddress)
    val changeScript = if (sendMax) toScript else outputScriptFromAddress(params.changeAddress)
    val inputSum = params.utxos.sumOf { it.valueSats }

    val preChangeOutputs = if (sendMax) emptyList() else listOf(TxOut(Satoshi(amount), toScript))

    val weightWithoutChange = Transaction(2L, estimateInputs, preChangeOutputs, 0L).weight()
    val vsizeWithoutChange = ceilDiv4(weightWithoutChange)
    val feeWithoutChange = feePerByteInt * vsizeWithoutChange

    val weightWithChange =
        Transaction(
            2L,
            estimateInputs,
            preChangeOutputs + listOf(TxOut(Satoshi(0L), changeScript)),
            0L,
        ).weight()
    val vsizeWithChange = ceilDiv4(weightWithChange)
    val feeWithChange = feePerByteInt * vsizeWithChange

    val outputSumBeforeChange = if (sendMax) 0L else amount
    val changeCandidate = inputSum - outputSumBeforeChange - feeWithChange
    val needChange = changeCandidate > DUST_SATS

    val selectedVsize: Int
    val selectedFeeInt: Long
    val draftOutputs: List<TxOut>
    if (needChange) {
        selectedVsize = vsizeWithChange
        selectedFeeInt = feeWithChange
        draftOutputs = preChangeOutputs + listOf(TxOut(Satoshi(changeCandidate), changeScript))
    } else {
        selectedVsize = vsizeWithoutChange
        selectedFeeInt = feeWithoutChange
        draftOutputs = preChangeOutputs
    }

    if (sendMax && draftOutputs.size != 1) {
        throw IllegalArgumentException("insufficient funds for amount and fee")
    }

    // Integer sat/vB selection, then move the excess into change so the fee becomes
    // ceil(rate * vsize). When dest === change, skip the payment output so it stays exact.
    val targetFee = ceil(params.feeRateSatPerVb * selectedVsize).toLong()
    val excess = selectedFeeInt - targetFee
    val skipPaymentAmount = if (!sendMax && toScript.contentEquals(changeScript)) amount else null

    var appliedExcess = false
    val finalOutputs =
        if (excess > 0) {
            draftOutputs.map { out ->
                if (!appliedExcess &&
                    out.publicKeyScript.toByteArray().contentEquals(changeScript) &&
                    (skipPaymentAmount == null || out.amount.toLong() != skipPaymentAmount)
                ) {
                    appliedExcess = true
                    out.copy(amount = Satoshi(out.amount.toLong() + excess))
                } else {
                    out
                }
            }
        } else {
            draftOutputs
        }

    if (!sendMax) {
        val checkFee = if (appliedExcess) targetFee else selectedFeeInt
        if (inputSum < amount + checkFee) {
            throw IllegalArgumentException("insufficient funds for amount and fee")
        }
    }

    val unsignedInputs = params.utxos.map { TxIn(outPointFor(it), SEQUENCE_FINAL) }
    val unsignedTx = Transaction(2L, unsignedInputs, finalOutputs, 0L)

    val draftInputs =
        params.utxos.mapIndexed { index, utxo ->
            val addr = watchAddresses[index]
            DraftInput(
                utxo = utxo,
                scriptType = scriptTypes[index],
                signPath = addr.path,
                publicKey = account.publicKeyAt(addr),
                bip32Path = account.bip32PathNums(addr),
            )
        }

    val outputSum = finalOutputs.sumOf { it.amount.toLong() }
    val feeSats = inputSum - outputSum

    val changeSats =
        if (sendMax) {
            0L
        } else {
            var matched = 0L
            for (out in finalOutputs) {
                if (out.publicKeyScript.toByteArray().contentEquals(changeScript)) matched += out.amount.toLong()
            }
            if (toScript.contentEquals(changeScript)) {
                val change = matched - amount
                if (change > 0L) change else 0L
            } else {
                matched
            }
        }

    return DraftSendTx(
        unsignedTx = unsignedTx,
        inputs = draftInputs,
        account = account,
        vsize = selectedVsize,
        feeSats = feeSats,
        changeSats = changeSats,
        changeVouts = changeOutputVouts(unsignedTx, params.changeAddress, params.toAddress, params.amountSats),
    )
}

/**
 * Build and sign a mainnet send. Requires a mnemonic (BIP84 p2wpkh) or WIF wallet secret; a WIF
 * signs p2pkh, p2sh-p2wpkh, p2wpkh and p2tr key-path inputs with the same key.
 */
fun buildSignedSendTx(params: BuildSendTxParams): BuildSendTxResult {
    val parsedSecret = parseWalletSecret(params.secret)
    if (parsedSecret.kind != WalletSecretKind.MNEMONIC && parsedSecret.kind != WalletSecretKind.WIF) {
        throw IllegalArgumentException(SIGNING_SECRET_REQUIRED)
    }
    val draft = buildDraftSendTx(params)
    val spentOutputs = draft.inputs.map { TxOut(Satoshi(it.utxo.valueSats), it.utxo.scriptPubKey) }

    val signedInputs =
        draft.inputs.mapIndexed { index, input ->
            val privateKey = draft.account.privateKeyFor(input.signPath)
            val publicKey = privateKey.publicKey()
            val txIn = draft.unsignedTx.txIn[index]
            val amount = Satoshi(input.utxo.valueSats)
            when (input.scriptType) {
                AddressScriptType.P2PKH -> {
                    val sig = signLegacyLowR(draft.unsignedTx, index, input.utxo.scriptPubKey, privateKey)
                    txIn.updateSignatureScript(pushData(sig) + pushData(publicKey.value.toByteArray()))
                }
                AddressScriptType.P2SH_P2WPKH -> {
                    val scriptCode = Script.write(Script.pay2pkh(publicKey))
                    val sig = signWitnessV0LowR(draft.unsignedTx, index, scriptCode, amount, privateKey)
                    txIn
                        .updateSignatureScript(pushData(Script.write(Script.pay2wpkh(publicKey))))
                        .updateWitness(ScriptWitness(listOf(ByteVector(sig), publicKey.value)))
                }
                AddressScriptType.P2WPKH -> {
                    val scriptCode = Script.write(Script.pay2pkh(publicKey))
                    val sig = signWitnessV0LowR(draft.unsignedTx, index, scriptCode, amount, privateKey)
                    txIn.updateWitness(ScriptWitness(listOf(ByteVector(sig), publicKey.value)))
                }
                AddressScriptType.P2TR -> {
                    val sig =
                        draft.unsignedTx.signInputTaprootKeyPath(
                            privateKey,
                            index,
                            spentOutputs,
                            SigHash.SIGHASH_DEFAULT,
                            null,
                        )
                    txIn.updateWitness(Script.witnessKeyPathPay2tr(sig))
                }
            }
        }
    val signedTx = draft.unsignedTx.copy(txIn = signedInputs)

    return BuildSendTxResult(
        txHex = hexFromBytes(Transaction.write(signedTx)),
        txid = signedTx.txid.toString(),
        feeSats = draft.feeSats,
        vsize = ceilDiv4(signedTx.weight()),
        changeSats = draft.changeSats,
        changeVouts = draft.changeVouts,
    )
}

/**
 * Build an unsigned PSBT (no signing). Works with any wallet secret. Each input carries the data a
 * signer needs and nothing more: legacy inputs get the full previous transaction, segwit inputs get
 * their spent output, and mnemonic/zpub inputs get their BIP32 origin. A single-address watch knows
 * no public key, so its inputs stay script-only (no redeemScript, no tapInternalKey).
 */
fun buildUnsignedSendPsbt(params: BuildSendTxParams): BuildSendPsbtResult {
    val draft = buildDraftSendTx(params)
    val fingerprint = draft.account.fingerprint()

    var psbt = Psbt(draft.unsignedTx)
    draft.inputs.forEachIndexed { index, input ->
        val outPoint = draft.unsignedTx.txIn[index].outPoint
        val txOut = TxOut(Satoshi(input.utxo.valueSats), input.utxo.scriptPubKey)
        val derivationPaths =
            if (input.publicKey != null && input.bip32Path != null && fingerprint != null) {
                mapOf(input.publicKey to KeyPathWithMaster(fingerprint, KeyPath(input.bip32Path)))
            } else {
                emptyMap()
            }
        val updated =
            when (input.scriptType) {
                AddressScriptType.P2PKH -> {
                    val prevTx =
                        Transaction.read(
                            input.utxo.nonWitnessUtxo ?: throw IllegalArgumentException(LEGACY_PREV_TX_REQUIRED),
                        )
                    psbt.updateNonWitnessInput(prevTx, input.utxo.vout, derivationPaths = derivationPaths)
                }
                AddressScriptType.P2SH_P2WPKH ->
                    psbt.updateWitnessInput(
                        outPoint,
                        txOut,
                        redeemScript = input.publicKey?.let { Script.pay2wpkh(it) },
                        derivationPaths = derivationPaths,
                    )
                AddressScriptType.P2WPKH ->
                    psbt.updateWitnessInput(
                        outPoint,
                        txOut,
                        derivationPaths = derivationPaths,
                    )
                AddressScriptType.P2TR ->
                    psbt.updateWitnessInput(
                        outPoint,
                        txOut,
                        derivationPaths = derivationPaths,
                        taprootInternalKey = input.publicKey?.let { XonlyPublicKey(it) },
                    )
            }
        psbt = updated.right ?: throw IllegalStateException("failed to update PSBT input $index: ${updated.left}")
    }

    return BuildSendPsbtResult(
        psbtHex = hexFromBytes(Psbt.write(psbt).toByteArray()),
        txid = draft.unsignedTx.txid.toString(),
        feeSats = draft.feeSats,
        vsize = draft.vsize,
        changeSats = draft.changeSats,
        changeVouts = draft.changeVouts,
    )
}

/**
 * Mnemonic/WIF secrets sign; zpub/address watches produce an unsigned PSBT. When sending a
 * non-max amount from an address watch, change is forced back to the single watched address.
 */
fun buildSend(params: BuildSendTxParams): BuildSendResult {
    val parsed = parseWalletSecret(params.secret)
    if (parsed.kind == WalletSecretKind.MNEMONIC || parsed.kind == WalletSecretKind.WIF) {
        val result = buildSignedSendTx(params)
        return SignedSendResult(
            txHex = result.txHex,
            txid = result.txid,
            feeSats = result.feeSats,
            vsize = result.vsize,
            changeSats = result.changeSats,
            changeVouts = result.changeVouts,
        )
    }

    val effectiveParams =
        if (parsed.kind == WalletSecretKind.ADDRESS && params.amountSats !is SendAmount.Max) {
            val watched =
                params.wallet.addresses.firstOrNull()
                    ?: throw IllegalArgumentException("address wallet missing watched address")
            params.copy(changeAddress = watched.address)
        } else {
            params
        }
    val result = buildUnsignedSendPsbt(effectiveParams)
    return PsbtSendResult(
        psbtHex = result.psbtHex,
        txid = result.txid,
        feeSats = result.feeSats,
        vsize = result.vsize,
        changeSats = result.changeSats,
        changeVouts = result.changeVouts,
    )
}
