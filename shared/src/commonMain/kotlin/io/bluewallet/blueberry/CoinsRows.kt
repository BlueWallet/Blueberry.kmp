package io.bluewallet.blueberry

import io.bluewallet.blueberry.parse.shortTxid
import io.bluewallet.blueberry.parse.utxoValuePercent

data class CoinsRowModel(
    val key: String,
    val circleArgb: Long,
    val valueSats: Long,
    val name: String?,
    val barPercent: Int,
    val isChange: Boolean,
    val ageLabel: String,
    val address: String?,
    val path: String?,
)

fun coinsRowCaption(
    address: String?,
    ageLabel: String,
    name: String?,
): String? {
    val addr = address?.trim()?.takeIf { it.isNotEmpty() }?.let { shortTxid(it) }
    val age = ageLabel.trim().takeIf { it.isNotEmpty() }
    val memo = name?.trim()?.takeIf { it.isNotEmpty() }
    return listOfNotNull(addr, age, memo).joinToString(" · ").takeIf { it.isNotEmpty() }
}

fun coinsRows(utxos: List<WalletUtxoRow>): List<CoinsRowModel> {
    val maxValue = utxos.maxOfOrNull { it.valueSats } ?: 0L
    return utxos.map { u ->
        CoinsRowModel(
            key = u.key,
            circleArgb = utxoCircleArgb(u.key),
            valueSats = u.valueSats,
            name = u.name,
            barPercent = utxoValuePercent(u.valueSats, maxValue),
            isChange = u.isChange,
            ageLabel = u.ageLabel,
            address = u.address,
            path = u.path,
        )
    }
}
