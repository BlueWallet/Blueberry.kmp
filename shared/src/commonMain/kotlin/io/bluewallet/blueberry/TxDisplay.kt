@file:Suppress("TooManyFunctions", "ReturnCount")

package io.bluewallet.blueberry

private const val TX_LIST_STRIP = 6

fun txScreen(input: TxDisplayInput): TxScreen {
    val detail = txDetail(input)
    return TxScreen(detail, txListRow(input, detail))
}

internal fun txDetail(input: TxDisplayInput): TxDetail {
    val amounts = txAmounts(input)
    val received = input.direction == TxDirection.In
    val whenTime = input.block?.mined ?: input.broadcastAt
    val dated = whenTime?.let { txWhenLabel(it, input.now) }.orEmpty()
    val external = input.outputs.filter { !it.mine }
    val heroSub = if (input.direction == TxDirection.Moved) "Moved between your coins" else ""
    val to = if (received || input.direction == TxDirection.Moved) null else external.firstOrNull()
    val weight = txWeight(input)
    val vsize = txVsize(input)
    val block = input.block
    val displaySats =
        if (input.direction == TxDirection.Out && amounts.feeSats != null) {
            amounts.shownSats + amounts.feeSats
        } else {
            amounts.shownSats
        }
    return TxDetail(
        header = TxHeader(txViewDirection(input), dated),
        hero =
            TxHero(
                amount = txAmountParts(displaySats, received),
                sub = heroSub,
                toLabel = to?.let { input.label.ifBlank { "Recipient" } },
                toAddress = to?.address,
                toMore = (external.size - 1).coerceAtLeast(0),
            ),
        status = txStatus(input),
        label = txLabelCard(input),
        flow = txFlowView(input),
        coins = txCoinCards(input, input.txid),
        reveals = txReveals(input),
        details = txDetailsModel(input, weight, vsize),
        direction = input.direction,
        outcome = input.outcome,
        amountSats = displaySats,
    )
}

private fun txViewDirection(input: TxDisplayInput): String =
    when (input.outcome) {
        TxOutcome.Replaced -> "Replaced"
        TxOutcome.Dropped -> "Dropped"
        TxOutcome.None ->
            when (input.direction) {
                TxDirection.In -> "Received"
                TxDirection.Moved -> "Self"
                TxDirection.Out -> if (input.block == null) "Sending" else "Sent"
            }
    }

private fun txLabelCard(input: TxDisplayInput): TxLabelCard {
    val received = input.direction == TxDirection.In
    val hasLabel = input.label.isNotBlank()
    val placeholder = if (received) "Who is it from?" else "What was it for?"
    val subtitle =
        when {
            hasLabel -> "On this transaction and its coin"
            received -> "Who sent it? Coin control uses labels to keep coins apart."
            else -> "What was it for?"
        }
    return TxLabelCard(
        title = if (hasLabel) input.label else "Add a label",
        subtitle = subtitle,
        placeholder = placeholder,
        hasLabel = hasLabel,
    )
}

private fun txDetailsModel(
    input: TxDisplayInput,
    weight: Int,
    vsize: Int,
): TxDetailsModel {
    val block = input.block
    val summary =
        if (block == null) {
            "Not in a block yet"
        } else {
            "${txGroupDigits(block.height.toLong())} · #${txGroupDigits(block.index.toLong())} in block"
        }
    return TxDetailsModel(
        transactionSummary = txShortId(input.txid, vsize),
        blockSummary = summary,
        txid = input.txid,
        rawSizeBytes = input.baseBytes + input.witnessBytes,
        weightWU = weight,
        vsizeVB = vsize,
        baseBytes = input.baseBytes,
        witnessBytes = input.witnessBytes,
        inputCount = input.inputCount,
        outputCount = input.outputs.size,
        position = block?.let { txPosition(it) },
        blockHash = block?.hash,
        blockHashLeadingZeros = block?.hash?.let { txLeadingZeros(it) },
        pendingBlock = block == null,
        heightLabel = block?.let { txGroupDigits(it.height.toLong()) },
        feeRateLabel = block?.feeRateLabel,
    )
}

internal fun txListRow(
    input: TxDisplayInput,
    detail: TxDetail,
): TxListRow {
    val failed = input.outcome != TxOutcome.None
    val pending = input.block == null && !failed
    val frozen = input.outputs.any { it.mine && it.frozen }
    val amber = detail.reveals.filter { it.tone == TxTone.Amber }
    val badge =
        when {
            failed -> TxBadge.None
            frozen -> TxBadge.Frozen
            amber.isNotEmpty() -> TxBadge.Warning
            else -> TxBadge.None
        }
    val whenTime = input.block?.mined ?: input.broadcastAt
    val date = txListDate(whenTime, input.now)
    return TxListRow(
        glyph = listGlyph(input, failed),
        pendingGlyph = pending,
        badge = badge,
        title = input.label.ifBlank { plainTitle(input) },
        titleMuted = failed,
        subtitle = listSubtitle(input, pending, frozen, amber, date),
        amount = detail.hero.amount,
        struck = failed,
        amountSub = if (input.direction == TxDirection.Moved) "Network fee" else null,
    )
}

private fun listGlyph(
    input: TxDisplayInput,
    failed: Boolean,
): TxGlyph =
    when {
        failed -> TxGlyph.Failed
        input.direction == TxDirection.In -> TxGlyph.Received
        input.direction == TxDirection.Moved -> TxGlyph.Moved
        else -> TxGlyph.Sent
    }

private fun plainTitle(input: TxDisplayInput): String =
    when (input.direction) {
        TxDirection.In -> "Received"
        TxDirection.Moved -> "Self"
        TxDirection.Out -> "Sent"
    }

private fun listSubtitle(
    input: TxDisplayInput,
    pending: Boolean,
    frozen: Boolean,
    amber: List<TxReveal>,
    date: String,
): TxListSubtitle {
    if (input.outcome == TxOutcome.Replaced) return TxListSubtitle.Status("Replaced")
    if (input.outcome == TxOutcome.Dropped) return TxListSubtitle.Status("Dropped")
    if (pending) return TxListSubtitle.Strip(pending = true, confirmations = 0, label = "Pending")
    val conf = confirmationCount(input)
    if (conf != null && conf in 1 until TX_LIST_STRIP) {
        return TxListSubtitle.Strip(pending = false, confirmations = conf, label = "$conf of $TX_LIST_STRIP")
    }
    if (frozen) return TxListSubtitle.Noted("Frozen", TxListLeadTone.Frozen, date)
    val flag = privacyFlag(amber)
    if (flag != null) return TxListSubtitle.Noted(flag, TxListLeadTone.Amber, date)
    if (input.label.isBlank()) return TxListSubtitle.Noted(date, TxListLeadTone.Secondary, "No label")
    return TxListSubtitle.DateOnly(date)
}

private fun confirmationCount(input: TxDisplayInput): Int? {
    val block = input.block ?: return null
    val tip = input.tipHeight ?: return null
    if (tip < block.height) return null
    return tip - block.height + 1
}

private fun privacyFlag(amber: List<TxReveal>): String? {
    if (amber.isEmpty()) return null
    if (amber.any { it.title == "Looks like dusting" }) return "Looks like dusting"
    val joined = amber.firstOrNull { it.title.startsWith("Joined ") }
    if (joined != null) return joined.title
    if (amber.any { it.title.startsWith("Sent to an address") }) return "Reused address"
    return amber.first().title
}
