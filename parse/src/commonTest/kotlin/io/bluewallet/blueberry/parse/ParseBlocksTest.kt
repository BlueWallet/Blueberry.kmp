package io.bluewallet.blueberry.parse

import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.ModuleStatus
import io.bluewallet.blueberry.bus.SyncCatchupPayload
import io.bluewallet.blueberry.bus.SyncCatchupReason
import io.bluewallet.blueberry.bus.SyncIdlePayload
import io.bluewallet.blueberry.bus.createMessageBus
import io.bluewallet.blueberry.parse.modules.ParseBlocksOptions
import io.bluewallet.blueberry.parse.modules.createParseBlocksModule
import io.bluewallet.blueberry.peers.modules.ModuleContext
import io.bluewallet.blueberry.storage.DownloadedBlock
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.CreateWalletOptions
import io.bluewallet.blueberry.wallet.GAP_LIMIT
import io.bluewallet.blueberry.wallet.HdWatchGaps
import io.bluewallet.blueberry.wallet.WatchGaps
import io.bluewallet.blueberry.wallet.createWallet
import io.bluewallet.blueberry.wallet.saveHdWatchGaps
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun blockBytesWithReceive(
    script: ByteArray,
    value: Long,
): ByteArray = wrapBlock(coinbaseLikeReceive(script, value))

class ParseBlocksTest {
    @Test
    fun parses_backlog_after_sync_idle_and_emits_wallet_txs() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val script = p2wpkhScript()
            db.blocks.insert(DownloadedBlock(50, "ab".repeat(32), blockBytesWithReceive(script, 5000)))

            val events = mutableListOf<Long>()
            bus.on(Event.WalletTxs) { events.add(it.at) }
            val logs = StringBuilder()

            val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 4))
            val mod =
                createParseBlocksModule(
                    ModuleContext(bus, db),
                    ParseBlocksOptions(
                        wallet = wallet,
                        idleDelayMs = 50,
                        blockGapMs = 0,
                        log = { logs.appendLine("[parse-blocks] $it") },
                        logError = { msg, err ->
                            logs.appendLine("[parse-blocks] $msg${err?.let { ": ${it.message}" } ?: ""}")
                        },
                    ),
                )
            mod.start()
            bus.emit(Event.SyncIdle, SyncIdlePayload(at = 1))
            waitFor { db.parsedBlocks.has(50) && db.transactions.count() == 1 }
            assertTrue(events.isNotEmpty())
            assertEquals(5000, db.transactions.list()[0].netDeltaSats)

            mod.stop()
            val text = logs.toString()
            assertTrue(text.contains("[parse-blocks] start"))
            assertTrue(text.contains("[parse-blocks] allowed"))
            assertTrue(text.contains("[parse-blocks] batch n=1 from=50 to=50"))
            assertTrue(text.contains("[parse-blocks] stop"))

            val before = events.size
            val wallet2 = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 4))
            val mod2 =
                createParseBlocksModule(
                    ModuleContext(bus, db),
                    ParseBlocksOptions(wallet = wallet2, idleDelayMs = 50, blockGapMs = 0),
                )
            mod2.start()
            bus.emit(Event.SyncIdle, SyncIdlePayload(at = 2))
            waitFor { events.size > before }
            assertEquals(1, db.transactions.count())
            mod2.stop()
            db.close()
        }

    @Test
    fun blocks_progress_while_busy_sets_needsRun_without_overlapping_parses() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val script = p2wpkhScript()
            db.blocks.insert(DownloadedBlock(1, "11".repeat(32), blockBytesWithReceive(script, 1)))
            db.blocks.insert(DownloadedBlock(2, "22".repeat(32), blockBytesWithReceive(script, 2)))

            var batchDepth = 0
            var overlapped = false
            var parses = 0
            val wallet =
                createWallet(
                    db,
                    CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = GAP_LIMIT + 1),
                )
            val mod =
                createParseBlocksModule(
                    ModuleContext(bus, db),
                    ParseBlocksOptions(
                        wallet = wallet,
                        idleDelayMs = 50,
                        batchSize = 1,
                        blockGapMs = 0,
                        onParseBatch = {
                            if (batchDepth > 0) overlapped = true
                            batchDepth++
                            parses++
                            if (parses == 2) {
                                db.blocks.insert(
                                    DownloadedBlock(3, "33".repeat(32), blockBytesWithReceive(script, 3)),
                                )
                                bus.emit(
                                    Event.BlocksProgress,
                                    io.bluewallet.blueberry.bus.BlocksProgressPayload(
                                        at = 1,
                                        downloaded = 3,
                                        matched = 3,
                                    ),
                                )
                            }
                            batchDepth--
                        },
                    ),
                )
            mod.start()
            bus.emit(Event.SyncIdle, SyncIdlePayload(at = 1))
            waitFor { db.parsedBlocks.has(1) && db.parsedBlocks.has(2) && db.parsedBlocks.has(3) }
            assertFalse(overlapped)
            assertEquals(3, db.transactions.count())
            mod.stop()
            db.close()
        }

    @Test
    fun decode_error_emits_module_status_and_keeps_parsing_subsequent_blocks() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val script = p2wpkhScript()
            db.blocks.insert(DownloadedBlock(10, "aa".repeat(32), byteArrayOf(0x00, 0x01, 0x02)))
            db.blocks.insert(DownloadedBlock(11, "bb".repeat(32), blockBytesWithReceive(script, 100)))

            val errors = mutableListOf<String?>()
            bus.on(Event.ModuleStatus) {
                if (it.module == "parse-blocks" && it.status == ModuleStatus.ERROR) {
                    errors.add(it.detail)
                }
            }
            val logs = StringBuilder()
            val mod =
                createParseBlocksModule(
                    ModuleContext(bus, db),
                    ParseBlocksOptions(
                        wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 4)),
                        idleDelayMs = 50,
                        blockGapMs = 0,
                        log = { logs.appendLine("[parse-blocks] $it") },
                        logError = { msg, err ->
                            logs.appendLine("[parse-blocks] $msg${err?.let { ": ${it.message}" } ?: ""}")
                        },
                    ),
                )
            mod.start()
            bus.emit(Event.SyncIdle, SyncIdlePayload(at = 1))
            waitFor { db.parsedBlocks.has(11) }
            assertFalse(db.parsedBlocks.has(10))
            assertTrue(errors.isNotEmpty())
            assertTrue(errors[0]!!.contains("height 10"))
            assertEquals(1, db.transactions.count())
            assertEquals(100, db.transactions.list()[0].netDeltaSats)
            mod.stop()
            assertTrue(logs.toString().contains("[parse-blocks] decode height=10"))
            db.close()
        }

    @Test
    fun does_not_parse_backlog_until_sync_idle() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val script = p2wpkhScript()
            db.blocks.insert(DownloadedBlock(50, "ab".repeat(32), blockBytesWithReceive(script, 5000)))

            var batches = 0
            val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 4))
            val mod =
                createParseBlocksModule(
                    ModuleContext(bus, db),
                    ParseBlocksOptions(
                        wallet = wallet,
                        idleDelayMs = 50,
                        blockGapMs = 0,
                        onParseBatch = { batches++ },
                    ),
                )
            mod.start()
            delay(80)
            assertEquals(0, batches)
            assertFalse(db.parsedBlocks.has(50))

            bus.emit(Event.SyncIdle, SyncIdlePayload(at = 1))
            waitFor { db.parsedBlocks.has(50) && db.transactions.count() == 1 }
            assertTrue(batches >= 1)
            mod.stop()
            db.close()
        }

    @Test
    fun sync_catchup_pauses_parsing_sync_idle_resumes() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val script = p2wpkhScript()
            for (h in 1..3) {
                db.blocks.insert(
                    DownloadedBlock(
                        h,
                        h.toString(16).padStart(2, '0').repeat(32),
                        blockBytesWithReceive(script, h.toLong()),
                    ),
                )
            }
            val wallet =
                createWallet(
                    db,
                    CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = GAP_LIMIT + 1),
                )
            saveHdWatchGaps(db, HdWatchGaps.uniform(WatchGaps(GAP_LIMIT + 1, GAP_LIMIT + 1)))
            var catchupEmitted = false
            bus.on(Event.WalletTxs) {
                if (catchupEmitted) return@on
                catchupEmitted = true
                bus.emit(Event.SyncCatchup, SyncCatchupPayload(at = 1, reason = SyncCatchupReason.BLOCKS))
            }
            val logs = StringBuilder()
            val mod =
                createParseBlocksModule(
                    ModuleContext(bus, db),
                    ParseBlocksOptions(
                        wallet = wallet,
                        idleDelayMs = 50,
                        batchSize = 1,
                        blockGapMs = 30,
                        log = { logs.appendLine("[parse-blocks] $it") },
                    ),
                )
            mod.start()
            bus.emit(Event.SyncIdle, SyncIdlePayload(at = 1))
            waitFor(10_000) { db.parsedBlocks.has(1) }
            delay(120)
            assertFalse(db.parsedBlocks.has(2))
            assertFalse(db.parsedBlocks.has(3))
            assertTrue(logs.toString().contains("[parse-blocks] paused"))

            bus.emit(Event.SyncIdle, SyncIdlePayload(at = 2))
            waitFor(10_000) {
                db.parsedBlocks.has(2) && db.parsedBlocks.has(3) && db.transactions.count() == 3
            }
            mod.stop()
            db.close()
        }

    @Test
    fun blocks_progress_after_idle_drain_parses_new_block() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val script = p2wpkhScript()
            var batches = 0
            val mod =
                createParseBlocksModule(
                    ModuleContext(bus, db),
                    ParseBlocksOptions(
                        wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 4)),
                        idleDelayMs = 50,
                        blockGapMs = 0,
                        onParseBatch = { batches++ },
                    ),
                )
            mod.start()
            bus.emit(Event.SyncIdle, SyncIdlePayload(at = 1))
            waitFor { batches >= 1 }
            delay(40)
            db.blocks.insert(DownloadedBlock(9, "99".repeat(32), blockBytesWithReceive(script, 9)))
            bus.emit(
                Event.BlocksProgress,
                io.bluewallet.blueberry.bus
                    .BlocksProgressPayload(at = 2, downloaded = 1, matched = 1),
            )
            waitFor { db.parsedBlocks.has(9) && db.transactions.count() == 1 }
            mod.stop()
            db.close()
        }

    @Test
    fun does_not_poll_parse_while_idle_with_empty_backlog() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            var batches = 0
            val mod =
                createParseBlocksModule(
                    ModuleContext(bus, db),
                    ParseBlocksOptions(
                        wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 4)),
                        idleDelayMs = 50,
                        blockGapMs = 0,
                        onParseBatch = { batches++ },
                    ),
                )
            mod.start()
            bus.emit(Event.SyncIdle, SyncIdlePayload(at = 1))
            waitFor { batches >= 1 }
            delay(180)
            assertEquals(1, batches)
            mod.stop()
            db.close()
        }

    @Test
    fun drains_a_multi_batch_backlog_without_waiting_idleDelayMs() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val script = p2wpkhScript()
            for (h in 1..3) {
                db.blocks.insert(
                    DownloadedBlock(
                        h,
                        h.toString(16).padStart(2, '0').repeat(32),
                        blockBytesWithReceive(script, h.toLong()),
                    ),
                )
            }
            val started =
                io.bluewallet.blueberry.headers
                    .nowMillis()
            val mod =
                createParseBlocksModule(
                    ModuleContext(bus, db),
                    ParseBlocksOptions(
                        wallet =
                            createWallet(
                                db,
                                CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = GAP_LIMIT + 1),
                            ),
                        idleDelayMs = 5_000,
                        batchSize = 1,
                        blockGapMs = 0,
                    ),
                )
            mod.start()
            bus.emit(Event.SyncIdle, SyncIdlePayload(at = 1))
            waitFor { db.parsedBlocks.has(1) && db.parsedBlocks.has(2) && db.parsedBlocks.has(3) }
            assertTrue(
                io.bluewallet.blueberry.headers
                    .nowMillis() - started < 2_000,
            )
            mod.stop()
            db.close()
        }

    @Test
    fun decode_error_does_not_block_later_heights_when_batchSize_is_1() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val script = p2wpkhScript()
            db.blocks.insert(DownloadedBlock(10, "aa".repeat(32), byteArrayOf(0x00, 0x01, 0x02)))
            db.blocks.insert(DownloadedBlock(11, "bb".repeat(32), blockBytesWithReceive(script, 100)))
            val mod =
                createParseBlocksModule(
                    ModuleContext(bus, db),
                    ParseBlocksOptions(
                        wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 4)),
                        idleDelayMs = 50,
                        batchSize = 1,
                        blockGapMs = 0,
                    ),
                )
            mod.start()
            bus.emit(Event.SyncIdle, SyncIdlePayload(at = 1))
            waitFor { db.parsedBlocks.has(11) && db.transactions.count() == 1 }
            assertFalse(db.parsedBlocks.has(10))
            mod.stop()
            db.close()
        }

    @Test
    fun unexpected_parseBatch_error_retries_after_backoff() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val script = p2wpkhScript()
            db.blocks.insert(DownloadedBlock(7, "77".repeat(32), blockBytesWithReceive(script, 7)))
            var batches = 0
            val logs = StringBuilder()
            val mod =
                createParseBlocksModule(
                    ModuleContext(bus, db),
                    ParseBlocksOptions(
                        wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 4)),
                        idleDelayMs = 50,
                        blockGapMs = 0,
                        logError = { msg, err ->
                            logs.appendLine("[parse-blocks] $msg${err?.let { ": ${it.message}" } ?: ""}")
                        },
                        onParseBatch = {
                            batches++
                            if (batches == 1) error("boom")
                        },
                    ),
                )
            mod.start()
            bus.emit(Event.SyncIdle, SyncIdlePayload(at = 1))
            waitFor { db.parsedBlocks.has(7) && db.transactions.count() == 1 }
            mod.stop()
            assertTrue(logs.toString().contains("[parse-blocks] batch: boom"))
            db.close()
        }

    @Test
    fun catchup_during_a_block_gap_pauses_the_next_block_until_sync_idle() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val script = p2wpkhScript()
            for (h in 1..2) {
                db.blocks.insert(
                    DownloadedBlock(
                        h,
                        h.toString(16).padStart(2, '0').repeat(32),
                        blockBytesWithReceive(script, h.toLong()),
                    ),
                )
            }
            var catchupScheduled = false
            bus.on(Event.WalletTxs) {
                if (catchupScheduled) return@on
                catchupScheduled = true
                // Original uses setTimeout 20ms so catchup lands in the block gap.
            }
            val wallet =
                createWallet(
                    db,
                    CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = GAP_LIMIT + 1),
                )
            saveHdWatchGaps(db, HdWatchGaps.uniform(WatchGaps(GAP_LIMIT + 1, GAP_LIMIT + 1)))
            val mod =
                createParseBlocksModule(
                    ModuleContext(bus, db),
                    ParseBlocksOptions(
                        wallet = wallet,
                        idleDelayMs = 50,
                        batchSize = 2,
                        blockGapMs = 100,
                    ),
                )
            bus.on(Event.WalletTxs) {
                if (db.parsedBlocks.has(1) && !db.parsedBlocks.has(2)) {
                    bus.emit(Event.SyncCatchup, SyncCatchupPayload(at = 2, reason = SyncCatchupReason.BLOCKS))
                }
            }
            mod.start()
            bus.emit(Event.SyncIdle, SyncIdlePayload(at = 1))
            waitFor { db.parsedBlocks.has(1) }
            delay(150)
            assertFalse(db.parsedBlocks.has(2))
            bus.emit(Event.SyncIdle, SyncIdlePayload(at = 3))
            waitFor { db.parsedBlocks.has(2) }
            mod.stop()
            db.close()
        }
}
