package io.bluewallet.blueberry.storage

import com.ionspin.kotlin.bignum.integer.BigInteger

data class Peer(
    val host: String,
    val port: Int,
    val services: ULong,
    val alive: Boolean,
    val usedForBlocks: Boolean,
    val lastProbedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class PeerWrite(
    val host: String,
    val port: Int,
    val services: ULong,
    val alive: Boolean,
    val usedForBlocks: Boolean,
    val lastProbedAt: Long?,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
)

data class AliveServiceOptions(
    val unusedForBlocks: Boolean = false,
)

interface PeersRepository {
    fun upsert(peer: PeerWrite)

    fun list(): List<Peer>

    fun count(): Int

    fun listAlive(): List<Peer>

    fun listAliveWithServices(
        serviceBits: ULong,
        limit: Int,
        options: AliveServiceOptions? = null,
    ): List<Peer>

    fun listWithServices(
        serviceBits: ULong,
        limit: Int,
    ): List<Peer>

    fun listUnprobedWithServicesUnused(
        serviceBits: ULong,
        limit: Int,
    ): List<Peer>

    fun listProbeQueue(limit: Int): List<Peer>

    fun listOldestDeadWithServices(
        serviceBits: ULong,
        limit: Int,
    ): List<Peer>

    fun markProbed(
        host: String,
        port: Int,
        at: Long,
    )

    fun markAlive(
        host: String,
        port: Int,
        alive: Boolean,
    )

    fun markUsedForBlocks(
        host: String,
        port: Int,
    )
}

data class HeaderRecord(
    val height: Int,
    val hashInternalHex: String,
    val header: ByteArray,
)

data class StoredHeader(
    val height: Int,
    val hashInternalHex: String,
    val header: ByteArray,
    val cumulativeWork: BigInteger,
)

data class HeaderWrite(
    val height: Int,
    val hashInternalHex: String,
    val header: ByteArray,
    val cumulativeWork: BigInteger? = null,
)

interface HeadersRepository {
    fun ensureCheckpoint(checkpoint: HeaderRecord)

    fun tip(): StoredHeader?

    fun count(): Int

    fun minHeight(): Int?

    fun get(height: Int): StoredHeader?

    fun heightForHashInternal(hashInternalHex: String): Int?

    fun loadRange(
        fromHeight: Int,
        toHeight: Int,
    ): List<StoredHeader>

    fun loadAll(): List<StoredHeader>

    fun append(headers: List<HeaderWrite>)

    fun replaceAfter(
        commonAncestorHeight: Int,
        headers: List<HeaderWrite>,
    )
}

data class FilterHeaderRecord(
    val height: Int,
    val header: ByteArray,
)

data class FilterRecord(
    val height: Int,
    val blockHashInternalHex: String,
    val filter: ByteArray,
)

data class HeightRange(
    val from: Int,
    val to: Int,
)

interface FilterHeadersRepository {
    fun tip(): FilterHeaderRecord?

    fun get(height: Int): FilterHeaderRecord?

    fun minHeight(): Int?

    fun loadRange(
        fromHeight: Int,
        toHeight: Int,
    ): List<FilterHeaderRecord>

    fun append(rows: List<FilterHeaderRecord>)

    fun deleteFrom(height: Int)
}

interface FiltersRepository {
    fun count(): Int

    fun countInRange(
        from: Int,
        to: Int,
    ): Int

    fun minHeight(): Int?

    fun maxHeight(): Int?

    fun has(height: Int): Boolean

    fun get(height: Int): FilterRecord?

    fun hashAt(height: Int): String?

    fun firstHashMismatch(
        from: Int,
        to: Int,
    ): Int?

    fun missingRanges(
        from: Int,
        to: Int,
        maxSpan: Int,
    ): List<HeightRange>

    fun completeInRange(
        from: Int,
        to: Int,
    ): Boolean

    fun append(rows: List<FilterRecord>)

    fun listNeedingMatch(limit: Int): List<FilterRecord>

    fun countScanned(): Int

    fun hasUnscanned(): Boolean

    fun markScanned(heights: List<Int>)

    fun markUnscanned(heights: List<Int>)

    fun markUnscannedFrom(fromHeight: Int)

    fun deleteFrom(height: Int)
}

interface KeyValueRepository {
    fun get(key: String): String?

    fun set(
        key: String,
        value: String,
    )
}

data class UtxoNameRow(
    val outpoint: String,
    val name: String,
)

interface UtxoNamesRepository {
    fun get(outpoint: String): String?

    fun upsert(
        outpoint: String,
        name: String,
    )

    fun delete(outpoint: String)

    fun list(): List<UtxoNameRow>
}

data class TxPaymentLabelRow(
    val txid: String,
    val label: String,
)

interface TxPaymentLabelsRepository {
    fun get(txid: String): TxPaymentLabelRow?

    fun upsert(row: TxPaymentLabelRow)

    fun list(): List<TxPaymentLabelRow>
}

data class MatchedBlock(
    val height: Int,
    val blockHashInternalHex: String,
)

data class DownloadedBlock(
    val height: Int,
    val blockHashInternalHex: String,
    val block: ByteArray,
)

interface MatchedBlocksRepository {
    fun insert(block: MatchedBlock): Boolean

    fun get(height: Int): MatchedBlock?

    fun count(): Int

    fun listNeedingDownload(limit: Int): List<MatchedBlock>
}

interface BlocksRepository {
    fun count(): Int

    fun has(height: Int): Boolean

    fun get(height: Int): DownloadedBlock?

    fun insert(block: DownloadedBlock): Boolean

    fun insertIfMatched(block: DownloadedBlock): Boolean

    fun listNeedingParseHeights(limit: Int): List<Int>
}

data class StoredTx(
    val txid: String,
    val height: Int,
    val txIndex: Int,
    val blockHashInternalHex: String,
    val tx: ByteArray,
    val netDeltaSats: Long,
)

interface ParsedBlocksRepository {
    fun has(height: Int): Boolean

    fun mark(height: Int)

    fun count(): Int

    fun clearFrom(fromHeight: Int)
}

data class TxSetFingerprint(
    val count: Int,
    val netDeltaSum: Long,
    val newestTxid: String?,
)

interface TransactionsRepository {
    fun upsert(tx: StoredTx)

    fun list(): List<StoredTx>

    fun count(): Int

    fun fingerprint(): TxSetFingerprint

    fun minHeight(): Int?

    fun get(txid: String): StoredTx?

    fun setNetDelta(
        txid: String,
        netDeltaSats: Long,
    )
}

data class WipeFiltersFromOptions(
    val prevHeaderHeight: Int? = null,
)

interface Database {
    val peers: PeersRepository
    val headers: HeadersRepository
    val filterHeaders: FilterHeadersRepository
    val filters: FiltersRepository
    val matchedBlocks: MatchedBlocksRepository
    val blocks: BlocksRepository
    val parsedBlocks: ParsedBlocksRepository
    val transactions: TransactionsRepository
    val keyValue: KeyValueRepository
    val utxoNames: UtxoNamesRepository
    val txPaymentLabels: TxPaymentLabelsRepository

    fun transaction(fn: () -> Unit)

    fun rewindAfter(ancestorHeight: Int)

    fun wipeFiltersFrom(
        height: Int,
        options: WipeFiltersFromOptions? = null,
    )

    fun close()
}
