package io.bluewallet.blueberry

data class Bip21Payment(
    val address: String,
    val amountBtc: String?,
    val label: String?,
)

data class SendFormFields(
    val address: String,
    val amount: String,
    val label: String,
    val privateSend: Boolean = false,
)

fun parseBip21(input: String): Bip21Payment? {
    val trimmed = input.trim()
    val rest =
        if (trimmed.startsWith("bitcoin:", ignoreCase = true)) {
            trimmed.substringAfter(':').removePrefix("//")
        } else {
            return null
        }
    val address = rest.substringBefore('?')
    val params = queryParams(rest.substringAfter('?', missingDelimiterValue = ""))
    return if (address.isEmpty()) {
        null
    } else {
        Bip21Payment(
            address = address,
            amountBtc = params["amount"],
            label = params["label"] ?: params["message"],
        )
    }
}

fun applyBip21Payload(
    payload: String,
    current: SendFormFields,
): SendFormFields {
    val parsed = parseBip21(payload)
    return if (parsed == null) {
        current.copy(address = payload.trim())
    } else {
        SendFormFields(
            address = parsed.address,
            amount = parsed.amountBtc ?: current.amount,
            label = parsed.label ?: current.label,
            privateSend = current.privateSend,
        )
    }
}

private fun queryParams(query: String): Map<String, String> {
    if (query.isEmpty()) return emptyMap()
    val out = linkedMapOf<String, String>()
    for (part in query.split('&')) {
        if (part.isEmpty()) continue
        val key = percentDecode(part.substringBefore('=')).lowercase()
        val value = if ('=' in part) percentDecode(part.substringAfter('=')) else ""
        if (key.isNotEmpty() && key !in out) out[key] = value
    }
    return out
}

private fun percentDecode(raw: String): String {
    val bytes = ArrayList<Byte>()
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        when {
            c == '+' -> {
                bytes += ' '.code.toByte()
                i++
            }
            c == '%' && i + 2 < raw.length -> {
                val hi = raw[i + 1].digitToIntOrNull(16)
                val lo = raw[i + 2].digitToIntOrNull(16)
                if (hi != null && lo != null) {
                    bytes += ((hi shl 4) + lo).toByte()
                    i += 3
                } else {
                    bytes += c.code.toByte()
                    i++
                }
            }
            else -> {
                bytes += c.code.toByte()
                i++
            }
        }
    }
    return bytes.toByteArray().decodeToString()
}
