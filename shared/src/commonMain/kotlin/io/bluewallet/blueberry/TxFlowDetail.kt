package io.bluewallet.blueberry

/** Detail-screen nodes for the send-review graph. Review's own nodes leave the new fields unset. */
fun detailFlowNodes(flow: TxFlowView): Pair<List<TxFlowNode>, List<TxFlowNode>> {
    val outputTotal = flow.outputs.sumOf { it.amount ?: 0L }.coerceAtLeast(1L)
    val inputs = flow.inputs.map { chip -> flowNode(chip, outputTotal) }
    val outputs = flow.outputs.map { chip -> flowNode(chip, outputTotal) }
    return inputs to outputs
}

private fun flowNode(
    chip: TxFlowChipModel,
    outputTotal: Long,
): TxFlowNode {
    val amount = chip.amount
    return when (chip.kind) {
        "sender" ->
            TxFlowNode(
                sats = outputTotal,
                label = chip.sub ?: chip.title,
                role = TxFlowRole.Input,
                colorArgb = 0xFF8E959C,
                headline = chip.title,
                ring = true,
            )
        "you", "to" -> TxFlowNode(amount ?: 0L, chip.title, TxFlowRole.Payment, chip.colorArgb)
        "change" -> TxFlowNode(amount ?: 0L, chip.title, TxFlowRole.Change, chip.colorArgb)
        "fee" -> TxFlowNode(amount ?: 0L, chip.title, TxFlowRole.Fee)
        else ->
            TxFlowNode(
                sats = amount ?: 0L,
                label = chip.title,
                role = TxFlowRole.Input,
                colorArgb = chip.colorArgb ?: 0xFF8E959C,
                ring = chip.kind == "notYours",
            )
    }
}

/** One sentence a screen reader hears instead of the individual chips. */
fun detailFlowDescription(flow: TxFlowView): String {
    val inputs = flow.inputs.joinToString(", ") { chipWords(it) }
    val outputs = flow.outputs.joinToString(", ") { chipWords(it) }
    return "$inputs. $outputs."
}

private fun chipWords(chip: TxFlowChipModel): String {
    val amount = chip.amount?.let { txBtcFixed(it) }
    return if (amount == null) "${chip.title} ${chip.sub.orEmpty()}".trim() else "${chip.title} ₿$amount"
}
