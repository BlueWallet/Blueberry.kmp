package io.bluewallet.blueberry.filters.modules

import io.bluewallet.bip157.hexToBytes
import io.bluewallet.bip158.buildBasicFilter
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.FiltersProgressPayload
import io.bluewallet.blueberry.bus.ModuleStatus
import io.bluewallet.blueberry.bus.createMessageBus
import io.bluewallet.blueberry.filters.waitFor
import io.bluewallet.blueberry.peers.modules.ModuleContext
import io.bluewallet.blueberry.storage.FilterRecord
import io.bluewallet.blueberry.storage.HeaderWrite
import io.bluewallet.blueberry.storage.StoredTx
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.CreateWalletOptions
import io.bluewallet.blueberry.wallet.INITIAL_WATCH_COUNT
import io.bluewallet.blueberry.wallet.SyncFromDbResult
import io.bluewallet.blueberry.wallet.Wallet
import io.bluewallet.blueberry.wallet.WatchGaps
import io.bluewallet.blueberry.wallet.createWallet
import io.bluewallet.blueberry.wallet.deriveWatchWallet
import io.bluewallet.blueberry.wallet.saveWalletSecret
import io.bluewallet.blueberry.wallet.saveWatchGaps
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ABANDON_MNEMONIC =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

private const val BLUE_EXTERNAL_0 = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"

private fun displayHash(internalHex: String): ByteArray {
    val internal = hexToBytes(internalHex)
    return ByteArray(32) { i -> internal[31 - i] }
}

private fun filterContaining(
    scripts: List<ByteArray>,
    internalHex: String,
): ByteArray = buildBasicFilter(displayHash(internalHex), scripts)

private fun appendFilter(
    db: io.bluewallet.blueberry.storage.Database,
    height: Int,
    internalHex: String,
    scripts: List<ByteArray>,
) {
    db.filters.append(
        listOf(
            FilterRecord(
                height = height,
                blockHashInternalHex = internalHex,
                filter = filterContaining(scripts, internalHex),
            ),
        ),
    )
}

private fun needsMatch(
    db: io.bluewallet.blueberry.storage.Database,
    height: Int,
): Boolean = db.filters.listNeedingMatch(64).any { it.height == height }

class FiltersMatchingTest {
    @Test
    fun hit_on_init_emits_match_and_marks_scanned() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 4))
            val hash = "11".repeat(32)
            appendFilter(db, 100, hash, listOf(wallet.snapshot().addresses[0].scriptPubKey))

            val hits = mutableListOf<Pair<Int, String>>()
            bus.on(Event.FiltersMatch) { hits.add(it.height to it.blockHashInternalHex) }

            val mod =
                createFiltersMatchingModule(
                    ModuleContext(bus, db),
                    FiltersMatchingOptions(wallet = wallet, batchGapMs = 0, yieldFn = {}),
                )
            mod.start()
            waitFor { hits.size == 1 && !needsMatch(db, 100) }
            assertEquals(listOf(100 to hash), hits)
            assertEquals(1, db.matchedBlocks.count())
            mod.stop()
            db.close()
        }

    @Test
    fun miss_marks_scanned_without_emitting() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 4))
            val hash = "22".repeat(32)
            appendFilter(
                db,
                200,
                hash,
                listOf(byteArrayOf(0x00, 0x14) + ByteArray(20) { 0xab.toByte() }),
            )

            val hits = mutableListOf<Int>()
            bus.on(Event.FiltersMatch) { hits.add(it.height) }

            val mod =
                createFiltersMatchingModule(
                    ModuleContext(bus, db),
                    FiltersMatchingOptions(wallet = wallet, batchGapMs = 0, yieldFn = {}),
                )
            mod.start()
            waitFor { !needsMatch(db, 200) }
            assertEquals(emptyList(), hits)
            assertEquals(0, db.matchedBlocks.count())
            mod.stop()
            db.close()
        }

    @Test
    fun already_scanned_filter_is_skipped() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 4))
            val hash = "33".repeat(32)
            appendFilter(db, 300, hash, listOf(wallet.snapshot().addresses[0].scriptPubKey))
            db.filters.markScanned(listOf(300))

            val hits = mutableListOf<Int>()
            bus.on(Event.FiltersMatch) { hits.add(it.height) }

            val mod =
                createFiltersMatchingModule(
                    ModuleContext(bus, db),
                    FiltersMatchingOptions(wallet = wallet, batchGapMs = 0),
                )
            mod.start()
            delay(40)
            assertEquals(emptyList(), hits)
            assertFalse(needsMatch(db, 300))
            mod.stop()
            db.close()
        }

    @Test
    fun emits_matching_progress_on_start_and_after_each_batch() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 4))
            val junk = { fill: Byte -> byteArrayOf(0x00, 0x14) + ByteArray(20) { fill } }
            appendFilter(db, 1, "01".repeat(32), listOf(junk(0xcd.toByte())))
            db.filters.markScanned(listOf(1))
            appendFilter(db, 2, "02".repeat(32), listOf(junk(0xce.toByte())))
            appendFilter(db, 3, "03".repeat(32), listOf(junk(0xcf.toByte())))

            val events = mutableListOf<Pair<Int, Int>>()
            bus.on(Event.MatchingProgress) { events.add(it.scanned to it.total) }
            val logs = mutableListOf<String>()

            val mod =
                createFiltersMatchingModule(
                    ModuleContext(bus, db),
                    FiltersMatchingOptions(
                        wallet = wallet,
                        batchSize = 1,
                        batchGapMs = 0,
                        yieldFn = {},
                        log = { logs.add(it) },
                    ),
                )
            mod.start()
            assertEquals(1 to 3, events[0])
            waitFor { events.size >= 3 && db.filters.countScanned() == 3 }
            assertEquals(listOf(1, 2, 3), events.map { it.first })
            assertTrue(events.all { it.second == 3 })
            mod.stop()
            val text = logs.joinToString("\n")
            assertTrue(text.contains("start"))
            assertTrue(text.contains("scan start scanned=1 total=3"))
            assertTrue(text.contains("scan done"))
            assertTrue(text.contains("stop"))
            db.close()
        }

    @Test
    fun keeps_matching_while_matched_blocks_still_need_download() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 4))
            val junk = byteArrayOf(0x00, 0x14) + ByteArray(20) { 0xab.toByte() }
            appendFilter(db, 400, "11".repeat(32), listOf(wallet.snapshot().addresses[0].scriptPubKey))
            appendFilter(db, 401, "22".repeat(32), listOf(junk))

            val mod =
                createFiltersMatchingModule(
                    ModuleContext(bus, db),
                    FiltersMatchingOptions(
                        wallet = wallet,
                        batchSize = 1,
                        batchGapMs = 0,
                        yieldFn = {},
                    ),
                )
            mod.start()
            waitFor { db.matchedBlocks.count() == 1 }
            waitFor { !needsMatch(db, 401) }
            assertEquals(1, db.matchedBlocks.listNeedingDownload(10).size)
            mod.stop()
            db.close()
        }

    @Test
    fun idle_filters_progress_emits_matching_progress_with_new_total_before_scanning() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 4))
            val junk = byteArrayOf(0x00, 0x14) + ByteArray(20) { 0xab.toByte() }
            appendFilter(db, 1, "01".repeat(32), listOf(junk))
            db.filters.markScanned(listOf(1))

            val events = mutableListOf<Pair<Int, Int>>()
            bus.on(Event.MatchingProgress) { events.add(it.scanned to it.total) }

            val mod =
                createFiltersMatchingModule(
                    ModuleContext(bus, db),
                    FiltersMatchingOptions(wallet = wallet, batchSize = 1, batchGapMs = 0, yieldFn = {}),
                )
            mod.start()
            waitFor { events.isNotEmpty() }
            delay(30)
            assertEquals(1 to 1, events[0])

            appendFilter(db, 2, "02".repeat(32), listOf(junk))
            bus.emit(Event.FiltersProgress, FiltersProgressPayload(at = 1, downloaded = 2, total = 2))
            waitFor { db.filters.countScanned() == 2 }
            assertTrue(events.contains(1 to 2))
            mod.stop()
            db.close()
        }

    @Test
    fun idle_resumes_on_filters_progress_busy_kick_still_drains_new_work() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 4))
            val junkA = byteArrayOf(0x00, 0x14) + ByteArray(20) { 0xcd.toByte() }
            val junkB = byteArrayOf(0x00, 0x14) + ByteArray(20) { 0xce.toByte() }

            val gate = CompletableDeferred<Unit>()
            var gated = false

            val mod =
                createFiltersMatchingModule(
                    ModuleContext(bus, db),
                    FiltersMatchingOptions(
                        wallet = wallet,
                        batchSize = 1,
                        batchGapMs = 0,
                        yieldFn = {
                            if (!gated) {
                                gated = true
                                gate.await()
                            }
                        },
                    ),
                )
            mod.start()
            delay(30)

            appendFilter(db, 500, "55".repeat(32), listOf(junkA))
            bus.emit(Event.FiltersProgress, FiltersProgressPayload(at = 1, downloaded = 1, total = 1))
            waitFor { gated }

            appendFilter(db, 501, "56".repeat(32), listOf(junkB))
            bus.emit(Event.FiltersProgress, FiltersProgressPayload(at = 2, downloaded = 2, total = 2))
            bus.emit(Event.FiltersProgress, FiltersProgressPayload(at = 3, downloaded = 2, total = 2))

            gate.complete(Unit)
            waitFor { !needsMatch(db, 500) && !needsMatch(db, 501) }
            assertEquals(0, db.matchedBlocks.count())
            mod.stop()
            db.close()
        }

    @Test
    fun rederives_watchlist_when_key_value_gaps_grow() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 4))
            val expanded = deriveWatchWallet(ABANDON_MNEMONIC, 8)
            val index5External = expanded.addresses.first { !it.change && it.index == 5 }
            val hash = "66".repeat(32)
            db.headers.append(
                listOf(
                    HeaderWrite(
                        height = 600,
                        hashInternalHex = hash,
                        header = ByteArray(80),
                    ),
                ),
            )
            appendFilter(db, 600, hash, listOf(index5External.scriptPubKey))

            val hits = mutableListOf<Pair<Int, String>>()
            bus.on(Event.FiltersMatch) { hits.add(it.height to it.blockHashInternalHex) }
            val logs = mutableListOf<String>()

            val mod =
                createFiltersMatchingModule(
                    ModuleContext(bus, db),
                    FiltersMatchingOptions(
                        wallet = wallet,
                        batchGapMs = 0,
                        yieldFn = {},
                        log = { logs.add(it) },
                    ),
                )
            mod.start()
            waitFor { !needsMatch(db, 600) }
            assertEquals(emptyList(), hits)
            assertEquals(0, db.matchedBlocks.count())

            saveWatchGaps(db, WatchGaps(8, 4))
            db.filters.markUnscanned(listOf(600))
            bus.emit(Event.FiltersProgress, FiltersProgressPayload(at = 1, downloaded = 1, total = 1))

            waitFor { hits.any { it.first == 600 } }
            assertEquals(listOf(600 to hash), hits)
            assertEquals(1, db.matchedBlocks.count())
            val text = logs.joinToString("\n")
            assertTrue(text.contains("rematch from=600"))
            val afterRematch = text.substring(text.indexOf("rematch from=600"))
            assertTrue(afterRematch.contains("scan start scanned=0 total=1"))
            mod.stop()
            db.close()
        }

    @Test
    fun gap_growth_during_busy_scan_rematches_after_old_scripts_mark_scanned() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 4))
            val expanded = deriveWatchWallet(ABANDON_MNEMONIC, 8)
            val index5 = expanded.addresses.first { !it.change && it.index == 5 }
            val hash = "77".repeat(32)
            appendFilter(db, 700, hash, listOf(index5.scriptPubKey))
            db.transactions.upsert(
                StoredTx(
                    txid = "ab".repeat(32),
                    height = 700,
                    txIndex = 0,
                    blockHashInternalHex = hash,
                    tx = byteArrayOf(0x00),
                    netDeltaSats = 1,
                ),
            )

            val gate = CompletableDeferred<Unit>()
            var gated = false
            val hits = mutableListOf<Int>()
            bus.on(Event.FiltersMatch) { hits.add(it.height) }

            val mod =
                createFiltersMatchingModule(
                    ModuleContext(bus, db),
                    FiltersMatchingOptions(
                        wallet = wallet,
                        batchGapMs = 0,
                        yieldFn = {
                            if (!gated) {
                                gated = true
                                gate.await()
                            }
                        },
                    ),
                )
            mod.start()
            waitFor { gated }

            saveWatchGaps(db, WatchGaps(8, 4))
            wallet.refresh()
            db.filters.markUnscannedFrom(700)
            bus.emit(Event.FiltersProgress, FiltersProgressPayload(at = 1, downloaded = 1, total = 1))

            gate.complete(Unit)
            waitFor { hits.contains(700) && db.matchedBlocks.count() == 1 }
            mod.stop()
            db.close()
        }

    @Test
    fun gap_growth_aborts_in_flight_scan_instead_of_draining_with_stale_scripts() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 4))
            val expanded = deriveWatchWallet(ABANDON_MNEMONIC, 8)
            val index5 = expanded.addresses.first { !it.change && it.index == 5 }
            val junk = byteArrayOf(0x00, 0x14) + ByteArray(20) { 0xab.toByte() }

            for (h in 1..30) {
                appendFilter(db, h, h.toString(16).padStart(2, '0').repeat(32), listOf(junk))
            }
            val hitHash = "99".repeat(32)
            appendFilter(db, 31, hitHash, listOf(index5.scriptPubKey))
            db.transactions.upsert(
                StoredTx(
                    txid = "ef".repeat(32),
                    height = 1,
                    txIndex = 0,
                    blockHashInternalHex = "01".repeat(32),
                    tx = byteArrayOf(0x00),
                    netDeltaSats = 1,
                ),
            )

            var yields = 0
            var grown = false
            var hit = false
            bus.on(Event.FiltersMatch) { if (it.height == 31) hit = true }

            val mod =
                createFiltersMatchingModule(
                    ModuleContext(bus, db),
                    FiltersMatchingOptions(
                        wallet = wallet,
                        batchSize = 1,
                        batchGapMs = 0,
                        yieldFn = {
                            yields++
                            if (!grown && yields >= 2) {
                                grown = true
                                saveWatchGaps(db, WatchGaps(8, 4))
                                wallet.refresh()
                                db.filters.markUnscannedFrom(1)
                                bus.emit(
                                    Event.FiltersProgress,
                                    FiltersProgressPayload(at = 1, downloaded = 31, total = 31),
                                )
                            }
                        },
                    ),
                )
            mod.start()
            waitFor(5000) { hit }
            assertTrue(yields < 45)
            mod.stop()
            db.close()
        }

    @Test
    fun scan_done_total_includes_filters_that_arrived_mid_scan() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 4))
            val junk = byteArrayOf(0x00, 0x14) + ByteArray(20) { 0xab.toByte() }
            appendFilter(db, 1, "01".repeat(32), listOf(junk))
            appendFilter(db, 2, "02".repeat(32), listOf(junk))

            var injected = false
            val logs = mutableListOf<String>()
            val mod =
                createFiltersMatchingModule(
                    ModuleContext(bus, db),
                    FiltersMatchingOptions(
                        wallet = wallet,
                        batchSize = 1,
                        batchGapMs = 0,
                        yieldFn = {
                            if (!injected) {
                                injected = true
                                appendFilter(db, 3, "03".repeat(32), listOf(junk))
                            }
                        },
                        log = { logs.add(it) },
                    ),
                )
            mod.start()
            waitFor { db.filters.countScanned() == 3 }
            mod.stop()
            val text = logs.joinToString("\n")
            assertTrue(text.contains("scan start scanned=0 total=2"))
            assertTrue(text.contains("scan done scanned=3 total=3"))
            assertFalse(Regex("scan done scanned=\\d+ total=2").containsMatchIn(text))
            db.close()
        }

    @Test
    fun idle_loop_with_no_pending_work_does_not_log_scan_start_or_scan_done() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 4))
            val junk = byteArrayOf(0x00, 0x14) + ByteArray(20) { 0xab.toByte() }
            appendFilter(db, 1, "01".repeat(32), listOf(junk))
            db.filters.markScanned(listOf(1))

            val logs = mutableListOf<String>()
            val mod =
                createFiltersMatchingModule(
                    ModuleContext(bus, db),
                    FiltersMatchingOptions(
                        wallet = wallet,
                        batchGapMs = 0,
                        yieldFn = {},
                        log = { logs.add(it) },
                    ),
                )
            mod.start()
            delay(120)
            val text = logs.joinToString("\n")
            mod.stop()
            assertTrue(text.contains("start"))
            assertFalse(Regex("scan start").containsMatchIn(text))
            assertFalse(Regex("scan done").containsMatchIn(text))
            db.close()
        }

    @Test
    fun scan_throw_does_not_stop_the_matching_loop() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val inner = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 4))
            val hash = "88".repeat(32)
            appendFilter(db, 800, hash, listOf(inner.snapshot().addresses[0].scriptPubKey))

            var syncs = 0
            val wallet =
                object : Wallet {
                    override fun snapshot() = inner.snapshot()

                    override fun scripts() = inner.scripts()

                    override fun gaps() = inner.gaps()

                    override fun peekGaps() = inner.peekGaps()

                    override fun refresh() = inner.refresh()

                    override fun syncFromDb(): SyncFromDbResult {
                        syncs++
                        if (syncs == 1) error("scan boom")
                        return inner.syncFromDb()
                    }
                }

            val hits = mutableListOf<Int>()
            bus.on(Event.FiltersMatch) { hits.add(it.height) }
            val errors = mutableListOf<String>()
            bus.on(Event.ModuleStatus) { p ->
                if (p.module == "filters-matching" && p.status == ModuleStatus.ERROR) {
                    errors.add(p.detail.orEmpty())
                }
            }

            val mod =
                createFiltersMatchingModule(
                    ModuleContext(bus, db),
                    FiltersMatchingOptions(wallet = wallet, batchGapMs = 0, yieldFn = {}),
                )
            mod.start()
            waitFor { hits.contains(800) }
            assertTrue(errors.any { it.contains("scan boom") })
            mod.stop()
            db.close()
        }

    @Test
    fun stop_during_idle_does_not_wait_out_the_poll() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 4))
            val junk = byteArrayOf(0x00, 0x14) + ByteArray(20) { 0xab.toByte() }
            appendFilter(db, 1, "01".repeat(32), listOf(junk))
            db.filters.markScanned(listOf(1))

            val running = CompletableDeferred<Unit>()
            bus.on(Event.ModuleStatus) { p ->
                if (p.module == "filters-matching" && p.status == ModuleStatus.RUNNING) {
                    running.complete(Unit)
                }
            }
            val mod =
                createFiltersMatchingModule(
                    ModuleContext(bus, db),
                    FiltersMatchingOptions(wallet = wallet, batchGapMs = 0, yieldFn = {}),
                )
            mod.start()
            running.await()
            delay(30)
            val startedAt =
                kotlin.time.TimeSource.Monotonic
                    .markNow()
            mod.stop()
            val elapsedMs = startedAt.elapsedNow().inWholeMilliseconds
            assertTrue(elapsedMs < 400, "stop() took ${elapsedMs}ms")
            db.close()
        }

    @Test
    fun uses_kv_backed_wallet_known_first_address() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            saveWalletSecret(db, ABANDON_MNEMONIC)
            val bus = createMessageBus()
            val wallet = createWallet(db)
            assertEquals(BLUE_EXTERNAL_0, wallet.snapshot().addresses[0].address)

            val mod =
                createFiltersMatchingModule(
                    ModuleContext(bus, db),
                    FiltersMatchingOptions(wallet = wallet, batchGapMs = 0),
                )
            mod.start()
            assertEquals(INITIAL_WATCH_COUNT * 2, wallet.scripts().size)
            mod.stop()
            db.close()
        }
}
