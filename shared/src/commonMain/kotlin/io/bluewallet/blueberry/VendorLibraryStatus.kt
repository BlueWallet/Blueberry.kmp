package io.bluewallet.blueberry

import io.bluewallet.bip157.NODE_COMPACT_FILTERS
import io.bluewallet.bip158.hexToBytes
import io.bluewallet.bip324.Networks
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.echalote.Echalote
import io.bluewallet.headers.MAINNET_HEADER_CONSENSUS
import qr.ImageTooSmallException
import qr.QRDecoder
import kotlin.random.Random

fun vendorLibraryStatus(): List<String> {
    val errors =
        listOfNotNull(
            vendorStatusLine("headers") {
                MAINNET_HEADER_CONSENSUS.checkpoint.height
            },
            vendorStatusLine("bip324") {
                Networks.mainnet.defaultPort
            },
            vendorStatusLine("bip157") {
                NODE_COMPACT_FILTERS
            },
            vendorStatusLine("bip158") {
                hexToBytes("00")
            },
            vendorStatusLine("echalote") {
                Echalote.DEFAULT_MEEK_URL
            },
            vendorStatusLine("storage") {
                val db = createSqliteDatabase(":memory:")
                try {
                    val value = Random.nextInt().toString()
                    db.keyValue.set("click", value)
                    val got = db.keyValue.get("click")
                    if (got != value) throw Exception("mismatch")
                } finally {
                    db.close()
                }
            },
            vendorStatusLine("qr") {
                try {
                    QRDecoder.decode(10, 10, ByteArray(400) { 255.toByte() })
                    throw Exception("expected too-small")
                } catch (_: ImageTooSmallException) {
                }
            },
        )
    return if (errors.isEmpty()) listOf("ok") else errors
}

internal fun vendorStatusLine(
    name: String,
    block: () -> Unit,
): String? =
    try {
        block()
        null
    } catch (error: Exception) {
        "$name: ${error.message ?: error.toString()}"
    }
