package io.bluewallet.blueberry

import io.bluewallet.blueberry.parse.shortTxid

fun sendUtxoCaption(
    address: String?,
    ageLabel: String,
    name: String?,
): String {
    val addr = address?.trim()?.takeIf { it.isNotEmpty() }?.let { shortTxid(it) }
    val age = ageLabel.trim().takeIf { it.isNotEmpty() }
    val memo = name?.trim()?.takeIf { it.isNotEmpty() }
    return listOfNotNull(addr, age, memo).joinToString("  ")
}
