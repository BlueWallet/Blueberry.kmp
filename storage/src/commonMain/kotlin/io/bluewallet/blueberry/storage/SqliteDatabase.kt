package io.bluewallet.blueberry.storage

import app.cash.sqldelight.db.SqlDriver
import com.ionspin.kotlin.bignum.integer.BigInteger
import io.bluewallet.headers.equalBytes

/** Stay under Android CursorWindow's ~2 MiB row cap when reading stored blocks. */
private const val BLOCK_BLOB_CHUNK = 512 * 1024

fun createSqliteDatabase(path: String): Database {
    val driver = openSqliteDriver(path)
    try {
        applySchema(driver)
        applyPragmas(driver)
    } catch (e: Throwable) {
        try {
            driver.close()
        } catch (_: Throwable) {
        }
        throw e
    }
    return SqliteDatabase(driver, StorageDb(driver))
}

internal class SqliteDatabase(
    private val driver: SqlDriver,
    private val storageDb: StorageDb,
) : Database {
    override val keyValue =
        object : KeyValueRepository {
            override fun get(key: String): String? = storageDb.keyValueQueries.get(key).executeAsOneOrNull()

            override fun set(
                key: String,
                value: String,
            ) {
                storageDb.keyValueQueries.set(key, value)
            }
        }

    override val utxoNames =
        object : UtxoNamesRepository {
            override fun get(outpoint: String): String? = storageDb.utxoNamesQueries.get(outpoint).executeAsOneOrNull()

            override fun upsert(
                outpoint: String,
                name: String,
            ) {
                storageDb.utxoNamesQueries.upsert(outpoint, name)
            }

            override fun delete(outpoint: String) {
                storageDb.utxoNamesQueries.delete(outpoint)
            }

            override fun list(): List<UtxoNameRow> =
                storageDb.utxoNamesQueries
                    .list()
                    .executeAsList()
                    .map { UtxoNameRow(it.outpoint, it.name) }
        }

    override val txPaymentLabels =
        object : TxPaymentLabelsRepository {
            override fun get(txid: String): TxPaymentLabelRow? =
                storageDb.txPaymentLabelsQueries
                    .get(txid)
                    .executeAsOneOrNull()
                    ?.let { TxPaymentLabelRow(it.txid, it.label) }

            override fun upsert(row: TxPaymentLabelRow) {
                storageDb.txPaymentLabelsQueries.upsert(row.txid, row.label)
            }

            override fun delete(txid: String) {
                storageDb.txPaymentLabelsQueries.delete(txid)
            }

            override fun list(): List<TxPaymentLabelRow> =
                storageDb.txPaymentLabelsQueries
                    .list()
                    .executeAsList()
                    .map { TxPaymentLabelRow(it.txid, it.label) }
        }

    override val privateSends =
        object : PrivateSendsRepository {
            override fun get(txid: String): PrivateSendRow? =
                storageDb.privateSendsQueries
                    .get(txid)
                    .executeAsOneOrNull()
                    ?.toRow()

            override fun upsert(row: PrivateSendRow) {
                storageDb.privateSendsQueries.upsert(
                    txid = row.txid,
                    partner = row.partner,
                    order_id = row.orderId,
                    tx_hex = row.txHex,
                    destination = row.destination,
                    refund_address = row.refundAddress,
                    utxos = encodePrivateSendCoins(row.coins),
                    confirmed_in_block = row.confirmedInBlock,
                    created_at = row.createdAt.takeIf { it != 0L } ?: currentTimeMillis(),
                )
            }

            override fun list(): List<PrivateSendRow> =
                storageDb.privateSendsQueries
                    .list()
                    .executeAsList()
                    .map { it.toRow() }

            override fun setConfirmedInBlock(
                txid: String,
                height: Long,
            ) {
                storageDb.privateSendsQueries.setConfirmedInBlock(confirmed_in_block = height, txid = txid)
            }
        }

    override val sends =
        object : SendsRepository {
            override fun get(txid: String): SendRow? =
                storageDb.sendsQueries
                    .get(txid)
                    .executeAsOneOrNull()
                    ?.toRow()

            override fun upsert(row: SendRow) {
                storageDb.sendsQueries.upsert(
                    txid = row.txid,
                    tx_hex = row.txHex,
                    destination = row.destination,
                    utxos = encodePrivateSendCoins(row.coins),
                    confirmed_in_block = row.confirmedInBlock,
                    created_at = row.createdAt.takeIf { it != 0L } ?: currentTimeMillis(),
                )
            }

            override fun list(): List<SendRow> =
                storageDb.sendsQueries
                    .list()
                    .executeAsList()
                    .map { it.toRow() }

            override fun setConfirmedInBlock(
                txid: String,
                height: Long,
            ) {
                storageDb.sendsQueries.setConfirmedInBlock(confirmed_in_block = height, txid = txid)
            }
        }

    override val peers =
        object : PeersRepository {
            override fun upsert(peer: PeerWrite) {
                val now = currentTimeMillis()
                storageDb.peersQueries.upsert(
                    host = peer.host,
                    port = peer.port.toLong(),
                    services = toSqliteServices(peer.services),
                    alive = if (peer.alive) 1L else 0L,
                    used_for_blocks = if (peer.usedForBlocks) 1L else 0L,
                    last_probed_at = peer.lastProbedAt,
                    created_at = peer.createdAt ?: now,
                    updated_at = peer.updatedAt ?: now,
                )
            }

            override fun list(): List<Peer> =
                storageDb.peersQueries
                    .list()
                    .executeAsList()
                    .map(::rowToPeer)

            override fun count(): Int =
                storageDb.peersQueries
                    .count()
                    .executeAsOne()
                    .toInt()

            override fun listAlive(): List<Peer> =
                storageDb.peersQueries
                    .listAlive()
                    .executeAsList()
                    .map(::rowToPeer)

            override fun listAliveWithServices(
                serviceBits: ULong,
                limit: Int,
                options: AliveServiceOptions?,
            ): List<Peer> {
                if (limit <= 0) return emptyList()
                val mask = toSqliteServices(serviceBits)
                val rows =
                    if (options?.unusedForBlocks == true) {
                        storageDb.peersQueries.listAliveWithServicesUnused(mask, limit.toLong())
                    } else {
                        storageDb.peersQueries.listAliveWithServices(mask, limit.toLong())
                    }
                return rows.executeAsList().map(::rowToPeer)
            }

            override fun listWithServices(
                serviceBits: ULong,
                limit: Int,
            ): List<Peer> {
                if (limit <= 0) return emptyList()
                return storageDb.peersQueries
                    .listWithServices(toSqliteServices(serviceBits), limit.toLong())
                    .executeAsList()
                    .map(::rowToPeer)
            }

            override fun listUnprobedWithServicesUnused(
                serviceBits: ULong,
                limit: Int,
            ): List<Peer> {
                if (limit <= 0) return emptyList()
                return storageDb.peersQueries
                    .listUnprobedWithServicesUnused(toSqliteServices(serviceBits), limit.toLong())
                    .executeAsList()
                    .map(::rowToPeer)
            }

            override fun listProbeQueue(limit: Int): List<Peer> {
                if (limit <= 0) return emptyList()
                return storageDb.peersQueries
                    .listProbeQueue(limit.toLong())
                    .executeAsList()
                    .map(::rowToPeer)
            }

            override fun listOldestDeadWithServices(
                serviceBits: ULong,
                limit: Int,
            ): List<Peer> {
                if (limit <= 0) return emptyList()
                return storageDb.peersQueries
                    .listOldestDeadWithServices(toSqliteServices(serviceBits), limit.toLong()) { host, port, services, alive, usedForBlocks, lastProbedAt, createdAt, updatedAt ->
                        Peer(
                            host = host,
                            port = port.toInt(),
                            services = fromSqliteServices(services),
                            alive = alive == 1L,
                            usedForBlocks = usedForBlocks == 1L,
                            lastProbedAt = lastProbedAt,
                            createdAt = createdAt,
                            updatedAt = updatedAt,
                        )
                    }.executeAsList()
            }

            override fun markProbed(
                host: String,
                port: Int,
                at: Long,
            ) {
                val now = currentTimeMillis()
                storageDb.peersQueries.markProbed(at, now, host, port.toLong())
            }

            override fun markAlive(
                host: String,
                port: Int,
                alive: Boolean,
            ) {
                val now = currentTimeMillis()
                storageDb.peersQueries.markAlive(
                    alive = if (alive) 1L else 0L,
                    updated_at = now,
                    host = host,
                    port = port.toLong(),
                )
            }

            override fun markUsedForBlocks(
                host: String,
                port: Int,
            ) {
                storageDb.peersQueries.markUsedForBlocks(
                    updated_at = currentTimeMillis(),
                    host = host,
                    port = port.toLong(),
                )
            }
        }
    override val headers =
        object : HeadersRepository {
            override fun ensureCheckpoint(checkpoint: HeaderRecord) {
                val n = count()
                if (n == 0) {
                    val work = headerWorkFromBytes(checkpoint.header)
                    storageDb.headersQueries.insert(
                        height = checkpoint.height.toLong(),
                        hash_internal_hex = checkpoint.hashInternalHex,
                        header_ = checkpoint.header,
                        cumulative_work = work.toString(),
                    )
                    return
                }
                val row =
                    storageDb.headersQueries.first().executeAsOneOrNull()
                        ?: error("checkpoint: headers table inconsistent")
                val existing = rowToStoredHeader(row)
                if (
                    existing.height != checkpoint.height ||
                    existing.hashInternalHex != checkpoint.hashInternalHex ||
                    !equalBytes(existing.header, checkpoint.header)
                ) {
                    error(
                        "checkpoint mismatch: stored height ${existing.height} hashInternalHex ${existing.hashInternalHex}, " +
                            "expected height ${checkpoint.height} hashInternalHex ${checkpoint.hashInternalHex}. " +
                            "Delete blueberry.data/blueberry.sqlite (or clear headers rows) and restart.",
                    )
                }
            }

            override fun tip(): StoredHeader? =
                storageDb.headersQueries
                    .tip()
                    .executeAsOneOrNull()
                    ?.let(::rowToStoredHeader)

            override fun count(): Int =
                storageDb.headersQueries
                    .count()
                    .executeAsOne()
                    .toInt()

            override fun minHeight(): Int? =
                storageDb.headersQueries
                    .minHeight()
                    .executeAsOne()
                    .h
                    ?.toInt()

            override fun get(height: Int): StoredHeader? =
                storageDb.headersQueries
                    .get(height.toLong())
                    .executeAsOneOrNull()
                    ?.let(::rowToStoredHeader)

            override fun heightForHashInternal(hashInternalHex: String): Int? =
                storageDb.headersQueries
                    .heightForHashInternal(hashInternalHex)
                    .executeAsOneOrNull()
                    ?.toInt()

            override fun loadRange(
                fromHeight: Int,
                toHeight: Int,
            ): List<StoredHeader> =
                storageDb.headersQueries
                    .loadRange(fromHeight.toLong(), toHeight.toLong())
                    .executeAsList()
                    .map(::rowToStoredHeader)

            override fun loadAll(): List<StoredHeader> =
                storageDb.headersQueries
                    .loadAll()
                    .executeAsList()
                    .map(::rowToStoredHeader)

            override fun append(headers: List<HeaderWrite>) {
                if (headers.isEmpty()) return
                transaction {
                    val tipWork = tip()?.cumulativeWork ?: BigInteger.ZERO
                    insertHeaderWrites(headers, tipWork)
                }
            }

            override fun replaceAfter(
                commonAncestorHeight: Int,
                headers: List<HeaderWrite>,
            ) {
                transaction {
                    storageDb.headersQueries.deleteAfter(commonAncestorHeight.toLong())
                    val ancestorWork = get(commonAncestorHeight)?.cumulativeWork ?: BigInteger.ZERO
                    insertHeaderWrites(headers, ancestorWork)
                }
            }
        }
    override val filterHeaders =
        object : FilterHeadersRepository {
            override fun tip(): FilterHeaderRecord? =
                storageDb.filterHeadersQueries
                    .tip()
                    .executeAsOneOrNull()
                    ?.let(::rowToFilterHeaderRecord)

            override fun get(height: Int): FilterHeaderRecord? =
                storageDb.filterHeadersQueries
                    .get(height.toLong())
                    .executeAsOneOrNull()
                    ?.let(::rowToFilterHeaderRecord)

            override fun minHeight(): Int? =
                storageDb.filterHeadersQueries
                    .minHeight()
                    .executeAsOne()
                    .h
                    ?.toInt()

            override fun loadRange(
                fromHeight: Int,
                toHeight: Int,
            ): List<FilterHeaderRecord> =
                storageDb.filterHeadersQueries
                    .loadRange(fromHeight.toLong(), toHeight.toLong())
                    .executeAsList()
                    .map(::rowToFilterHeaderRecord)

            override fun append(rows: List<FilterHeaderRecord>) {
                if (rows.isEmpty()) return
                transaction {
                    for (row in rows) {
                        storageDb.filterHeadersQueries.insert(
                            height = row.height.toLong(),
                            header_ = row.header,
                        )
                    }
                }
            }

            override fun deleteFrom(height: Int) {
                storageDb.filterHeadersQueries.deleteFrom(height.toLong())
            }
        }
    override val filters =
        object : FiltersRepository {
            override fun count(): Int =
                storageDb.filtersQueries
                    .countFilters()
                    .executeAsOne()
                    .toInt()

            override fun countInRange(
                from: Int,
                to: Int,
            ): Int =
                storageDb.filtersQueries
                    .countInRange(from.toLong(), to.toLong())
                    .executeAsOne()
                    .toInt()

            override fun minHeight(): Int? =
                storageDb.filtersQueries
                    .minHeight()
                    .executeAsOne()
                    .h
                    ?.toInt()

            override fun maxHeight(): Int? =
                storageDb.filtersQueries
                    .maxHeight()
                    .executeAsOne()
                    .h
                    ?.toInt()

            override fun has(height: Int): Boolean = storageDb.filtersQueries.has(height.toLong()).executeAsOneOrNull() != null

            override fun get(height: Int): FilterRecord? =
                storageDb.filtersQueries
                    .get(height.toLong())
                    .executeAsOneOrNull()
                    ?.let(::rowToFilterRecord)

            override fun hashAt(height: Int): String? = storageDb.filtersQueries.hashAt(height.toLong()).executeAsOneOrNull()

            override fun firstHashMismatch(
                from: Int,
                to: Int,
            ): Int? {
                if (to < from) return null
                return storageDb.filtersQueries
                    .firstHashMismatch(from.toLong(), to.toLong())
                    .executeAsOneOrNull()
                    ?.toInt()
            }

            override fun missingRanges(
                from: Int,
                to: Int,
                maxSpan: Int,
            ): List<HeightRange> {
                if (to < from) return emptyList()
                val span = maxOf(1, maxSpan)

                fun pushChunks(
                    ranges: MutableList<HeightRange>,
                    start: Int,
                    end: Int,
                ) {
                    var s = start
                    while (s <= end) {
                        val e = minOf(s + span - 1, end)
                        ranges.add(HeightRange(s, e))
                        s = e + 1
                    }
                }

                val minH = minHeight()
                val maxH = maxHeight()
                if (minH == null || maxH == null) {
                    val ranges = mutableListOf<HeightRange>()
                    pushChunks(ranges, from, to)
                    return ranges
                }
                val total = count()
                if (total > 0 && maxH - minH + 1 == total) {
                    val ranges = mutableListOf<HeightRange>()
                    if (from < minH) pushChunks(ranges, from, minOf(minH - 1, to))
                    if (maxH < to) pushChunks(ranges, maxOf(maxH + 1, from), to)
                    return ranges
                }

                val heights =
                    storageDb.filtersQueries
                        .heightsInRange(from.toLong(), to.toLong())
                        .executeAsList()
                        .map { it.toInt() }

                val ranges = mutableListOf<HeightRange>()
                var expect = from
                for (height in heights) {
                    if (height > expect) pushChunks(ranges, expect, height - 1)
                    expect = height + 1
                }
                if (expect <= to) pushChunks(ranges, expect, to)
                return ranges
            }

            override fun completeInRange(
                from: Int,
                to: Int,
            ): Boolean {
                if (to < from) return true
                val minH = minHeight()
                val maxH = maxHeight()
                if (minH == null || maxH == null) return false
                if (minH > from || maxH < to) return false
                val total = count()
                if (maxH - minH + 1 == total) return true
                return countInRange(from, to) == to - from + 1
            }

            override fun append(rows: List<FilterRecord>) {
                if (rows.isEmpty()) return
                transaction {
                    for (row in rows) {
                        storageDb.filtersQueries.insert(
                            height = row.height.toLong(),
                            block_hash_internal_hex = row.blockHashInternalHex,
                            filter = row.filter,
                        )
                        storageDb.filtersQueries.insertUnscanned(row.height.toLong())
                    }
                }
            }

            override fun listNeedingMatch(limit: Int): List<FilterRecord> {
                if (limit <= 0) return emptyList()
                return storageDb.filtersQueries
                    .listNeedingMatch(limit.toLong())
                    .executeAsList()
                    .map {
                        FilterRecord(
                            height = it.height.toInt(),
                            blockHashInternalHex = it.block_hash_internal_hex,
                            filter = it.filter,
                        )
                    }
            }

            override fun countScanned(): Int = count() - unscannedCount()

            override fun hasUnscanned(): Boolean = storageDb.filtersQueries.hasUnscanned().executeAsOneOrNull() != null

            private fun unscannedCount(): Int =
                storageDb.filtersQueries
                    .countUnscanned()
                    .executeAsOne()
                    .toInt()

            override fun markScanned(heights: List<Int>) {
                if (heights.isEmpty()) return
                val sorted = heights.sorted()
                val first = sorted[0]
                val contiguous = sorted.withIndex().all { (i, height) -> height == first + i }
                if (contiguous) {
                    storageDb.filtersQueries.deleteUnscannedRange(
                        first.toLong(),
                        (first + sorted.size - 1).toLong(),
                    )
                    return
                }
                transaction {
                    for (height in sorted) {
                        storageDb.filtersQueries.deleteUnscannedOne(height.toLong())
                    }
                }
            }

            override fun markUnscanned(heights: List<Int>) {
                if (heights.isEmpty()) return
                transaction {
                    for (height in heights) {
                        storageDb.filtersQueries.insertUnscanned(height.toLong())
                    }
                }
            }

            override fun markUnscannedFrom(fromHeight: Int) {
                storageDb.filtersQueries.markUnscannedFrom(fromHeight.toLong())
            }

            override fun deleteFrom(height: Int) {
                transaction {
                    storageDb.filtersQueries.deleteUnscannedFrom(height.toLong())
                    storageDb.filtersQueries.deleteFiltersFrom(height.toLong())
                }
            }
        }
    override val matchedBlocks =
        object : MatchedBlocksRepository {
            override fun insert(block: MatchedBlock): Boolean {
                var inserted = false
                transaction {
                    storageDb.matchedBlocksQueries.insert(
                        height = block.height.toLong(),
                        block_hash_internal_hex = block.blockHashInternalHex,
                    )
                    inserted = storageDb.matchedBlocksQueries.changes().executeAsOne() > 0L
                }
                return inserted
            }

            override fun get(height: Int): MatchedBlock? =
                storageDb.matchedBlocksQueries.get(height.toLong()).executeAsOneOrNull()?.let {
                    MatchedBlock(it.height.toInt(), it.block_hash_internal_hex)
                }

            override fun count(): Int =
                storageDb.matchedBlocksQueries
                    .count()
                    .executeAsOne()
                    .toInt()

            override fun listNeedingDownload(limit: Int): List<MatchedBlock> {
                if (limit <= 0) return emptyList()
                return storageDb.matchedBlocksQueries
                    .listNeedingDownload(limit.toLong())
                    .executeAsList()
                    .map { MatchedBlock(it.height.toInt(), it.block_hash_internal_hex) }
            }
        }

    override val blocks =
        object : BlocksRepository {
            override fun count(): Int =
                storageDb.blocksQueries
                    .count()
                    .executeAsOne()
                    .toInt()

            override fun has(height: Int): Boolean = storageDb.blocksQueries.has(height.toLong()).executeAsOneOrNull() != null

            override fun get(height: Int): DownloadedBlock? {
                val meta =
                    storageDb.blocksQueries.getMeta(height.toLong()).executeAsOneOrNull()
                        ?: return null
                val total = meta.nbytes.toInt()
                if (total < 0) return null
                val blob = ByteArray(total)
                var offset = 0
                while (offset < total) {
                    val chunkLen = minOf(BLOCK_BLOB_CHUNK, total - offset)
                    val chunk =
                        storageDb.blocksQueries
                            .getBlobChunk(
                                value = (offset + 1).toString(),
                                value_ = chunkLen.toString(),
                                height = height.toLong(),
                            ).executeAsOneOrNull() ?: return null
                    if (chunk.size > chunkLen) return null
                    chunk.copyInto(blob, destinationOffset = offset)
                    offset += chunk.size
                    if (chunk.isEmpty()) break
                }
                if (offset != total) return null
                return DownloadedBlock(
                    height = meta.height.toInt(),
                    blockHashInternalHex = meta.block_hash_internal_hex,
                    block = blob,
                )
            }

            override fun insert(block: DownloadedBlock): Boolean {
                var inserted = false
                transaction {
                    storageDb.blocksQueries.insert(
                        height = block.height.toLong(),
                        block_hash_internal_hex = block.blockHashInternalHex,
                        block = block.block,
                    )
                    inserted = storageDb.blocksQueries.changes().executeAsOne() > 0L
                }
                return inserted
            }

            override fun insertIfMatched(block: DownloadedBlock): Boolean {
                var inserted = false
                transaction {
                    storageDb.blocksQueries.insertIfMatched(
                        height = block.height.toLong(),
                        block_hash_internal_hex = block.blockHashInternalHex,
                        block = block.block,
                        height_ = block.height.toLong(),
                        block_hash_internal_hex_ = block.blockHashInternalHex,
                    )
                    inserted = storageDb.blocksQueries.changes().executeAsOne() > 0L
                }
                return inserted
            }

            override fun listNeedingParseHeights(limit: Int): List<Int> {
                if (limit <= 0) return emptyList()
                return storageDb.blocksQueries
                    .listNeedingParseHeights(limit.toLong())
                    .executeAsList()
                    .map { it.toInt() }
            }
        }

    override val parsedBlocks =
        object : ParsedBlocksRepository {
            override fun has(height: Int): Boolean = storageDb.parsedBlocksQueries.has(height.toLong()).executeAsOneOrNull() != null

            override fun mark(height: Int) {
                storageDb.parsedBlocksQueries.mark(height.toLong())
            }

            override fun count(): Int =
                storageDb.parsedBlocksQueries
                    .count()
                    .executeAsOne()
                    .toInt()

            override fun clearFrom(fromHeight: Int) {
                storageDb.parsedBlocksQueries.clearFrom(fromHeight.toLong())
            }
        }

    override val transactions =
        object : TransactionsRepository {
            override fun upsert(tx: StoredTx) {
                storageDb.transactionsQueries.upsert(
                    txid = tx.txid,
                    height = tx.height.toLong(),
                    tx_index = tx.txIndex.toLong(),
                    block_hash_internal_hex = tx.blockHashInternalHex,
                    tx = tx.tx,
                    net_delta_sats = tx.netDeltaSats,
                )
            }

            override fun list(): List<StoredTx> =
                storageDb.transactionsQueries
                    .list()
                    .executeAsList()
                    .map(::rowToStoredTx)

            override fun count(): Int =
                storageDb.transactionsQueries
                    .count()
                    .executeAsOne()
                    .toInt()

            override fun fingerprint(): TxSetFingerprint {
                if (count() == 0) return TxSetFingerprint(0, 0, null)
                val row = storageDb.transactionsQueries.fingerprint().executeAsOne()
                return TxSetFingerprint(
                    count = row.n.toInt(),
                    netDeltaSum = row.s,
                    newestTxid = row.newest,
                )
            }

            override fun minHeight(): Int? =
                storageDb.transactionsQueries
                    .minHeight()
                    .executeAsOne()
                    .h
                    ?.toInt()

            override fun get(txid: String): StoredTx? =
                storageDb.transactionsQueries
                    .get(txid)
                    .executeAsOneOrNull()
                    ?.let(::rowToStoredTx)

            override fun setNetDelta(
                txid: String,
                netDeltaSats: Long,
            ) {
                storageDb.transactionsQueries.setNetDelta(net_delta_sats = netDeltaSats, txid = txid)
            }
        }

    override fun transaction(fn: () -> Unit) {
        if (driver.currentTransaction() != null) {
            fn()
            return
        }
        storageDb.transaction { fn() }
    }

    override fun rewindAfter(ancestorHeight: Int) {
        transaction {
            val height = ancestorHeight.toLong()
            storageDb.filtersQueries.rewindFilterHeaders(height)
            storageDb.filtersQueries.rewindUnscanned(height)
            storageDb.filtersQueries.rewindFilters(height)
            storageDb.filtersQueries.rewindMatched(height)
            storageDb.filtersQueries.rewindBlocks(height)
            storageDb.filtersQueries.rewindParsed(height)
            storageDb.filtersQueries.rewindTransactions(height)
        }
    }

    override fun wipeFiltersFrom(
        height: Int,
        options: WipeFiltersFromOptions?,
    ) {
        transaction {
            storageDb.filtersQueries.deleteUnscannedFrom(height.toLong())
            storageDb.filtersQueries.deleteFiltersFrom(height.toLong())
            storageDb.filtersQueries.deleteFilterHeadersFrom(height.toLong())
            val prevHeaderHeight = options?.prevHeaderHeight
            if (prevHeaderHeight != null) {
                storageDb.filtersQueries.deleteFilterHeaderAt(prevHeaderHeight.toLong())
            }
        }
    }

    override fun close() {
        driver.close()
    }

    internal fun pragmaValue(pragma: String): String = queryPragmaValue(driver, pragma)

    private fun insertHeaderWrites(
        headerRecords: List<HeaderWrite>,
        startingWork: BigInteger,
    ) {
        var cumulative = startingWork
        for (h in headerRecords) {
            cumulative = h.cumulativeWork ?: (cumulative + headerWorkFromBytes(h.header))
            storageDb.headersQueries.insert(
                height = h.height.toLong(),
                hash_internal_hex = h.hashInternalHex,
                header_ = h.header,
                cumulative_work = cumulative.toString(),
            )
        }
    }
}

private fun Sends.toRow(): SendRow =
    SendRow(
        txid = txid,
        txHex = tx_hex,
        destination = destination,
        coins = decodePrivateSendCoins(utxos),
        confirmedInBlock = confirmed_in_block,
        createdAt = created_at,
    )

private fun Private_sends.toRow(): PrivateSendRow =
    PrivateSendRow(
        txid = txid,
        partner = partner,
        orderId = order_id,
        txHex = tx_hex,
        destination = destination,
        refundAddress = refund_address,
        coins = decodePrivateSendCoins(utxos),
        confirmedInBlock = confirmed_in_block,
        createdAt = created_at,
    )

private fun rowToFilterHeaderRecord(row: Filter_headers): FilterHeaderRecord =
    FilterHeaderRecord(
        height = row.height.toInt(),
        header = row.header_,
    )

private fun rowToFilterRecord(row: Filters): FilterRecord =
    FilterRecord(
        height = row.height.toInt(),
        blockHashInternalHex = row.block_hash_internal_hex,
        filter = row.filter,
    )

private fun rowToStoredHeader(row: Headers): StoredHeader =
    StoredHeader(
        height = row.height.toInt(),
        hashInternalHex = row.hash_internal_hex,
        header = row.header_,
        cumulativeWork = BigInteger.parseString(row.cumulative_work),
    )

private fun rowToStoredTx(row: Transactions): StoredTx =
    StoredTx(
        txid = row.txid,
        height = row.height.toInt(),
        txIndex = row.tx_index.toInt(),
        blockHashInternalHex = row.block_hash_internal_hex,
        tx = row.tx,
        netDeltaSats = row.net_delta_sats,
    )

private fun rowToPeer(row: Peers): Peer =
    Peer(
        host = row.host,
        port = row.port.toInt(),
        services = fromSqliteServices(row.services),
        alive = row.alive == 1L,
        usedForBlocks = row.used_for_blocks == 1L,
        lastProbedAt = row.last_probed_at,
        createdAt = row.created_at,
        updatedAt = row.updated_at,
    )
