package io.bluewallet.blueberry

fun txListSecondary(
    shortTxid: String,
    note: String?,
    utxoLabel: String? = null,
    addressLabel: String? = null,
): String = listOf(note, utxoLabel, addressLabel).firstNotNullOfOrNull { it?.trim()?.ifEmpty { null } } ?: shortTxid

fun txListSecondaryMuted(
    note: String?,
    utxoLabel: String? = null,
    addressLabel: String? = null,
): Boolean = listOf(note, utxoLabel, addressLabel).all { it?.trim().isNullOrEmpty() }

/** Absolute block times on the list are the date only. Relative labels stay. */
fun txListDateLabel(label: String): String {
    val trimmed = label.trim()
    val isAbsolute =
        trimmed.length >= 16 &&
            trimmed[4] == '-' &&
            trimmed[7] == '-' &&
            trimmed[10] == ' '
    return if (isAbsolute) trimmed.substring(0, 10) else trimmed
}
