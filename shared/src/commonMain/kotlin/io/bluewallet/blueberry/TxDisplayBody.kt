@file:Suppress("TooManyFunctions", "ReturnCount")

package io.bluewallet.blueberry

internal fun txCoinCards(
    input: TxDisplayInput,
    txid: String,
): List<TxCoinCard> = input.outputs.filter { it.mine }.map { txCoinCard(input, it, txid) }

private fun txCoinCard(
    input: TxDisplayInput,
    output: TxDisplayOutput,
    txid: String,
): TxCoinCard {
    val titledChange = output.change || input.direction == TxDirection.Moved
    val title = coinTitle(output, titledChange)
    val frozen = output.frozen && !output.spent
    val sub = coinSub(output, output.change, frozen)
    val chain = if (output.change) "Change address" else "Receive address"
    return TxCoinCard(
        outpoint = "$txid:${output.vout}",
        title = title,
        sub = sub,
        subTone = if (frozen) TxTone.Blue else TxTone.Neutral,
        amountSats = output.valueSat,
        address = output.address,
        addressNote = addressNote(chain, output),
        frozen = frozen,
        spent = output.spent,
        spentTxid = output.spentTxid,
        actions = if (output.spent) listOf("Open transaction") else listOf(if (output.frozen) "Frozen" else "Freeze", "Open coin"),
    )
}

private fun coinTitle(
    output: TxDisplayOutput,
    change: Boolean,
): String =
    when {
        change -> "Your change"
        output.label.isNotBlank() -> "${output.label} coin"
        else -> "New coin"
    }

private fun coinSub(
    output: TxDisplayOutput,
    change: Boolean,
    frozen: Boolean,
): String {
    if (output.spent) {
        val label = output.spentLabel?.takeIf { it.isNotBlank() } ?: "another payment"
        return "Spent in “$label”"
    }
    if (frozen) return "Frozen · never picked when you send"
    if (change && output.groupSize > 1 && output.groupName != null) return "Back in ${output.groupName}"
    if (!change && output.addressUseCount > 1) {
        val earlier = output.addressUseCount - 1
        val word = if (earlier == 1) "payment" else "payments"
        return "Same address as $earlier earlier $word"
    }
    if (output.groupSize > 1 && output.groupName != null) return "In ${output.groupName}"
    return "Not linked to your other coins"
}

private fun addressNote(
    chain: String,
    output: TxDisplayOutput,
): String {
    val index = output.addressIndex?.let { " #$it" }.orEmpty()
    val use =
        when {
            output.change && output.addressUseCount <= 1 -> "first use"
            output.change -> "used ${output.addressUseCount} times"
            output.addressUseCount <= 1 -> "first payment to it"
            else -> "received ${output.addressUseCount} payments"
        }
    return "$chain$index · $use"
}

internal fun txReveals(input: TxDisplayInput): List<TxReveal> = if (input.direction == TxDirection.In) receivedReveals(input) else sentReveals(input)

private fun receivedReveals(input: TxDisplayInput): List<TxReveal> {
    val rows = mutableListOf<TxReveal>()
    input.outputs.filter { it.mine }.forEach { output ->
        rows += addressReveal(output)
        rows += dustOrSenderReveal(input, output)
    }
    val block = input.block
    if (block != null) {
        val height = txGroupDigits(block.height.toLong())
        rows +=
            TxReveal(
                tone = TxTone.Green,
                title = "Found without asking a server",
                sub =
                    "Your phone matched block $height's filter and downloaded the whole block from a peer. " +
                        "No one was told which payment is yours.",
            )
    }
    return rows
}

private fun addressReveal(output: TxDisplayOutput): TxReveal {
    val index = output.addressIndex ?: 0
    return if (output.addressUseCount <= 1) {
        TxReveal(
            tone = TxTone.Green,
            title = "Paid to a fresh address",
            sub = "Address #$index has only received this payment, so nothing else on-chain points to it.",
            outpoint = null,
        )
    } else {
        TxReveal(
            tone = TxTone.Amber,
            title = "Sent to an address you've used before",
            sub =
                "Address #$index has received ${output.addressUseCount} payments. " +
                    "Anyone can see they belong to the same wallet.",
        )
    }
}

private fun dustOrSenderReveal(
    input: TxDisplayInput,
    output: TxDisplayOutput,
): TxReveal {
    if (!isDust(input, output)) return senderReveal(input, output)
    return if (output.frozen) {
        TxReveal(
            tone = TxTone.Green,
            title = "Frozen, so it can't link anything",
            sub = "Blueberry will never pick this coin when you send.",
            action = "Unfreeze",
            outpoint = "${input.txid}:${output.vout}",
        )
    } else {
        TxReveal(
            tone = TxTone.Amber,
            title = "Looks like dusting",
            sub =
                "Tiny amounts sent to many addresses at once. " +
                    "Spending this with your other coins would show the sender which coins are yours.",
            action = "Freeze this coin",
            outpoint = "${input.txid}:${output.vout}",
        )
    }
}

private fun senderReveal(
    input: TxDisplayInput,
    output: TxDisplayOutput,
): TxReveal =
    TxReveal(
        tone = TxTone.Neutral,
        title = "The sender can follow this coin",
        sub = "They know this output is yours and will see when it moves. Spend it with other coins and they learn about those too.",
        action = if (input.label.isBlank()) "Label who sent it" else null,
        outpoint = "${input.txid}:${output.vout}",
    )

private fun isDust(
    input: TxDisplayInput,
    output: TxDisplayOutput,
): Boolean {
    if (output.valueSat > TX_DUST_SATS) return false
    val twins = input.outputs.count { !it.mine && it.valueSat == output.valueSat }
    val costly = output.spendCostSat != null && output.spendCostSat >= output.valueSat
    return twins >= 2 || costly
}

private fun sentReveals(input: TxDisplayInput): List<TxReveal> {
    val rows = mutableListOf<TxReveal>()
    rows += historyReveal(input)
    rows += changeReveal(input)
    broadcastReveal(input)?.let { rows += it }
    return rows
}

private fun historyReveal(input: TxDisplayInput): TxReveal {
    val groups = input.walletInputs.map { it.groupName?.takeIf { name -> name.isNotBlank() } ?: it.outpoint }.toSet()
    if (groups.size <= 1) {
        val coin = input.walletInputs.firstOrNull()
        val sub =
            if (coin != null && coin.groupSize > 1 && coin.groupName != null) {
                "Spent only from ${coin.groupName}, which is already linked on-chain."
            } else {
                "Spent coins with no shared history."
            }
        return TxReveal(TxTone.Green, "No new links", sub)
    }
    return TxReveal(
        TxTone.Amber,
        "Joined ${groups.size} histories",
        "This transaction showed watchers that these coins share an owner.",
    )
}

private fun changeReveal(input: TxDisplayInput): TxReveal {
    val change = input.outputs.filter { it.mine }
    if (change.isEmpty()) {
        return TxReveal(TxTone.Green, "No change output", "Nothing from this payment links back to you.")
    }
    val coin = change.first()
    val where = if (coin.groupSize > 1 && coin.groupName != null) coin.groupName else "a new history"
    val label = input.label.ifBlank { "Change" }
    return TxReveal(
        TxTone.Neutral,
        "₿${txBtcFixed(coin.valueSat)} came back as change",
        "A new coin in $where, labeled “$label”. The recipient can likely tell it's yours.",
    )
}

private fun broadcastReveal(input: TxDisplayInput): TxReveal? =
    when (input.broadcast) {
        TxBroadcast.Tor ->
            TxReveal(
                TxTone.Green,
                "Broadcast over Tor",
                "Peers saw it arrive from the Tor network, not from your IP address.",
            )
        TxBroadcast.Direct ->
            TxReveal(
                TxTone.Neutral,
                "Broadcast directly",
                "Peers saw it come from your IP address. Turn on Tor broadcast in settings to hide it.",
            )
        TxBroadcast.Unknown -> null
    }
