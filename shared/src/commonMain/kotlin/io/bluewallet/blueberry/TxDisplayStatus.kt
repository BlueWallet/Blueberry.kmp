@file:Suppress("ReturnCount")

package io.bluewallet.blueberry

import kotlin.math.round

internal fun txStatus(input: TxDisplayInput): TxStatus {
    val failed = txFailedStatus(input)
    if (failed != null) return failed
    val block = input.block
    if (block == null) return txPendingStatus(input)
    if (input.tipHeight == null || input.tipHeight < block.height) return txSyncingStatus(block)
    return txConfirmedStatus(block, input.tipHeight)
}

private fun txFailedStatus(input: TxDisplayInput): TxStatus? {
    if (input.outcome == TxOutcome.None) return null
    val title = if (input.outcome == TxOutcome.Replaced) "Replaced" else "Dropped"
    return TxStatus(
        title = title,
        sub = "This transaction will not confirm.",
        badge = null,
        badgeTone = TxTone.Amber,
        cells = List(TX_STRIP_CELLS) { TxStripCell.Empty },
        left = title,
        right = null,
        note = "",
        speedUp = false,
        failed = true,
        replacementTxid = input.replacementTxid,
    )
}

private fun txPendingStatus(input: TxDisplayInput): TxStatus {
    val own = input.direction != TxDirection.In && input.ownSend
    val cells = listOf(TxStripCell.Pending) + List(TX_STRIP_CELLS - 1) { TxStripCell.Empty }
    val sub =
        if (own) {
            val whenEpoch = input.broadcastEpochS ?: input.nowEpochS
            val rate = txPendingRate(input)
            val ago = txAgo(whenEpoch, input.nowEpochS)
            if (rate == null) "Broadcast $ago" else "Broadcast $ago · $rate sat/vB"
        } else {
            val whenEpoch = input.broadcastEpochS ?: input.nowEpochS
            "Seen ${txAgo(whenEpoch, input.nowEpochS)}"
        }
    val note =
        if (own) {
            "Until it's in a block it can still be replaced or dropped. Pay a higher fee to move it up the queue."
        } else {
            "The sender can still replace it. Don't count it as received until it confirms."
        }
    return TxStatus(
        title = "Waiting for a block",
        sub = sub,
        badge = "Pending",
        badgeTone = TxTone.Blue,
        cells = cells,
        left = "Next block",
        right = null,
        note = note,
        speedUp = own,
    )
}

private fun txPendingRate(input: TxDisplayInput): Int? {
    val fee = txAmounts(input).feeSats ?: return null
    val vsize = txVsize(input)
    if (vsize <= 0) return null
    return round(fee.toDouble() / vsize).toInt()
}

private fun txSyncingStatus(block: TxDisplayBlock): TxStatus =
    TxStatus(
        title = "Confirmed",
        sub = "In block ${txGroupDigits(block.height.toLong())}",
        badge = null,
        badgeTone = TxTone.Blue,
        cells = confirmationCells(1),
        left = "Your block · ${txGroupDigits(block.height.toLong())}",
        right = "Syncing…",
        note = "",
        speedUp = false,
    )

private fun txConfirmedStatus(
    block: TxDisplayBlock,
    tip: Int,
): TxStatus {
    val conf = tip - block.height + 1
    val badge =
        if (conf >= TX_SETTLED_CONFIRMATIONS) {
            "Settled"
        } else {
            "${TX_SETTLED_CONFIRMATIONS - conf} to go"
        }
    val tone = if (conf >= TX_SETTLED_CONFIRMATIONS) TxTone.Green else TxTone.Blue
    val word = if (conf == 1) "confirmation" else "confirmations"
    val right =
        if (conf > TX_STRIP_CELLS) {
            "+${txGroupDigits((conf - TX_STRIP_CELLS).toLong())} more on top"
        } else {
            "Latest · ${txGroupDigits(tip.toLong())}"
        }
    return TxStatus(
        title = "Confirmed",
        sub = "${txGroupDigits(conf.toLong())} $word",
        badge = badge,
        badgeTone = tone,
        cells = confirmationCells(conf),
        left = "Your block · ${txGroupDigits(block.height.toLong())}",
        right = right,
        note = confirmationNote(conf),
        speedUp = false,
    )
}

private fun confirmationCells(conf: Int): List<TxStripCell> =
    List(TX_STRIP_CELLS) { index ->
        when {
            index == 0 -> TxStripCell.Block
            index < conf -> TxStripCell.Confirmed
            else -> TxStripCell.Empty
        }
    }

private fun confirmationNote(conf: Int): String =
    when {
        conf <= 1 -> "Just mined. Five more blocks on top is the usual bar for final."
        conf == 2 -> "1 block has been built on top of yours. Six is the usual bar for final."
        conf <= TX_STRIP_CELLS ->
            "${conf - 1} blocks have been built on top of yours. Six is the usual bar for final."
        else -> "Buried under ${txGroupDigits((conf - 1).toLong())} newer blocks."
    }
