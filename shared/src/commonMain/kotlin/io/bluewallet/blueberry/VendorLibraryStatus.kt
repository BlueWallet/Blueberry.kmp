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

fun vendorLibraryStatus(): List<String> = listOf(
    vendorStatusLine("headers") {
        "headers: checkpoint ${MAINNET_HEADER_CONSENSUS.checkpoint.height}"
    },
    vendorStatusLine("bip324") {
        "bip324: mainnet port ${Networks.mainnet.defaultPort}"
    },
    vendorStatusLine("bip157") {
        "bip157: NODE_COMPACT_FILTERS $NODE_COMPACT_FILTERS"
    },
    vendorStatusLine("bip158") {
        "bip158: hex 00 size ${hexToBytes("00").size}"
    },
    vendorStatusLine("echalote") {
        "echalote: meek ${Echalote.DEFAULT_MEEK_URL}"
    },
    vendorStatusLine("storage") {
        val db = createSqliteDatabase(":memory:")
        try {
            val value = Random.nextInt().toString()
            db.keyValue.set("click", value)
            val got = db.keyValue.get("click")
            if (got != value) throw Exception("mismatch")
            "storage: kv ok"
        } finally {
            db.close()
        }
    },
    vendorStatusLine("qr") {
        try {
            QRDecoder.decode(10, 10, ByteArray(400) { 255.toByte() })
            throw Exception("expected too-small")
        } catch (_: ImageTooSmallException) {
            "qr: ImageTooSmallException"
        }
    },
)

internal fun vendorStatusLine(name: String, block: () -> String): String =
    try {
        block()
    } catch (error: Exception) {
        "$name: error ${error.message}"
    }
