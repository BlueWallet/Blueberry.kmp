package io.bluewallet.blueberry

import io.bluewallet.blueberry.wallet.BuildSendResult
import io.bluewallet.blueberry.wallet.PsbtSendResult
import io.bluewallet.blueberry.wallet.SendAmount
import io.bluewallet.blueberry.wallet.SignedSendResult

/** What the recipient gets: the exact amount, or for Max everything left after fee and change. */
fun sendPaidSats(
    amount: SendAmount,
    inputSum: Long,
    feeSats: Long,
    changeSats: Long,
): Long =
    when (amount) {
        is SendAmount.Max -> inputSum - feeSats - changeSats
        is SendAmount.Exact -> amount.sats
    }

data class BuiltSend(
    val txid: String,
    val feeSats: Long,
    val changeSats: Long,
    val vsize: Int,
    val changeVouts: List<Int>,
)

fun builtSend(result: BuildSendResult): BuiltSend =
    when (result) {
        is SignedSendResult -> BuiltSend(result.txid, result.feeSats, result.changeSats, result.vsize, result.changeVouts)
        is PsbtSendResult -> BuiltSend(result.txid, result.feeSats, result.changeSats, result.vsize, result.changeVouts)
    }

data class TxSummaryInput(
    val key: String,
    val valueSats: Long,
    val title: String?,
    val argb: Long,
)

enum class TxOutputKind { Recipient, Change, Fee }

data class TxSummaryOutput(
    val kind: TxOutputKind,
    val sats: Long,
    val label: String,
)

data class TxSummary(
    val inputs: List<TxSummaryInput>,
    val outputs: List<TxSummaryOutput>,
    val paidSats: Long,
    val feeSats: Long,
    val changeSats: Long,
    val vsize: Int,
) {
    /** Outputs on chain: the fee row isn't one. */
    val chainOutputs: Int get() = outputs.count { it.kind != TxOutputKind.Fee }
    val totalLeavingSats: Long get() = paidSats + feeSats
}

/** Review lists, straight from the built transaction: coins in, then recipient, change and fee. */
fun txSummary(
    coins: List<WalletUtxoRow>,
    amount: SendAmount,
    built: BuiltSend,
    recipientLabel: String,
): TxSummary {
    val inputSum = coins.sumOf { it.valueSats }
    val paid = sendPaidSats(amount, inputSum, built.feeSats, built.changeSats)
    val inputs =
        coins
            .sortedByDescending { it.valueSats }
            .map { TxSummaryInput(it.key, it.valueSats, utxoRowTitle(it.name, it.address)?.text, utxoCircleArgb(it.key)) }
    val outputs =
        listOfNotNull(
            TxSummaryOutput(TxOutputKind.Recipient, paid, recipientLabel),
            if (built.changeSats > 0) TxSummaryOutput(TxOutputKind.Change, built.changeSats, "Your change") else null,
            TxSummaryOutput(TxOutputKind.Fee, built.feeSats, "Network fee"),
        )
    return TxSummary(inputs, outputs, paid, built.feeSats, built.changeSats, built.vsize)
}

private const val FEE_WARNING_PERCENT = 10

/** Amber line when the fee is more than a tenth of what the recipient gets. Warns, never blocks. */
fun feeShareWarning(
    feeSats: Long,
    paidSats: Long,
): String? {
    if (paidSats <= 0L || feeSats * 100 <= paidSats * FEE_WARNING_PERCENT) return null
    val percent = (feeSats * 100 + paidSats / 2) / paidSats
    return "The fee is $percent% of what you're sending."
}

sealed class FeeDraftCheck {
    data object Invalid : FeeDraftCheck()

    data class Short(
        val missingSats: Long,
    ) : FeeDraftCheck()

    data class Ok(
        val rate: Double,
        val estimate: SendFeeEstimate.Ok,
    ) : FeeDraftCheck()
}

/** Fee sheet: whether the typed rate can be applied to these coins. */
fun checkFeeDraft(
    draft: String,
    estimate: (Double) -> SendFeeEstimate,
): FeeDraftCheck {
    val rate = parseFeeRateSatPerVb(draft) ?: return FeeDraftCheck.Invalid
    return when (val est = estimate(rate)) {
        is SendFeeEstimate.Ok -> FeeDraftCheck.Ok(rate, est)
        is SendFeeEstimate.Short -> FeeDraftCheck.Short(est.missingSats)
    }
}
