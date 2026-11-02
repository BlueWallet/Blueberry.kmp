package io.bluewallet.blueberry

data class SendRecipient(
    val address: String,
    val label: String,
)

/** "bc1q2d…welh": enough of each end to check against the source. */
fun shortAddress(address: String): String {
    val a = address.trim()
    return if (a.length <= 14) a else "${a.take(6)}…${a.takeLast(4)}"
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
