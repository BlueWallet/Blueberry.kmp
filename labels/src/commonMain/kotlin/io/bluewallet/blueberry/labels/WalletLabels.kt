package io.bluewallet.blueberry.labels

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class UtxoLabel(
    val outpoint: String,
    val name: String,
)

data class PaymentLabel(
    val txid: String,
    val label: String,
)

data class WalletLabels(
    val utxoNames: List<UtxoLabel>,
    val paymentLabels: List<PaymentLabel>,
)

/** BIP-329 JSONL of [labels], sealed with the SLIP-0015 file cipher. */
fun encryptWalletLabels(
    accountKey: String,
    labels: WalletLabels,
): ByteArray {
    val lines =
        buildList {
            labels.utxoNames.sortedBy { it.outpoint }.forEach { row ->
                add(bip329Line("output", row.outpoint, row.name))
            }
            labels.paymentLabels.sortedBy { it.txid }.forEach { row ->
                add(bip329Line("tx", row.txid, row.label))
            }
        }
    return encryptMetadataFile(accountKey, lines.joinToString("\n").encodeToByteArray())
}

/**
 * Opens a SLIP-0015 payload and returns BIP-329 `output` and `tx` rows.
 * A line that is not valid JSON, or not one of those records, is skipped.
 */
fun decryptWalletLabels(
    accountKey: String,
    payload: ByteArray,
): WalletLabels = labelsFromPlaintext(decryptMetadataFile(accountKey, payload).decodeToString())

/**
 * Labels from a stored file. A blank document is an empty set.
 * A non-blank document with no output or tx rows is not a label file.
 */
fun storedWalletLabels(
    accountKey: String,
    payload: ByteArray,
): WalletLabels? {
    val text = decryptMetadataFile(accountKey, payload).decodeToString()
    val labels = labelsFromPlaintext(text)
    val hasRow = labels.utxoNames.isNotEmpty() || labels.paymentLabels.isNotEmpty()
    return if (text.isBlank() || hasRow) labels else null
}

private fun labelsFromPlaintext(text: String): WalletLabels {
    val utxoNames = linkedMapOf<String, String>()
    val paymentLabels = linkedMapOf<String, String>()
    text.split('\n').forEach { line ->
        val record = bip329Record(line.trim()) ?: return@forEach
        when (record.type) {
            "output" -> utxoNames[record.ref] = record.label
            "tx" -> paymentLabels[record.ref] = record.label
        }
    }
    return WalletLabels(
        utxoNames = utxoNames.map { (outpoint, name) -> UtxoLabel(outpoint, name) }.sortedBy { it.outpoint },
        paymentLabels = paymentLabels.map { (txid, label) -> PaymentLabel(txid, label) }.sortedBy { it.txid },
    )
}

private data class Bip329Record(
    val type: String,
    val ref: String,
    val label: String,
)

private fun bip329Line(
    type: String,
    ref: String,
    label: String,
): String =
    buildJsonObject {
        put("type", type)
        put("ref", ref)
        put("label", label)
    }.toString()

private fun bip329Record(line: String): Bip329Record? {
    val obj = if (line.isEmpty()) null else parseJsonObject(line)
    val type = obj?.let { jsonString(it, "type") }
    val ref = obj?.let { jsonString(it, "ref") }
    val label = obj?.let { jsonString(it, "label") }
    val knownType = type == "output" || type == "tx"
    return if (knownType && ref != null && label != null) {
        Bip329Record(type, ref, label)
    } else {
        null
    }
}

private fun parseJsonObject(line: String): JsonObject? =
    try {
        Json.parseToJsonElement(line).jsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private fun jsonString(
    obj: JsonObject,
    name: String,
): String? {
    val value = obj[name] as? JsonPrimitive
    return if (value != null && value.isString) value.content else null
}
