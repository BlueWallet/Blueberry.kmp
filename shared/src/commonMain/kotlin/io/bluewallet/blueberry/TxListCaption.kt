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
