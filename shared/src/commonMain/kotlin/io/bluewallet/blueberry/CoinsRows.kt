package io.bluewallet.blueberry

import io.bluewallet.blueberry.parse.utxoValuePercent

data class CoinsRowModel(
    val key: String,
    val circleArgb: Long,
    val valueSats: Long,
    val name: String?,
    val barPercent: Int,
    val isChange: Boolean,
)

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
        )
    }
}
