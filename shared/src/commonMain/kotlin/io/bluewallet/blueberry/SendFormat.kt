package io.bluewallet.blueberry

data class SendRecipient(
    val address: String,
    val label: String,
)

/** "bc1q2d…welh": enough of each end to check against the source. */
fun shortAddress(
    address: String,
    head: Int = 6,
    tail: Int = 4,
): String {
    val a = address.trim()
    if (a.length <= head + tail) return a
    return "${a.take(head)}…${a.takeLast(tail)}"
}

/**
 * Chip label. An address keeps four characters from each end ("bc1q…fcr9").
 * A name, "To Rent", or "Your change" stays whole.
 */
fun flowChipLabel(label: String): String {
    val trimmed = label.trim()
    val to = "To "
    if (trimmed.startsWith(to)) {
        val rest = trimmed.removePrefix(to)
        val shortened = shortenAddress(rest)
        return if (shortened == rest) trimmed else to + shortened
    }
    return shortenAddress(trimmed)
}

private fun shortenAddress(value: String): String = if (isFlowAddress(value)) shortAddress(value, head = 4, tail = 4) else value

/** The address inside a chip label, including "To {address}", or null when the label is a name. */
internal fun flowAddressOrNull(label: String): String? {
    val trimmed = label.trim()
    val rest = if (trimmed.startsWith("To ")) trimmed.removePrefix("To ").trim() else trimmed
    return rest.takeIf { isFlowAddress(it) }
}

private val FLOW_ADDRESS_PREFIXES = listOf("bc1", "tb1", "bcrt1")

private fun isFlowAddress(value: String): Boolean {
    if (value.isEmpty() || value.any { it.isWhitespace() }) return false
    val head = value.substringBefore('…')
    val bech32 = FLOW_ADDRESS_PREFIXES.any { head.startsWith(it, ignoreCase = true) }
    val legacy = (value.startsWith("1") || value.startsWith("3")) && value.length >= 16
    return bech32 || legacy
}

/** The address in 4-character groups, for reading it back aloud. */
fun addressGroups(address: String): List<String> = address.trim().chunked(4)

private class ScriptShape(
    val first: Int,
    val size: Int,
    val label: String,
)

private val scriptShapes =
    listOf(
        ScriptShape(0x00, 22, "Native SegWit"),
        ScriptShape(0x00, 34, "SegWit script"),
        ScriptShape(0x51, 34, "Taproot"),
        ScriptShape(0x76, 25, "Legacy"),
        ScriptShape(0xa9, 23, "Script hash"),
    )

/** Human name for a recipient output script, or null when it isn't a standard type. */
fun addressTypeLabel(script: ByteArray): String? {
    val first = script.firstOrNull()?.toInt()?.and(0xff) ?: return null
    return scriptShapes.firstOrNull { it.first == first && it.size == script.size }?.label
}
