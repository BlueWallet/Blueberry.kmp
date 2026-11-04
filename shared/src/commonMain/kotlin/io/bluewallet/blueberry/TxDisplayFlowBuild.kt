package io.bluewallet.blueberry

private const val TX_FLOW_NODE_CAP = 4

internal fun txFlowView(input: TxDisplayInput): TxFlowView {
    val received = input.direction == TxDirection.In
    val built = if (received) receivedFlow(input) else sentFlow(input)
    return TxFlowView(
        count = "${input.inputCount} in · ${input.outputs.size} out",
        fromColumn = if (received) "From" else "You spend",
        toColumn = if (received) "To" else "Goes to",
        inputs = built.inputs,
        outputs = built.outputs,
        note = if (input.direction == TxDirection.Moved) "Moved between your coins" else built.note,
    )
}

private class BuiltFlow(
    val inputs: List<TxFlowChipModel>,
    val outputs: List<TxFlowChipModel>,
    val note: String?,
)

private fun receivedFlow(input: TxDisplayInput): BuiltFlow {
    val mine = input.outputs.filter { it.mine }
    val external = input.outputs.filter { !it.mine }
    val mineValues = mine.map { it.valueSat }.toSet()
    val twins = external.filter { it.valueSat in mineValues }
    val rest = external.filter { it.valueSat !in mineValues }
    val nodes = mutableListOf<TxFlowChipModel>()
    mine.forEach { output ->
        nodes +=
            TxFlowChipModel(
                kind = "you",
                amount = output.valueSat,
                title = "You",
                coinKey = coinKey(input, output),
            )
    }
    val leftovers =
        if (twins.size >= 2) {
            nodes +=
                TxFlowChipModel(
                    kind = "others",
                    amount = twins.sumOf { it.valueSat },
                    title = "${twins.size} others",
                )
            rest
        } else {
            twins + rest
        }
    appendExternal(nodes, leftovers)
    val note =
        if (twins.size >= 2 && mine.isNotEmpty()) {
            val count = twins.size + mine.size
            val value = mine.first().valueSat
            "$count outputs of exactly $value sats went to $count different addresses in one transaction."
        } else {
            "The sender paid the fee. Transactions don't record what the sender's coin held, so Blueberry can't show it."
        }
    val coins = if (input.inputCount == 1) "coin" else "coins"
    val sender =
        TxFlowChipModel(
            kind = "sender",
            title = "Sender",
            sub = "${input.inputCount} $coins",
        )
    return BuiltFlow(listOf(sender), nodes, note)
}

private fun appendExternal(
    nodes: MutableList<TxFlowChipModel>,
    rest: List<TxDisplayOutput>,
) {
    if (rest.isEmpty()) return
    if (nodes.size + rest.size <= TX_FLOW_NODE_CAP) {
        rest.forEach { output ->
            nodes += TxFlowChipModel(kind = "notYours", amount = output.valueSat, title = "Not yours")
        }
    } else {
        nodes +=
            TxFlowChipModel(
                kind = "notYours",
                amount = rest.sumOf { it.valueSat },
                title = "${rest.size} not yours",
            )
    }
}

private fun sentFlow(input: TxDisplayInput): BuiltFlow {
    val inputs =
        input.walletInputs.map { coin ->
            TxFlowChipModel(
                kind = "coin",
                amount = coin.valueSat,
                title = coin.label?.takeIf { it.isNotBlank() } ?: "No label",
                colorArgb = coin.colorArgb,
            )
        }
    val outputs = mutableListOf<TxFlowChipModel>()
    input.outputs.filter { !it.mine }.forEach { output ->
        outputs +=
            TxFlowChipModel(
                kind = "to",
                amount = output.valueSat,
                title = input.label.ifBlank { "Recipient" },
            )
    }
    input.outputs.filter { it.mine }.forEach { output ->
        outputs +=
            TxFlowChipModel(
                kind = "change",
                amount = output.valueSat,
                title = "Your change",
                coinKey = coinKey(input, output),
            )
    }
    val fee = txAmounts(input).feeSats ?: 0L
    outputs += TxFlowChipModel(kind = "fee", amount = fee, title = "Network fee")
    return BuiltFlow(inputs, outputs, null)
}

/** Same key as the coin row, and only once that row exists. */
private fun coinKey(
    input: TxDisplayInput,
    output: TxDisplayOutput,
): String? = if (output.inWallet || output.spent) "${input.txid}:${output.vout}" else null
