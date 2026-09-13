package io.bluewallet.blueberry.filters.match

import io.bluewallet.bip157.hexToBytes
import io.bluewallet.bip158.matchAnyBasicFilters
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.FilterRecord
import io.bluewallet.blueberry.storage.MatchedBlock
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlin.math.max

/** Rows loaded from SQLite per outer iteration. */
const val MATCH_FILTER_BATCH_SIZE = 1000

/**
 * Sync match slice before yielding to the event loop.
 * Smaller than the DB batch — matchAnyBasicFilters is a per-filter loop.
 */
const val MATCH_CHUNK_SIZE = 50

/** Extra sleep after each chunk; 0 = yield only (tests pass 0). */
const val MATCH_BATCH_GAP_MS = 0L

data class FilterMatch(
    val height: Int,
    val blockHashInternalHex: String,
)

data class MatchScanProgress(
    val scanned: Int,
    val total: Int,
)

class MatchScanCallbacks(
    val onMatch: ((FilterMatch) -> Unit)? = null,
    val onProgress: ((MatchScanProgress) -> Unit)? = null,
)

class MatchScanOptions(
    val batchSize: Int? = null,
    val chunkSize: Int? = null,
    val yieldFn: (suspend () -> Unit)? = null,
    val batchGapMs: Long? = null,
    val shouldContinue: (() -> Boolean)? = null,
)

private suspend fun defaultYield() {
    yield()
}

private suspend fun sleep(ms: Long) {
    if (ms <= 0) return
    delay(ms)
}

private fun toDisplayHash(internalHex: String): ByteArray {
    val internal = hexToBytes(internalHex)
    val out = ByteArray(32)
    for (i in 0 until 32) out[i] = internal[31 - i]
    return out
}

private fun rowStillCurrent(
    db: Database,
    row: FilterRecord,
): Boolean {
    if (db.filters.hashAt(row.height) != row.blockHashInternalHex) {
        return false
    }
    val header = db.headers.get(row.height)
    if (header != null && header.hashInternalHex != row.blockHashInternalHex) {
        return false
    }
    return true
}

/** Scan unscanned filters until empty or shouldContinue is false. */
suspend fun scanFiltersForMatches(
    db: Database,
    scripts: List<ByteArray>,
    callbacks: MatchScanCallbacks? = null,
    options: MatchScanOptions? = null,
): Int {
    val batchSize = max(1, options?.batchSize ?: MATCH_FILTER_BATCH_SIZE)
    val chunkSize = max(1, options?.chunkSize ?: MATCH_CHUNK_SIZE)
    val yieldFn = options?.yieldFn ?: { defaultYield() }
    val batchGapMs = max(0L, options?.batchGapMs ?: MATCH_BATCH_GAP_MS)
    val shouldContinue = options?.shouldContinue

    var total = db.filters.count()
    var scanned = db.filters.countScanned()
    var advanced = 0

    fun emitProgress() {
        total = db.filters.count()
        callbacks?.onProgress?.invoke(MatchScanProgress(scanned, total))
    }

    suspend fun pause() {
        if (batchGapMs > 0) {
            sleep(batchGapMs)
        } else {
            yieldFn()
        }
    }

    emitProgress()
    pause()

    while (shouldContinue?.invoke() != false) {
        val batch = db.filters.listNeedingMatch(batchSize)
        if (batch.isEmpty()) {
            emitProgress()
            return advanced
        }

        var offset = 0
        while (offset < batch.size) {
            if (shouldContinue?.invoke() == false) {
                emitProgress()
                return advanced
            }

            val chunk = batch.subList(offset, minOf(offset + chunkSize, batch.size))
            offset += chunkSize
            val current = chunk.filter { rowStillCurrent(db, it) }
            if (current.isEmpty()) {
                scanned = db.filters.countScanned()
                emitProgress()
                pause()
                continue
            }

            val filterBytesList = current.map { it.filter }
            val hashList = current.map { toDisplayHash(it.blockHashInternalHex) }

            val hitFlags =
                matchAnyBasicFilters(
                    filterBytesList,
                    hashList,
                    scripts,
                )

            val heights = ArrayList<Int>(current.size)
            for (i in current.indices) {
                val row = current[i]
                heights.add(row.height)
                if (hitFlags[i]) {
                    val inserted =
                        db.matchedBlocks.insert(
                            MatchedBlock(
                                height = row.height,
                                blockHashInternalHex = row.blockHashInternalHex,
                            ),
                        )
                    if (inserted) {
                        callbacks?.onMatch?.invoke(
                            FilterMatch(
                                height = row.height,
                                blockHashInternalHex = row.blockHashInternalHex,
                            ),
                        )
                    }
                }
                advanced++
            }

            db.filters.markScanned(heights)
            // Re-read: markUnscannedFrom (gap growth) can re-queue mid-scan; a
            // monotonic scanned++ would then report scanned > total.
            scanned = db.filters.countScanned()
            emitProgress()
            pause()
        }
    }

    return advanced
}
