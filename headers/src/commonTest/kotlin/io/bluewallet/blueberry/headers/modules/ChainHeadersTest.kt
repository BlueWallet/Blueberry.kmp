package io.bluewallet.blueberry.headers.modules

import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.PeersUpdatedPayload
import io.bluewallet.blueberry.bus.SyncIdlePayload
import io.bluewallet.blueberry.bus.createMessageBus
import io.bluewallet.blueberry.headers.CHECKPOINT_HEIGHT
import io.bluewallet.blueberry.headers.buildReorgFixture
import io.bluewallet.blueberry.headers.checkpointDbRecord
import io.bluewallet.blueberry.headers.mineEasyChain
import io.bluewallet.blueberry.headers.persistRecords
import io.bluewallet.blueberry.headers.stubPlatformNet
import io.bluewallet.blueberry.headers.upsertPeer
import io.bluewallet.blueberry.headers.waitFor
import io.bluewallet.blueberry.peers.modules.ModuleContext
import io.bluewallet.blueberry.peers.net.HeaderBatchResult
import io.bluewallet.blueberry.peers.net.HeaderFetchOptions
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.DownloadedBlock
import io.bluewallet.blueberry.storage.HeaderWrite
import io.bluewallet.blueberry.storage.HeadersRepository
import io.bluewallet.blueberry.storage.MatchedBlock
import io.bluewallet.blueberry.storage.PeerWrite
import io.bluewallet.blueberry.storage.StoredTx
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.WalletBirthdayInspection
import io.bluewallet.blueberry.wallet.inspectWalletBirthday
import io.bluewallet.blueberry.wallet.markWalletBirthdayPending
import io.bluewallet.headers.bytesToHex
import io.bluewallet.headers.decodeBlockHeader
import io.bluewallet.headers.headerHashInternal
import io.bluewallet.headers.hexToBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val NEXT_HEADER_HEX =
    "000000208fdfeffd2c3a3a235a847805dbd1dc5adb9cd48519532a000000000000000000105b6f8cba2f1258ea4c1e41f72e843c770c3acfede6f02df3108c6fba7b88bfca4f2a5ca5183217d6a930c9"

class ChainHeadersTest {
    @Test
    fun waits_for_peers_appends_mainnet_header_emits_progress() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val events = mutableListOf<Pair<Int, Int>>()
            bus.on(Event.HeadersProgress) { events.add(it.downloaded to it.total) }
            val nextHeader = decodeBlockHeader(hexToBytes(NEXT_HEADER_HEX))
            var calls = 0
            val mod =
                createChainHeadersModule(
                    ModuleContext(bus, db),
                    ChainHeadersOptions(
                        net = stubPlatformNet(),
                        connectTimeoutMs = 200,
                        headersTimeoutMs = 200,
                        pollIntervalMs = 50,
                        fetchBatch = { _, _, _ ->
                            calls++
                            if (calls == 1) {
                                HeaderBatchResult.Ok(CHECKPOINT_HEIGHT + 100, listOf(nextHeader))
                            } else {
                                HeaderBatchResult.Ok(CHECKPOINT_HEIGHT + 100, emptyList())
                            }
                        },
                    ),
                )
            mod.start()
            assertEquals(1, db.headers.count())
            delay(80)
            assertEquals(0, calls)
            db.peers.upsert(PeerWrite("1.1.1.1", 8333, 0uL, true, false, null))
            bus.emit(Event.PeersUpdated, PeersUpdatedPayload(1))
            waitFor {
                db.headers.tip()?.height == CHECKPOINT_HEIGHT + 1 &&
                    events.any { it.first == 1 && it.second == 100 }
            }
            assertEquals(bytesToHex(headerHashInternal(nextHeader)), db.headers.tip()!!.hashInternalHex)
            waitFor {
                val last = events.lastOrNull()
                last?.first == 1 && last.second == 1
            }
            assertEquals(1 to 1, events.last())
            mod.stop()
            db.close()
        }

    @Test
    fun persist_lock_retries_and_still_appends() =
        runBlocking {
            val bus = createMessageBus()
            val inner = createSqliteDatabase(":memory:")
            var failNext = true
            val db =
                object : Database by inner {
                    override val headers =
                        object : HeadersRepository by inner.headers {
                            override fun append(headers: List<HeaderWrite>) {
                                if (failNext) {
                                    failNext = false
                                    error("database is locked")
                                }
                                inner.headers.append(headers)
                            }
                        }
                }
            val nextHeader = decodeBlockHeader(hexToBytes(NEXT_HEADER_HEX))
            val mod =
                createChainHeadersModule(
                    ModuleContext(bus, db),
                    ChainHeadersOptions(
                        net = stubPlatformNet(),
                        connectTimeoutMs = 200,
                        headersTimeoutMs = 200,
                        pollIntervalMs = 50,
                        fetchBatch = { _, _, _ ->
                            HeaderBatchResult.Ok(CHECKPOINT_HEIGHT + 100, listOf(nextHeader))
                        },
                    ),
                )
            db.peers.upsert(PeerWrite("1.1.1.1", 8333, 0uL, true, false, null))
            mod.start()
            waitFor {
                db.headers.tip()?.height == CHECKPOINT_HEIGHT + 1 && !failNext
            }
            assertEquals(CHECKPOINT_HEIGHT + 1, db.headers.tip()!!.height)
            mod.stop()
            db.close()
        }

    @Test
    fun hard_fetch_failure_marks_peers_not_alive() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            for (host in listOf("1.1.1.1", "2.2.2.2")) upsertPeer(db, host)
            val tried = mutableListOf<String>()
            var peerUpdates = 0
            bus.on(Event.PeersUpdated) { peerUpdates++ }
            val mod =
                createChainHeadersModule(
                    ModuleContext(bus, db),
                    ChainHeadersOptions(
                        net = stubPlatformNet(),
                        racePeers = 1,
                        connectTimeoutMs = 100,
                        headersTimeoutMs = 100,
                        pollIntervalMs = 10_000,
                        fetchBatch = { host, _, _ ->
                            tried.add(host)
                            HeaderBatchResult.Err("dead")
                        },
                    ),
                )
            mod.start()
            waitFor { tried.size >= 2 && peerUpdates >= 2 }
            assertEquals(setOf("1.1.1.1", "2.2.2.2"), tried.toSet())
            assertEquals(0, db.peers.listAlive().size)
            mod.stop()
            db.close()
        }

    @Test
    fun unlinkable_batch_marks_peer_dead() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            db.headers.ensureCheckpoint(checkpointDbRecord())
            upsertPeer(db, "3.3.3.3")
            val linked = decodeBlockHeader(hexToBytes(NEXT_HEADER_HEX))
            val orphan = linked.copy(previousBlockHash = ByteArray(32) { 0xab.toByte() })
            val mod =
                createChainHeadersModule(
                    ModuleContext(bus, db),
                    ChainHeadersOptions(
                        net = stubPlatformNet(),
                        pollIntervalMs = 10_000,
                        fetchBatch = { _, _, _ ->
                            HeaderBatchResult.Ok(CHECKPOINT_HEIGHT + 1, listOf(orphan))
                        },
                    ),
                )
            mod.start()
            waitFor { db.peers.listAlive().isEmpty() }
            mod.stop()
            db.close()
        }

    @Test
    fun races_peers_and_takes_the_first_ok_batch() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            upsertPeer(db, "slow.peer")
            upsertPeer(db, "fast.peer")
            val nextHeader = decodeBlockHeader(hexToBytes(NEXT_HEADER_HEX))
            val started = mutableSetOf<String>()
            val startedLock = Mutex()
            var winner: String? = null
            var batches = 0
            val mod =
                createChainHeadersModule(
                    ModuleContext(bus, db),
                    ChainHeadersOptions(
                        net = stubPlatformNet(),
                        racePeers = 2,
                        connectTimeoutMs = 500,
                        headersTimeoutMs = 500,
                        pollIntervalMs = 10_000,
                        fetchBatch = { host, _, _ ->
                            startedLock.withLock { started.add(host) }
                            if (batches > 0) {
                                return@ChainHeadersOptions HeaderBatchResult.Ok(CHECKPOINT_HEIGHT + 100, emptyList())
                            }
                            if (host == "slow.peer") {
                                delay(80)
                                HeaderBatchResult.Ok(CHECKPOINT_HEIGHT + 100, listOf(nextHeader))
                            } else {
                                delay(5)
                                winner = host
                                batches++
                                HeaderBatchResult.Ok(CHECKPOINT_HEIGHT + 100, listOf(nextHeader))
                            }
                        },
                    ),
                )
            mod.start()
            waitFor {
                db.headers.tip()?.height == CHECKPOINT_HEIGHT + 1 &&
                    started.contains("fast.peer") &&
                    started.contains("slow.peer")
            }
            assertEquals(setOf("fast.peer", "slow.peer"), started.toSet())
            assertEquals("fast.peer", winner)
            mod.stop()
            db.close()
        }

    @Test
    fun first_non_empty_wins_without_waiting_for_slower_peers() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            upsertPeer(db, "fast.peer")
            upsertPeer(db, "hung.peer")
            val nextHeader = decodeBlockHeader(hexToBytes(NEXT_HEADER_HEX))
            val hung = kotlinx.coroutines.CompletableDeferred<Unit>()
            val mod =
                createChainHeadersModule(
                    ModuleContext(bus, db),
                    ChainHeadersOptions(
                        net = stubPlatformNet(),
                        racePeers = 2,
                        connectTimeoutMs = 500,
                        headersTimeoutMs = 500,
                        pollIntervalMs = 10_000,
                        fetchBatch = { host, _, _ ->
                            if (host == "hung.peer") {
                                hung.await()
                                HeaderBatchResult.Ok(CHECKPOINT_HEIGHT + 100, emptyList())
                            } else {
                                HeaderBatchResult.Ok(CHECKPOINT_HEIGHT + 100, listOf(nextHeader))
                            }
                        },
                    ),
                )
            mod.start()
            waitFor { db.headers.tip()?.height == CHECKPOINT_HEIGHT + 1 }
            assertEquals(CHECKPOINT_HEIGHT + 1, db.headers.tip()!!.height)
            hung.complete(Unit)
            mod.stop()
            db.close()
        }

    @Test
    fun reorgs_to_a_heavier_fork() =
        runBlocking {
            val fixture = buildReorgFixture()
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            persistRecords(db, fixture.canonical)
            val oldTip = fixture.canonical.last()
            db.matchedBlocks.insert(MatchedBlock(oldTip.height.toInt(), oldTip.hashInternalHex))
            db.blocks.insert(
                DownloadedBlock(oldTip.height.toInt(), oldTip.hashInternalHex, byteArrayOf(1, 2, 3)),
            )
            db.transactions.upsert(
                StoredTx("cd".repeat(32), oldTip.height.toInt(), 0, oldTip.hashInternalHex, byteArrayOf(4), 1),
            )
            val events = mutableListOf<Pair<Int, Int>>()
            bus.on(Event.HeadersProgress) { events.add(it.downloaded to it.total) }
            val wakes = mutableListOf<String>()
            bus.on(Event.WalletTxs) { wakes.add("wallet:txs") }
            bus.on(Event.BlocksProgress) { wakes.add("blocks:progress") }
            var calls = 0
            val mod =
                createChainHeadersModule(
                    ModuleContext(bus, db),
                    ChainHeadersOptions(
                        net = stubPlatformNet(),
                        consensus = fixture.params,
                        connectTimeoutMs = 200,
                        headersTimeoutMs = 200,
                        pollIntervalMs = 50,
                        nowSeconds = { 10_000 },
                        fetchBatch = { _, _, _ ->
                            calls++
                            if (calls == 1) {
                                HeaderBatchResult.Ok(10, fixture.heavierFork)
                            } else {
                                HeaderBatchResult.Ok(10, emptyList())
                            }
                        },
                    ),
                )
            upsertPeer(db, "1.1.1.1")
            mod.start()
            waitFor {
                db.headers.tip()?.height == 4 && events.lastOrNull() == 4 to 4
            }
            assertEquals(
                bytesToHex(headerHashInternal(fixture.heavierFork[2])),
                db.headers.tip()!!.hashInternalHex,
            )
            assertEquals(5, db.headers.count())
            assertEquals(4 to 4, events.last())
            assertEquals(0, db.matchedBlocks.count())
            assertFalse(db.blocks.has(oldTip.height.toInt()))
            assertEquals(0, db.transactions.list().size)
            assertTrue(wakes.contains("wallet:txs"))
            assertTrue(wakes.contains("blocks:progress"))
            mod.stop()
            db.close()
        }

    @Test
    fun stale_startHeight_does_not_report_downloaded_over_total() =
        runBlocking {
            val fixture = buildReorgFixture()
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            persistRecords(db, listOf(fixture.canonical[0]))
            val events = mutableListOf<Pair<Int, Int>>()
            bus.on(Event.HeadersProgress) { events.add(it.downloaded to it.total) }
            val beyondTip = fixture.canonical.drop(1).map { decodeBlockHeader(hexToBytes(it.headerHex)) }
            var calls = 0
            val mod =
                createChainHeadersModule(
                    ModuleContext(bus, db),
                    ChainHeadersOptions(
                        net = stubPlatformNet(),
                        consensus = fixture.params,
                        connectTimeoutMs = 200,
                        headersTimeoutMs = 200,
                        pollIntervalMs = 50,
                        nowSeconds = { 10_000 },
                        fetchBatch = { _, _, _ ->
                            calls++
                            when (calls) {
                                1 -> HeaderBatchResult.Ok(1, beyondTip.take(1))
                                2 -> HeaderBatchResult.Ok(1, beyondTip.drop(1))
                                else -> HeaderBatchResult.Ok(1, emptyList())
                            }
                        },
                    ),
                )
            upsertPeer(db, "1.1.1.1")
            mod.start()
            waitFor { db.headers.tip()?.height == 3 && events.lastOrNull() == 3 to 3 }
            mod.stop()
            for (ev in events) assertTrue(ev.first <= ev.second)
            assertEquals(3 to 3, events.last())
            db.close()
        }

    @Test
    fun inflated_startHeight_after_tip_does_not_drop_progress() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val events = mutableListOf<Pair<Int, Int>>()
            bus.on(Event.HeadersProgress) { events.add(it.downloaded to it.total) }
            val nextHeader = decodeBlockHeader(hexToBytes(NEXT_HEADER_HEX))
            var calls = 0
            val mod =
                createChainHeadersModule(
                    ModuleContext(bus, db),
                    ChainHeadersOptions(
                        net = stubPlatformNet(),
                        connectTimeoutMs = 200,
                        headersTimeoutMs = 200,
                        pollIntervalMs = 50,
                        fetchBatch = { _, _, _ ->
                            calls++
                            HeaderBatchResult.Ok(CHECKPOINT_HEIGHT + 100, listOf(nextHeader))
                        },
                    ),
                )
            upsertPeer(db, "1.1.1.1")
            mod.start()
            waitFor { db.headers.tip()?.height == CHECKPOINT_HEIGHT + 1 }
            delay(150)
            assertTrue(calls >= 2)
            assertEquals(1 to 1, events.last())
            mod.stop()
            db.close()
        }

    @Test
    fun backs_off_when_every_raced_peer_fails_instantly() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            upsertPeer(db, "1.1.1.1")
            val callAt = mutableListOf<Long>()
            val mod =
                createChainHeadersModule(
                    ModuleContext(bus, db),
                    ChainHeadersOptions(
                        net = stubPlatformNet(),
                        racePeers = 1,
                        connectTimeoutMs = 50,
                        headersTimeoutMs = 50,
                        pollIntervalMs = 10_000,
                        fetchBatch = { _, _, _ ->
                            callAt.add(
                                io.bluewallet.blueberry.headers
                                    .nowMillis(),
                            )
                            HeaderBatchResult.Err("session busy")
                        },
                    ),
                )
            mod.start()
            waitFor(3000) { callAt.size >= 3 }
            mod.stop()
            assertTrue(callAt[1] - callAt[0] >= 80)
            assertTrue(callAt[2] - callAt[1] >= 80)
            db.close()
        }

    @Test
    fun backs_off_between_empty_header_batches() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            upsertPeer(db, "1.1.1.1")
            val callAt = mutableListOf<Long>()
            val mod =
                createChainHeadersModule(
                    ModuleContext(bus, db),
                    ChainHeadersOptions(
                        net = stubPlatformNet(),
                        connectTimeoutMs = 100,
                        headersTimeoutMs = 100,
                        pollIntervalMs = 80,
                        fetchBatch = { _, _, _ ->
                            callAt.add(
                                io.bluewallet.blueberry.headers
                                    .nowMillis(),
                            )
                            HeaderBatchResult.Ok(CHECKPOINT_HEIGHT + 100, emptyList())
                        },
                    ),
                )
            mod.start()
            waitFor { callAt.size >= 3 }
            mod.stop()
            assertTrue(callAt[1] - callAt[0] >= 70)
            assertTrue(callAt[2] - callAt[1] >= 70)
            db.close()
        }

    @Test
    fun empty_race_times_out_a_hung_peer_and_keeps_polling() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            upsertPeer(db, "fast.peer")
            upsertPeer(db, "hung.peer")
            val tried = mutableListOf<String>()
            val mod =
                createChainHeadersModule(
                    ModuleContext(bus, db),
                    ChainHeadersOptions(
                        net = stubPlatformNet(),
                        racePeers = 2,
                        connectTimeoutMs = 80,
                        headersTimeoutMs = 80,
                        pollIntervalMs = 40,
                        fetchBatch = { host, _, _ ->
                            tried.add(host)
                            if (host == "hung.peer") {
                                kotlinx.coroutines.CompletableDeferred<Unit>().await()
                            }
                            HeaderBatchResult.Ok(CHECKPOINT_HEIGHT + 1, emptyList())
                        },
                    ),
                )
            mod.start()
            waitFor(2000) { tried.count { it == "fast.peer" } >= 2 }
            mod.stop()
            db.close()
        }

    @Test
    fun idle_ignores_peers_updated_unless_waiting() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            db.headers.ensureCheckpoint(checkpointDbRecord())
            upsertPeer(db, "1.1.1.1")
            var calls = 0
            val mod =
                createChainHeadersModule(
                    ModuleContext(bus, db),
                    ChainHeadersOptions(
                        net = stubPlatformNet(),
                        pollIntervalMs = 10_000,
                        fetchBatch = { _, _, _ ->
                            calls++
                            HeaderBatchResult.Ok(db.headers.tip()!!.height, emptyList())
                        },
                    ),
                )
            mod.start()
            bus.emit(Event.PeersUpdated, PeersUpdatedPayload(1))
            waitFor { calls >= 1 }
            bus.emit(Event.SyncIdle, SyncIdlePayload(1))
            val atIdle = calls
            bus.emit(Event.PeersUpdated, PeersUpdatedPayload(2))
            delay(50)
            assertEquals(atIdle, calls)
            mod.stop()
            db.close()
        }

    @Test
    fun idle_with_no_peers_resumes_on_peers_updated() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            db.headers.ensureCheckpoint(checkpointDbRecord())
            var calls = 0
            val mod =
                createChainHeadersModule(
                    ModuleContext(bus, db),
                    ChainHeadersOptions(
                        net = stubPlatformNet(),
                        pollIntervalMs = 10_000,
                        fetchBatch = { _, _, _ ->
                            calls++
                            HeaderBatchResult.Ok(db.headers.tip()!!.height, emptyList())
                        },
                    ),
                )
            mod.start()
            delay(30)
            bus.emit(Event.SyncIdle, SyncIdlePayload(1))
            assertEquals(0, calls)
            upsertPeer(db, "1.1.1.1")
            bus.emit(Event.PeersUpdated, PeersUpdatedPayload(2))
            waitFor { calls >= 1 }
            mod.stop()
            db.close()
        }

    @Test
    fun does_not_freeze_birthday_while_behind_peer_tip() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            markWalletBirthdayPending(db)
            upsertPeer(db, "1.1.1.1")
            val nextHeader = decodeBlockHeader(hexToBytes(NEXT_HEADER_HEX))
            val mod =
                createChainHeadersModule(
                    ModuleContext(bus, db),
                    ChainHeadersOptions(
                        net = stubPlatformNet(),
                        pollIntervalMs = 10_000,
                        fetchBatch = { _, _, _ ->
                            HeaderBatchResult.Ok(CHECKPOINT_HEIGHT + 100, listOf(nextHeader))
                        },
                    ),
                )
            mod.start()
            bus.emit(Event.PeersUpdated, PeersUpdatedPayload(1))
            waitFor { db.headers.tip()?.height == CHECKPOINT_HEIGHT + 1 }
            delay(40)
            assertEquals(WalletBirthdayInspection.Pending, inspectWalletBirthday(db))
            mod.stop()
            db.close()
        }

    @Test
    fun freezes_birthday_once_caught_up() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            markWalletBirthdayPending(db)
            upsertPeer(db, "1.1.1.1")
            val nextHeader = decodeBlockHeader(hexToBytes(NEXT_HEADER_HEX))
            val mod =
                createChainHeadersModule(
                    ModuleContext(bus, db),
                    ChainHeadersOptions(
                        net = stubPlatformNet(),
                        pollIntervalMs = 10_000,
                        fetchBatch = { _, _, _ ->
                            HeaderBatchResult.Ok(CHECKPOINT_HEIGHT + 1, listOf(nextHeader))
                        },
                    ),
                )
            mod.start()
            bus.emit(Event.PeersUpdated, PeersUpdatedPayload(1))
            waitFor { inspectWalletBirthday(db) is WalletBirthdayInspection.Ok }
            assertEquals(WalletBirthdayInspection.Ok(CHECKPOINT_HEIGHT + 1), inspectWalletBirthday(db))
            mod.stop()
            db.close()
        }

    @Test
    fun empty_batch_does_not_win_against_in_flight_non_empty() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            upsertPeer(db, "empty.peer")
            upsertPeer(db, "slow.peer")
            val nextHeader = decodeBlockHeader(hexToBytes(NEXT_HEADER_HEX))
            val mod =
                createChainHeadersModule(
                    ModuleContext(bus, db),
                    ChainHeadersOptions(
                        net = stubPlatformNet(),
                        racePeers = 2,
                        connectTimeoutMs = 200,
                        headersTimeoutMs = 200,
                        pollIntervalMs = 10_000,
                        fetchBatch = { host, _, _ ->
                            if (host == "empty.peer") {
                                HeaderBatchResult.Ok(CHECKPOINT_HEIGHT + 100, emptyList())
                            } else {
                                delay(40)
                                HeaderBatchResult.Ok(CHECKPOINT_HEIGHT + 100, listOf(nextHeader))
                            }
                        },
                    ),
                )
            mod.start()
            waitFor { db.headers.tip()?.height == CHECKPOINT_HEIGHT + 1 }
            mod.stop()
            db.close()
        }

    @Test
    fun locator_is_sampled_and_ends_at_checkpoint() =
        runBlocking {
            val (params, records) = mineEasyChain(25)
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            persistRecords(db, records)
            upsertPeer(db, "1.1.1.1")
            var locator: List<ByteArray>? = null
            val mod =
                createChainHeadersModule(
                    ModuleContext(bus, db),
                    ChainHeadersOptions(
                        net = stubPlatformNet(),
                        consensus = params,
                        connectTimeoutMs = 200,
                        headersTimeoutMs = 200,
                        pollIntervalMs = 10_000,
                        nowSeconds = { 10_000 },
                        fetchBatch = { _, _, opts: HeaderFetchOptions ->
                            if (locator == null) locator = opts.locatorHashes
                            HeaderBatchResult.Ok(24, emptyList())
                        },
                    ),
                )
            mod.start()
            waitFor { locator != null }
            mod.stop()
            val heights = locator!!.map { db.headers.heightForHashInternal(bytesToHex(it)) }
            assertEquals(listOf(24, 23, 22, 21, 20, 19, 18, 17, 16, 15, 14), heights.take(11))
            assertTrue(heights.contains(11))
            assertEquals(0, heights.last())
            db.close()
        }

    @Test
    fun fetchBatch_rejection_does_not_hang() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            upsertPeer(db, "1.1.1.1")
            val mod =
                createChainHeadersModule(
                    ModuleContext(bus, db),
                    ChainHeadersOptions(
                        net = stubPlatformNet(),
                        racePeers = 1,
                        connectTimeoutMs = 100,
                        headersTimeoutMs = 100,
                        pollIntervalMs = 10_000,
                        fetchBatch = { _, _, _ -> error("boom") },
                    ),
                )
            mod.start()
            waitFor { db.peers.listAlive().isEmpty() }
            mod.stop()
            db.close()
        }

    @Test
    fun stop_waits_for_in_flight_loop() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            upsertPeer(db, "1.1.1.1")
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            var fetches = 0
            val mod =
                createChainHeadersModule(
                    ModuleContext(bus, db),
                    ChainHeadersOptions(
                        net = stubPlatformNet(),
                        pollIntervalMs = 10_000,
                        fetchBatch = { _, _, _ ->
                            fetches++
                            gate.await()
                            HeaderBatchResult.Ok(CHECKPOINT_HEIGHT + 1, emptyList())
                        },
                    ),
                )
            mod.start()
            waitFor { fetches == 1 }
            var stopFinished = false
            val stopJob =
                CoroutineScope(Dispatchers.Default).async {
                    mod.stop()
                    stopFinished = true
                }
            delay(40)
            assertFalse(stopFinished)
            assertEquals(1, fetches)
            gate.complete(Unit)
            stopJob.await()
            assertTrue(stopFinished)
            db.close()
        }

    @Test
    fun does_not_replace_with_weaker_fork() =
        runBlocking {
            val fixture = buildReorgFixture()
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            persistRecords(db, fixture.canonical)
            upsertPeer(db, "1.1.1.1")
            val oldTip = fixture.canonical.last()
            val mod =
                createChainHeadersModule(
                    ModuleContext(bus, db),
                    ChainHeadersOptions(
                        net = stubPlatformNet(),
                        consensus = fixture.params,
                        connectTimeoutMs = 200,
                        headersTimeoutMs = 200,
                        pollIntervalMs = 50,
                        nowSeconds = { 10_000 },
                        fetchBatch = { _, _, _ -> HeaderBatchResult.Ok(10, fixture.weakerFork) },
                    ),
                )
            mod.start()
            delay(150)
            assertEquals(oldTip.height.toInt(), db.headers.tip()!!.height)
            assertEquals(oldTip.hashInternalHex, db.headers.tip()!!.hashInternalHex)
            mod.stop()
            db.close()
        }

    @Test
    fun skips_weaker_fork_peer_and_applies_extension() =
        runBlocking {
            val fixture = buildReorgFixture()
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            persistRecords(db, fixture.canonical)
            upsertPeer(db, "weak.peer")
            upsertPeer(db, "good.peer")
            val mod =
                createChainHeadersModule(
                    ModuleContext(bus, db),
                    ChainHeadersOptions(
                        net = stubPlatformNet(),
                        consensus = fixture.params,
                        racePeers = 2,
                        connectTimeoutMs = 200,
                        headersTimeoutMs = 200,
                        pollIntervalMs = 10_000,
                        nowSeconds = { 10_000 },
                        fetchBatch = { host, _, _ ->
                            if (host == "weak.peer") {
                                HeaderBatchResult.Ok(10, fixture.weakerFork)
                            } else {
                                delay(30)
                                HeaderBatchResult.Ok(10, listOf(fixture.nextCanonical))
                            }
                        },
                    ),
                )
            mod.start()
            waitFor { db.headers.tip()?.height == 4 }
            assertEquals(
                bytesToHex(headerHashInternal(fixture.nextCanonical)),
                db.headers.tip()!!.hashInternalHex,
            )
            mod.stop()
            db.close()
        }
}
