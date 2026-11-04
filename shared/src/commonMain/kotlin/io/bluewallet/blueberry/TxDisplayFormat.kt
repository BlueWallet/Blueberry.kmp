@file:Suppress("TooManyFunctions", "ReturnCount")

package io.bluewallet.blueberry

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

internal const val TX_STRIP_CELLS = 12
internal const val TX_POSITION_GRID = 320
internal const val TX_SETTLED_CONFIRMATIONS = 6
internal const val TX_DUST_SATS = 1_000L
private const val SATS_PER_BTC = 100_000_000L

internal data class TxAmounts(
    val shownSats: Long,
    val feeSats: Long?,
)

internal fun txGroupDigits(value: Long): String {
    val negative = value < 0
    val digits = if (negative) (-value).toString() else value.toString()
    val grouped =
        digits
            .reversed()
            .chunked(3)
            .joinToString(",")
            .reversed()
    return if (negative) "-$grouped" else grouped
}

internal fun txBtcFixed(sats: Long): String {
    val whole = sats / SATS_PER_BTC
    val frac = (sats % SATS_PER_BTC).toString().padStart(8, '0')
    return "$whole.$frac"
}

internal fun txSplitZeros(sats: Long): Pair<String, String> {
    val text = txBtcFixed(sats)
    var significant = text.trimEnd('0')
    if (significant.endsWith('.')) significant += "0"
    return significant to text.substring(significant.length)
}

internal fun txAmountParts(
    sats: Long,
    received: Boolean,
): TxAmountParts {
    val (significant, trailing) = txSplitZeros(sats)
    val sign = if (received) "" else "\u2212"
    return TxAmountParts(sign, significant, trailing)
}

internal fun txSatsLabel(sats: Long): String = "${txGroupDigits(sats)} sats"

internal fun txWeight(input: TxDisplayInput): Int = input.baseBytes * 4 + input.witnessBytes

internal fun txVsize(input: TxDisplayInput): Int = ceil(txWeight(input) / 4.0).toInt()

internal fun txAmounts(input: TxDisplayInput): TxAmounts {
    val mine = input.outputs.filter { it.mine }.sumOf { it.valueSat }
    val external = input.outputs.filter { !it.mine }.sumOf { it.valueSat }
    val fee = input.walletInputs.sumOf { it.valueSat } - mine - external
    val unknown = input.direction != TxDirection.In && fee < 0
    val shown =
        when {
            input.direction == TxDirection.In -> mine
            input.direction == TxDirection.Moved && !unknown -> fee
            input.direction == TxDirection.Moved -> 0L
            else -> external
        }
    val feeSats = if (input.direction == TxDirection.In || unknown) null else fee
    return TxAmounts(shown, feeSats)
}

internal fun txAgo(
    thenEpochS: Long,
    nowEpochS: Long,
): String {
    val minutes = ((nowEpochS - thenEpochS) / 60L).toInt().coerceAtLeast(0)
    return if (minutes < 60) "$minutes min ago" else "${minutes / 60} h ago"
}

internal fun txPosition(block: TxDisplayBlock): TxPosition? {
    val count = block.txCount ?: return null
    if (count <= 0) return null
    val perCell =
        if (count < TX_POSITION_GRID) {
            1.0
        } else {
            count.toDouble() / TX_POSITION_GRID
        }
    val cell =
        if (count < TX_POSITION_GRID) {
            block.index.coerceIn(0, TX_POSITION_GRID - 1)
        } else {
            minOf(TX_POSITION_GRID - 1, floor(block.index / perCell).toInt())
        }
    return TxPosition(
        cell = cell,
        txPerCell = round(perCell).toInt().coerceAtLeast(1),
        label = "#${txGroupDigits(block.index.toLong())} of ${txGroupDigits(count.toLong())}",
        index = block.index,
        txCount = count,
    )
}

internal fun txLeadingZeros(hash: String): Int = hash.length - hash.trimStart('0').length

internal fun txShortId(
    txid: String,
    vsize: Int,
): String {
    val head = txid.take(8)
    val tail = txid.takeLast(8)
    return "$head…$tail · $vsize vB"
}
