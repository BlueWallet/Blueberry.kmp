package io.bluewallet.blueberry

import io.bluewallet.blueberry.parse.usedWatchIndexes
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.wallet.AddressScriptType
import io.bluewallet.blueberry.wallet.HD_SCRIPT_TYPES
import io.bluewallet.blueberry.wallet.SendAmount
import io.bluewallet.blueberry.wallet.SendInputUtxo
import io.bluewallet.blueberry.wallet.Wallet
import io.bluewallet.blueberry.wallet.WatchWalletKind
import io.bluewallet.blueberry.wallet.WifReceiveTxRow
import io.bluewallet.blueberry.wallet.estimateSendFeeSats
import io.bluewallet.blueberry.wallet.pickMnemonicChange
import io.bluewallet.blueberry.wallet.preferredWifReceiveAddress
import kotlin.math.ceil
import kotlin.random.Random

/** Change at or under this goes to the fee, as in `buildSignedSendTx`. */
const val SEND_DUST_SATS = 546L

sealed class SendFeeEstimate {
    data class Ok(
        val paidSats: Long,
        val changeSats: Long,
        val feeSats: Long,
        val vsize: Int,
    ) : SendFeeEstimate()

    data class Short(
        val missingSats: Long,
    ) : SendFeeEstimate()
}

/** Same length as a real output script of [type]; only the size matters for fee estimates. */
fun placeholderOutputScript(type: AddressScriptType): ByteArray =
    when (type) {
        AddressScriptType.P2PKH -> ByteArray(25)
        AddressScriptType.P2SH_P2WPKH -> ByteArray(23)
        AddressScriptType.P2WPKH -> ByteArray(22)
        AddressScriptType.P2TR -> ByteArray(34)
    }

private fun vsizeOf(
    utxos: List<SendInputUtxo>,
    outputs: List<ByteArray>,
): Int = estimateSendFeeSats(utxos, 1.0, outputs).toInt()

private fun feeAt(
    rate: Double,
    vsize: Int,
): Long = ceil(rate * vsize).toLong()

/**
 * Fee, change and paid amount `buildSignedSendTx` would produce for these coins, without signing.
 * Max sends everything minus the fee to [toScript]; otherwise change only survives above dust.
 */
fun sendFeeEstimate(
    utxos: List<SendInputUtxo>,
    amount: SendAmount,
    rate: Double,
    toScript: ByteArray,
    changeScript: ByteArray,
): SendFeeEstimate {
    val inputSum = utxos.sumOf { it.valueSats }
    val exact = (amount as? SendAmount.Exact)?.sats
    val withChange = vsizeOf(utxos, if (exact == null) listOf(toScript) else listOf(toScript, changeScript))
    val feeWithChange = feeAt(rate, withChange)
    val candidate = inputSum - (exact ?: 0L) - feeWithChange
    val withoutChange = vsizeOf(utxos, listOf(toScript))
    val feeWithoutChange = feeAt(rate, withoutChange)
    return when {
        candidate > SEND_DUST_SATS && exact == null -> SendFeeEstimate.Ok(candidate, 0L, feeWithChange, withChange)
        candidate > SEND_DUST_SATS -> SendFeeEstimate.Ok(exact!!, candidate, feeWithChange, withChange)
        exact == null -> SendFeeEstimate.Short(SEND_DUST_SATS + 1 - candidate)
        inputSum >= exact + feeWithoutChange -> SendFeeEstimate.Ok(exact, 0L, inputSum - exact, withoutChange)
        else -> SendFeeEstimate.Short(exact + feeWithoutChange - inputSum)
    }
}

data class SendCoverage(
    val selectedSats: Long,
    val neededSats: Long,
    val covered: Boolean,
)

/**
 * Coins step bar: what the picked coins hold against the amount plus a fee at [rate].
 * Without a remembered rate the fee is left out; Review asks for one.
 */
fun sendCoverage(
    utxos: List<SendInputUtxo>,
    amount: SendAmount,
    rate: Double?,
    toScript: ByteArray,
    changeScript: ByteArray,
): SendCoverage {
    val selected = utxos.sumOf { it.valueSats }
    val bare = (amount as? SendAmount.Exact)?.sats ?: (SEND_DUST_SATS + 1)
    if (utxos.isEmpty() || rate == null) return SendCoverage(selected, bare, utxos.isNotEmpty() && selected >= bare)
    return when (val est = sendFeeEstimate(utxos, amount, rate, toScript, changeScript)) {
        is SendFeeEstimate.Ok ->
            SendCoverage(selected, if (amount is SendAmount.Max) est.feeSats + bare else est.paidSats + est.feeSats, true)
        is SendFeeEstimate.Short -> SendCoverage(selected, selected + est.missingSats, false)
    }
}

/**
 * The change script `buildActiveSendTx` will use when given `Random(seed)`, so estimates
 * and every rebuild in one send agree. Unknown cases fall back to a native SegWit size.
 */
fun sendChangeScript(
    db: Database,
    wallet: Wallet,
    seed: Long,
): ByteArray =
    runCatching {
        val watch = wallet.snapshot()
        when (watch.kind) {
            WatchWalletKind.WIF ->
                preferredWifReceiveAddress(
                    watch,
                    db.transactions.list().map { WifReceiveTxRow(it.height, it.txIndex, it.tx) },
                ).scriptPubKey
            WatchWalletKind.ADDRESS -> watch.addresses.first().scriptPubKey
            WatchWalletKind.BIP84 -> {
                val used = usedWatchIndexes(db.transactions.list().map { it.tx }, watch)
                val pick = pickMnemonicChange(watch, HD_SCRIPT_TYPES.associateWith { used.get(it).internal }, Random(seed))
                pick.address?.scriptPubKey ?: placeholderOutputScript(pick.scriptType)
            }
        }
    }.getOrElse { placeholderOutputScript(AddressScriptType.P2WPKH) }
