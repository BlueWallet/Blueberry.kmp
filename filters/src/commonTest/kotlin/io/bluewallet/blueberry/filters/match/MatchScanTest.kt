package io.bluewallet.blueberry.filters.match

import io.bluewallet.bip157.hexToBytes
import io.bluewallet.bip158.buildBasicFilter
import io.bluewallet.blueberry.storage.FilterRecord
import io.bluewallet.blueberry.storage.MatchedBlock
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.deriveWatchWallet
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MNEMONIC =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

private fun displayHash(internalHex: String): ByteArray {
    val internal = hexToBytes(internalHex)
    return ByteArray(32) { i -> internal[31 - i] }
}

private fun append(
    db: io.bluewallet.blueberry.storage.Database,
    height: Int,
    internalHex: String,
    elements: List<ByteArray>,
) {
    db.filters.append(
        listOf(
            FilterRecord(
                height = height,
                blockHashInternalHex = internalHex,
                filter = buildBasicFilter(displayHash(internalHex), elements),
            ),
        ),
    )
}

class MatchScanTest {
    @Test
    fun hit_inserts_matched_block_and_marks_scanned_miss_only_marks_scanned() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            val wallet = deriveWatchWallet(MNEMONIC, 4)
            val hitHash = "11".repeat(32)
            val missHash = "22".repeat(32)
            append(db, 100, hitHash, listOf(wallet.scripts[0]))
            append(
                db,
                101,
                missHash,
                listOf(byteArrayOf(0x00, 0x14) + ByteArray(20) { 0xab.toByte() }),
            )
            val matches = mutableListOf<Int>()
            val advanced =
                scanFiltersForMatches(
                    db,
                    wallet.scripts,
                    MatchScanCallbacks(onMatch = { matches.add(it.height) }),
                    MatchScanOptions(yieldFn = {}, batchGapMs = 0, chunkSize = 1000),
                )
            assertEquals(2, advanced)
            assertEquals(listOf(100), matches)
            assertEquals(1, db.matchedBlocks.count())
            assertEquals(2, db.filters.countScanned())
            assertEquals(emptyList(), db.filters.listNeedingMatch(10))
            db.close()
        }

    @Test
    fun skips_already_scanned_rows() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            val wallet = deriveWatchWallet(MNEMONIC, 4)
            append(db, 200, "33".repeat(32), listOf(wallet.scripts[0]))
            db.filters.markScanned(listOf(200))
            val matches = mutableListOf<Int>()
            val advanced =
                scanFiltersForMatches(
                    db,
                    wallet.scripts,
                    MatchScanCallbacks(onMatch = { matches.add(it.height) }),
                    MatchScanOptions(yieldFn = {}, batchGapMs = 0, chunkSize = 1000),
                )
            assertEquals(0, advanced)
            assertEquals(emptyList(), matches)
            assertEquals(0, db.matchedBlocks.count())
            db.close()
        }

    @Test
    fun refreshes_total_when_filters_are_appended_mid_scan() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            val wallet = deriveWatchWallet(MNEMONIC, 4)
            val junk = byteArrayOf(0x00, 0x14) + ByteArray(20) { 0xab.toByte() }
            append(db, 1, "01".repeat(32), listOf(junk))
            append(db, 2, "02".repeat(32), listOf(junk))

            val progressTotals = mutableListOf<Int>()
            var appended = false
            scanFiltersForMatches(
                db,
                wallet.scripts,
                MatchScanCallbacks(onProgress = { progressTotals.add(it.total) }),
                MatchScanOptions(
                    batchGapMs = 0,
                    chunkSize = 1,
                    yieldFn = {
                        if (!appended) {
                            appended = true
                            append(db, 3, "03".repeat(32), listOf(junk))
                        }
                    },
                ),
            )

            assertTrue(progressTotals.any { it >= 3 })
            assertEquals(3, progressTotals.last())
            db.close()
        }

    @Test
    fun does_not_re_emit_match_for_existing_matched_blocks_row() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            val wallet = deriveWatchWallet(MNEMONIC, 4)
            val hash = "44".repeat(32)
            append(db, 300, hash, listOf(wallet.scripts[0]))
            db.matchedBlocks.insert(MatchedBlock(height = 300, blockHashInternalHex = hash))
            val matches = mutableListOf<Int>()
            scanFiltersForMatches(
                db,
                wallet.scripts,
                MatchScanCallbacks(onMatch = { matches.add(it.height) }),
                MatchScanOptions(yieldFn = {}, batchGapMs = 0, chunkSize = 1000),
            )
            assertEquals(emptyList(), matches)
            assertEquals(emptyList(), db.filters.listNeedingMatch(10))
            db.close()
        }

    @Test
    fun progress_scanned_tracks_count_scanned_after_mid_scan_requeue() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            val wallet = deriveWatchWallet(MNEMONIC, 4)
            val junk = byteArrayOf(0x00, 0x14) + ByteArray(20) { 0xab.toByte() }
            for (h in 1..6) {
                append(db, h, h.toString(16).padStart(2, '0').repeat(32), listOf(junk))
            }

            val progress = mutableListOf<MatchScanProgress>()
            var requeued = false
            scanFiltersForMatches(
                db,
                wallet.scripts,
                MatchScanCallbacks(onProgress = { progress.add(it) }),
                MatchScanOptions(
                    batchGapMs = 0,
                    chunkSize = 2,
                    batchSize = 2,
                    yieldFn = {
                        if (!requeued && db.filters.countScanned() >= 2) {
                            requeued = true
                            db.filters.markUnscannedFrom(1)
                        }
                    },
                ),
            )

            for (p in progress) {
                assertTrue(p.scanned <= p.total)
            }
            assertEquals(MatchScanProgress(scanned = 6, total = 6), progress.last())
            db.close()
        }

    @Test
    fun default_chunk_is_smaller_than_the_db_batch_so_matching_can_yield() {
        assertTrue(MATCH_CHUNK_SIZE < MATCH_FILTER_BATCH_SIZE)
    }

    @Test
    fun drops_a_stale_in_memory_chunk_after_rewind_so_the_new_hash_can_match() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            val wallet = deriveWatchWallet(MNEMONIC, 4)
            val oldHash = "11".repeat(32)
            val newHash = "aa".repeat(32)
            append(db, 100, oldHash, listOf(wallet.scripts[0]))
            append(db, 101, oldHash, listOf(wallet.scripts[0]))

            var rewound = false
            scanFiltersForMatches(
                db,
                wallet.scripts,
                MatchScanCallbacks(),
                MatchScanOptions(
                    yieldFn = {
                        if (!rewound && db.filters.countScanned() >= 1) {
                            rewound = true
                            db.rewindAfter(100)
                            append(db, 101, newHash, listOf(wallet.scripts[0]))
                        }
                    },
                    batchGapMs = 0,
                    chunkSize = 1,
                ),
            )

            assertEquals(oldHash, db.matchedBlocks.get(100)?.blockHashInternalHex)
            assertEquals(newHash, db.matchedBlocks.get(101)?.blockHashInternalHex)
            assertEquals(emptyList(), db.filters.listNeedingMatch(10))
            db.close()
        }
}
