package io.bluewallet.blueberry.parse

data class WatchUtxo(
    val value: Long,
    val scriptPubKey: ByteArray,
    val height: Int? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WatchUtxo) return false
        return value == other.value &&
            scriptPubKey.contentEquals(other.scriptPubKey) &&
            height == other.height
    }

    override fun hashCode(): Int {
        var result = value.hashCode()
        result = 31 * result + scriptPubKey.contentHashCode()
        result = 31 * result + (height ?: 0)
        return result
    }
}

data class ExtractedWatchTx(
    val txid: String,
    val txIndex: Int,
    val tx: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExtractedWatchTx) return false
        return txid == other.txid && txIndex == other.txIndex && tx.contentEquals(other.tx)
    }

    override fun hashCode(): Int {
        var result = txid.hashCode()
        result = 31 * result + txIndex
        result = 31 * result + tx.contentHashCode()
        return result
    }
}

data class BalanceSummary(
    val sats: Long,
    val utxoCount: Int,
)

data class TxRow(
    val txid: String,
    val height: Int,
    val txIndex: Int,
    val tx: ByteArray,
)

data class UsedWatchIndexes(
    val external: List<Int>,
    val internal: List<Int>,
)
