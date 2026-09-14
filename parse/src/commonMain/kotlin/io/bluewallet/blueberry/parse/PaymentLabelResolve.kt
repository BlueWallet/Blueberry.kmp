package io.bluewallet.blueberry.parse

fun firstUtxoLabelByTxid(nameByOutpoint: Map<String, String>): Map<String, String> {
    val labeled =
        nameByOutpoint.mapNotNull { (outpoint, name) ->
            val trimmed = name.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val i = outpoint.lastIndexOf(':')
            if (i <= 0) return@mapNotNull null
            val vout = outpoint.substring(i + 1).toIntOrNull() ?: return@mapNotNull null
            Triple(outpoint.substring(0, i), vout, trimmed)
        }
    return labeled
        .groupBy { it.first }
        .mapValues { (_, rows) -> rows.minBy { it.second }.third }
}
