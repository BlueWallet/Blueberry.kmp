package io.bluewallet.blueberry

fun txListSecondary(
    shortTxid: String,
    note: String?,
    utxoLabel: String? = null,
): String {
    val trimmed = note?.trim().orEmpty().ifEmpty { utxoLabel?.trim().orEmpty() }
    return if (trimmed.isEmpty()) shortTxid else trimmed
}

fun txListSecondaryMuted(
    note: String?,
    utxoLabel: String? = null,
): Boolean = note?.trim().isNullOrEmpty() && utxoLabel?.trim().isNullOrEmpty()
