package io.bluewallet.blueberry

fun txListSecondary(
    shortTxid: String,
    note: String?,
): String {
    val trimmed = note?.trim().orEmpty()
    return if (trimmed.isEmpty()) shortTxid else trimmed
}

fun txListSecondaryMuted(note: String?): Boolean = note?.trim().isNullOrEmpty()
