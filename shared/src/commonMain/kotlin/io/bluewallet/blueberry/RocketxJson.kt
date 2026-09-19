@file:Suppress("TooManyFunctions")

package io.bluewallet.blueberry

internal sealed class JsonVal {
    data class Obj(
        val fields: Map<String, JsonVal>,
    ) : JsonVal()

    data class Arr(
        val items: List<JsonVal>,
    ) : JsonVal()

    data class Str(
        val value: String,
    ) : JsonVal()

    data class Num(
        val value: String,
    ) : JsonVal()

    data object Other : JsonVal()
}

internal class JsonCursor(
    val text: String,
) {
    var i = 0

    fun skipWs() {
        while (i < text.length && text[i].isWhitespace()) i++
    }

    fun at(ch: Char): Boolean = i < text.length && text[i] == ch

    fun eat(ch: Char): Boolean {
        if (!at(ch)) return false
        i++
        return true
    }
}

internal fun parseJson(text: String): JsonVal = readVal(JsonCursor(text))

internal fun collectQuotes(
    node: JsonVal,
    out: MutableList<RocketxQuote>,
) {
    when (node) {
        is JsonVal.Obj -> {
            quoteFromObj(node)?.let { out += it }
            for (v in node.fields.values) collectQuotes(v, out)
        }
        is JsonVal.Arr -> for (v in node.items) collectQuotes(v, out)
        else -> {}
    }
}

internal fun firstStringField(
    node: JsonVal,
    key: String,
): String? =
    when (node) {
        is JsonVal.Obj -> {
            stringAt(node, key) ?: node.fields.values.firstNotNullOfOrNull { firstStringField(it, key) }
        }
        is JsonVal.Arr -> node.items.firstNotNullOfOrNull { firstStringField(it, key) }
        else -> null
    }

private fun readVal(c: JsonCursor): JsonVal {
    c.skipWs()
    if (c.i >= c.text.length) return JsonVal.Other
    return when (c.text[c.i]) {
        '{' -> readObj(c)
        '[' -> readArr(c)
        '"' -> JsonVal.Str(readStr(c))
        't', 'f', 'n' -> {
            skipAtom(c)
            JsonVal.Other
        }
        else -> JsonVal.Num(readNum(c))
    }
}

private fun readObj(c: JsonCursor): JsonVal.Obj {
    c.i++
    val fields = linkedMapOf<String, JsonVal>()
    while (c.i < c.text.length && !c.at('}')) {
        c.skipWs()
        if (c.eat(',') || c.at('}')) continue
        val key = readStr(c)
        c.skipWs()
        c.eat(':')
        fields[key] = readVal(c)
    }
    c.eat('}')
    return JsonVal.Obj(fields)
}

private fun readArr(c: JsonCursor): JsonVal.Arr {
    c.i++
    val items = ArrayList<JsonVal>()
    while (c.i < c.text.length && !c.at(']')) {
        c.skipWs()
        if (c.eat(',') || c.at(']')) continue
        items += readVal(c)
    }
    c.eat(']')
    return JsonVal.Arr(items)
}

private fun readStr(c: JsonCursor): String {
    if (!c.eat('"')) return ""
    val out = StringBuilder()
    var closed = false
    while (c.i < c.text.length && !closed) {
        val ch = c.text[c.i++]
        when {
            ch == '"' -> closed = true
            ch != '\\' -> out.append(ch)
            c.i >= c.text.length -> closed = true
            else -> out.append(unescape(c.text[c.i++], c))
        }
    }
    return out.toString()
}

private fun unescape(
    e: Char,
    c: JsonCursor,
): Char =
    when (e) {
        'n' -> '\n'
        't' -> '\t'
        'r' -> '\r'
        'u' -> readHexChar(c)
        else -> e
    }

private fun readHexChar(c: JsonCursor): Char {
    if (c.i + 4 > c.text.length) return '?'
    val hex = c.text.substring(c.i, c.i + 4)
    c.i += 4
    return hex.toIntOrNull(16)?.toChar() ?: '?'
}

private fun isNumChar(ch: Char): Boolean = ch.isDigit() || ch in "+-.eE"

private fun readNum(c: JsonCursor): String {
    val start = c.i
    while (c.i < c.text.length && isNumChar(c.text[c.i])) c.i++
    return c.text.substring(start, c.i)
}

private fun skipAtom(c: JsonCursor) {
    while (c.i < c.text.length && c.text[c.i].isLetter()) c.i++
}

private fun quoteFromObj(obj: JsonVal.Obj): RocketxQuote? {
    val rateId = stringAt(obj, "rateId")
    val fromAmount = stringAt(obj, "fromAmount")
    val toAmount = stringAt(obj, "toAmount")
    if (rateId == null || fromAmount == null || toAmount == null) return null
    return RocketxQuote(
        rateId = rateId,
        fromAmount = fromAmount,
        toAmount = toAmount,
        venue = nestedString(obj, "exchangeInfo", "keyword") ?: "",
        fromTokenId = intAt(nestedObj(obj, "fromTokenInfo"), "id") ?: 0,
        toTokenId = intAt(nestedObj(obj, "toTokenInfo"), "id") ?: 0,
        exchangeId = nestedString(obj, "exchangeInfo", "id") ?: "",
    )
}

internal fun parseRocketxSwap(body: String): RocketxSwap? {
    val root = parseJson(body) as? JsonVal.Obj ?: return null
    val swapObj = nestedObj(root, "swap") ?: root
    val address = stringAt(swapObj, "depositAddress") ?: firstStringField(root, "depositAddress")
    return address?.let {
        RocketxSwap(
            address = it,
            fromAmount = stringAt(swapObj, "fromAmount").orEmpty(),
            toAmount = stringAt(swapObj, "toAmount").orEmpty(),
            venue = nestedString(root, "exchangeInfo", "keyword").orEmpty(),
            partnerFee = stringAt(swapObj, "partnerFee").orEmpty(),
            requestId = stringAt(root, "requestId").orEmpty(),
            estSeconds = nestedString(root, "estTimeInSeconds", "avg").orEmpty(),
        )
    }
}

internal fun stringAt(
    obj: JsonVal.Obj,
    key: String,
): String? =
    when (val v = obj.fields[key]) {
        is JsonVal.Str -> v.value
        is JsonVal.Num -> v.value
        else -> null
    }

private fun intAt(
    obj: JsonVal.Obj?,
    key: String,
): Int? {
    if (obj == null) return null
    return stringAt(obj, key)?.substringBefore('.')?.toIntOrNull()
}

internal fun nestedObj(
    obj: JsonVal.Obj,
    key: String,
): JsonVal.Obj? = obj.fields[key] as? JsonVal.Obj

internal fun nestedString(
    obj: JsonVal.Obj,
    nest: String,
    key: String,
): String? = nestedObj(obj, nest)?.let { stringAt(it, key) }
