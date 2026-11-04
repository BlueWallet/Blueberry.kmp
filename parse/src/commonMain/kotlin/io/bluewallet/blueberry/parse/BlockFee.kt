package io.bluewallet.blueberry.parse

import fr.acinq.bitcoin.Transaction

private const val HALVING_INTERVAL = 210_000
private const val INITIAL_SUBSIDY_SATS = 5_000_000_000L

/** Coinbase subsidy at [height], before fees. */
fun blockSubsidySats(height: Int): Long {
    val halvings = if (height < 0) 64 else height / HALVING_INTERVAL
    return if (halvings >= 64) 0L else INITIAL_SUBSIDY_SATS shr halvings
}

/**
 * Fee rate of the block: coinbase outputs minus the subsidy, divided by the virtual
 * size of every transaction except the coinbase. One pass over a block already stored
 * for this wallet. Null when the blob cannot be read or the coinbase pays less than the subsidy.
 */
fun blockFeeRateLabel(
    blockBytes: ByteArray,
    height: Int,
): String? {
    val fees = blockPayingFees(blockBytes, height)
    return fees?.let { formatSatPerVb(it.sats, it.vsize) }
}

private class PayingFees(
    val sats: Long,
    val vsize: Int,
)

private fun blockPayingFees(
    blockBytes: ByteArray,
    height: Int,
): PayingFees? = runCatching { payingFees(decodeBlockTxs(blockBytes), height) }.getOrNull()

private fun payingFees(
    txs: List<Transaction>,
    height: Int,
): PayingFees? {
    val coinbase = txs.firstOrNull()?.takeIf { it.isCoinbase() }
    val paying = if (coinbase == null) emptyList() else txs.drop(1)
    val weight = paying.sumOf { it.weight() }
    val vsize = (weight + 3) / 4
    val fees = (coinbase?.txOut?.sumOf { it.amount.toLong() } ?: 0L) - blockSubsidySats(height)
    val priced = coinbase != null && paying.isNotEmpty()
    val sized = fees >= 0L && vsize > 0
    return if (priced && sized) PayingFees(fees, vsize) else null
}
