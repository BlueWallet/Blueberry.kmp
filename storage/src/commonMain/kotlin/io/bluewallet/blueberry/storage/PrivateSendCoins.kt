package io.bluewallet.blueberry.storage

fun encodePrivateSendCoins(coins: List<PrivateSendCoin>): String =
    coins.joinToString(separator = ",", prefix = "[", postfix = "]") { coin ->
        """{"txid":"${coin.txid}","vout":${coin.vout},"valueSats":${coin.valueSats}}"""
    }

fun decodePrivateSendCoins(raw: String): List<PrivateSendCoin> =
    OBJECT
        .findAll(raw)
        .mapNotNull { match -> coinFromObject(match.value) }
        .toList()

private fun coinFromObject(obj: String): PrivateSendCoin? {
    val txid = TXID.find(obj)?.groupValues?.get(1)
    val vout =
        VOUT
            .find(obj)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    val value =
        VALUE
            .find(obj)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
    return if (txid == null || vout == null || value == null) {
        null
    } else {
        PrivateSendCoin(txid, vout, value)
    }
}

private val OBJECT = Regex("""\{[^}]+\}""")
private val TXID = Regex(""""txid"\s*:\s*"([^"]+)"""")
private val VOUT = Regex(""""vout"\s*:\s*(-?\d+)""")
private val VALUE = Regex(""""valueSats"\s*:\s*(-?\d+)""")
