package io.bluewallet.blueberry.sync.modules

import com.ionspin.kotlin.bignum.integer.BigInteger
import io.bluewallet.bip157.NODE_COMPACT_FILTERS
import io.bluewallet.blueberry.bus.BlocksProgressPayload
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.FiltersMatchPayload
import io.bluewallet.blueberry.bus.FiltersProgressPayload
import io.bluewallet.blueberry.bus.HeadersProgressPayload
import io.bluewallet.blueberry.bus.MatchingProgressPayload
import io.bluewallet.blueberry.bus.ModuleStatus
import io.bluewallet.blueberry.bus.PeersUpdatedPayload
import io.bluewallet.blueberry.bus.SyncCatchupReason
import io.bluewallet.blueberry.bus.createMessageBus
import io.bluewallet.blueberry.headers.checkpointDbRecord
import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.peers.modules.ModuleContext
import io.bluewallet.blueberry.storage.AliveServiceOptions
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.DownloadedBlock
import io.bluewallet.blueberry.storage.FilterHeaderRecord
import io.bluewallet.blueberry.storage.FilterRecord
import io.bluewallet.blueberry.storage.HeaderWrite
import io.bluewallet.blueberry.storage.MatchedBlock
import io.bluewallet.blueberry.storage.Peer
import io.bluewallet.blueberry.storage.PeerWrite
import io.bluewallet.blueberry.storage.PeersRepository
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.sync.waitFor
import io.bluewallet.blueberry.wallet.markWalletBirthdayPending
import io.bluewallet.blueberry.wallet.maybeFreezeWalletBirthday
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val CF = NODE_COMPACT_FILTERS.toULong()

/** Two consecutive idle evaluations are required before sync:idle. */
private fun enterIdle(bus: io.bluewallet.blueberry.bus.MessageBus) {
    val at = nowMillis()
    bus.emit(Event.HeadersProgress, HeadersProgressPayload(at, 1, 1, 1))
    bus.emit(Event.BlocksProgress, BlocksProgressPayload(at, 0, 0))
    bus.emit(Event.FiltersProgress, FiltersProgressPayload(at, 1, 1))
    bus.emit(Event.PeersUpdated, PeersUpdatedPayload(at))
}

private fun seedCaughtUpDb(db: Database, scanned: Boolean = true): io.bluewallet.blueberry.storage.StoredHeader {
    db.peers.upsert(
        PeerWrite(
            host = "1.1.1.1",
            port = 8333,
            services = CF,
            alive = true,
            usedForBlocks = false,
            lastProbedAt = null,
        ),
    )
    db.headers.ensureCheckpoint(checkpointDbRecord())
    val tip = db.headers.tip()!!
    db.filterHeaders.append(listOf(FilterHeaderRecord(tip.height, ByteArray(32) { 0x11 })))
    db.filters.append(
        listOf(
            FilterRecord(
                height = tip.height,
                blockHashInternalHex = tip.hashInternalHex,
                filter = byteArrayOf(0x00),
            ),
        ),
    )
    if (scanned) db.filters.markScanned(listOf(tip.height))
    return tip
}

private fun growTipWithoutFilter(db: Database, tipHeight: Int) {
    val tip = db.headers.tip()!!
    db.headers.append(
        listOf(
            HeaderWrite(
                height = tipHeight + 1,
                hashInternalHex = "cd".repeat(32),
                header = ByteArray(80),
                cumulativeWork = tip.cumulativeWork + BigInteger.ONE,
            ),
        ),
    )
}

private class RecordingPeers(
    private val inner: PeersRepository,
    val limits: MutableList<Int> = mutableListOf(),
) : PeersRepository by inner {
    override fun listAliveWithServices(
        serviceBits: ULong,
        limit: Int,
        options: AliveServiceOptions?,
    ): List<Peer> {
        limits.add(limit)
        return inner.listAliveWithServices(serviceBits, limit, options)
    }
}

private class DatabaseWithPeers(
    private val inner: Database,
    override val peers: PeersRepository,
) : Database by inner

class SyncIdleTest {
    @Test
    fun seeds_headers_from_db_so_restart_can_idle_without_headers_progress() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(
            PeerWrite("1.1.1.1", 8333, CF, true, false, null),
        )
        db.headers.ensureCheckpoint(checkpointDbRecord())
        val cp = db.headers.tip()!!
        val tipHeight = cp.height + 1
        val tipHash = "ab".repeat(32)
        db.headers.append(
            listOf(
                HeaderWrite(
                    height = tipHeight,
                    hashInternalHex = tipHash,
                    header = ByteArray(80),
                    cumulativeWork = cp.cumulativeWork + BigInteger.ONE,
                ),
            ),
        )
        markWalletBirthdayPending(db)
        maybeFreezeWalletBirthday(db, tipHeight)
        db.filters.append(
            listOf(FilterRecord(tipHeight, tipHash, byteArrayOf(0x00))),
        )
        db.filters.markScanned(listOf(tipHeight))

        val idles = mutableListOf<Long>()
        bus.on(Event.SyncIdle) { idles.add(it.at) }
        val mod = createSyncIdleModule(
            ModuleContext(bus, db),
            SyncIdleOptions(evalIntervalMs = 20, minAliveCompactFilters = 1),
        )
        mod.start()
        waitFor { idles.size >= 1 }
        mod.stop()
        db.close()
    }

    @Test
    fun needs_two_idle_evals_then_emits_once_no_re_spam() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        seedCaughtUpDb(db)

        val idles = mutableListOf<Long>()
        bus.on(Event.SyncIdle) { idles.add(it.at) }

        val mod = createSyncIdleModule(
            ModuleContext(bus, db),
            SyncIdleOptions(evalIntervalMs = 10_000, minAliveCompactFilters = 1),
        )
        mod.start()

        bus.emit(
            Event.HeadersProgress,
            HeadersProgressPayload(nowMillis(), 1, 1, 1),
        )
        delay(20)
        assertEquals(0, idles.size)

        bus.emit(Event.BlocksProgress, BlocksProgressPayload(nowMillis(), 0, 0))
        waitFor { idles.size == 1 }

        bus.emit(Event.PeersUpdated, PeersUpdatedPayload(nowMillis()))
        delay(30)
        assertEquals(1, idles.size)

        mod.stop()
        db.close()
    }

    @OptIn(ExperimentalAtomicApi::class)
    @Test
    fun transition_events_are_delivered_in_order_when_evaluations_overlap() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val tip = seedCaughtUpDb(db)
        val idleEntered = CompletableDeferred<Unit>()
        val releaseIdle = CompletableDeferred<Unit>()
        val delivered = AtomicReference<List<String>>(emptyList())

        fun record(event: String) {
            while (true) {
                val current = delivered.load()
                if (delivered.compareAndSet(current, current + event)) return
            }
        }

        bus.on(Event.SyncIdle) {
            idleEntered.complete(Unit)
            runBlocking { releaseIdle.await() }
        }
        bus.on(Event.SyncIdle) { record("idle") }
        bus.on(Event.SyncCatchup) { record("catchup") }

        val mod = createSyncIdleModule(
            ModuleContext(bus, db),
            SyncIdleOptions(evalIntervalMs = 10_000, minAliveCompactFilters = 1),
        )
        mod.start()
        bus.emit(Event.HeadersProgress, HeadersProgressPayload(nowMillis(), 1, 1, 1))

        val idleEmission = async(Dispatchers.Default) {
            bus.emit(Event.BlocksProgress, BlocksProgressPayload(nowMillis(), 0, 0))
        }
        idleEntered.await()

        db.matchedBlocks.insert(MatchedBlock(tip.height, tip.hashInternalHex))
        bus.emit(Event.FiltersMatch, FiltersMatchPayload(tip.height, tip.hashInternalHex))
        releaseIdle.complete(Unit)
        idleEmission.await()

        waitFor { delivered.load().size == 2 }
        assertEquals(listOf("idle", "catchup"), delivered.load())
        mod.stop()
        db.close()
    }

    @Test
    fun queued_progress_evaluations_keep_their_payload_order() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        seedCaughtUpDb(db)
        val idleEntered = CompletableDeferred<Unit>()
        val releaseIdle = CompletableDeferred<Unit>()
        val catchups = mutableListOf<SyncCatchupReason>()

        bus.on(Event.SyncIdle) {
            idleEntered.complete(Unit)
            runBlocking { releaseIdle.await() }
        }
        bus.on(Event.SyncCatchup) { catchups.add(it.reason) }

        val mod = createSyncIdleModule(
            ModuleContext(bus, db),
            SyncIdleOptions(evalIntervalMs = 10_000, minAliveCompactFilters = 1),
        )
        mod.start()
        bus.emit(Event.HeadersProgress, HeadersProgressPayload(nowMillis(), 1, 1, 1))
        bus.emit(Event.BlocksProgress, BlocksProgressPayload(nowMillis(), 0, 0))
        idleEntered.await()

        bus.emit(Event.HeadersProgress, HeadersProgressPayload(nowMillis(), 0, 1, 0))
        bus.emit(Event.HeadersProgress, HeadersProgressPayload(nowMillis(), 1, 1, 1))
        releaseIdle.complete(Unit)

        waitFor { catchups.isNotEmpty() }
        assertEquals(SyncCatchupReason.HEADERS, catchups.first())
        mod.stop()
        db.close()
    }

    @OptIn(ExperimentalAtomicApi::class)
    @Test
    fun stop_waits_for_an_in_progress_transition_before_reporting_stopped() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        seedCaughtUpDb(db)
        val idleEntered = CompletableDeferred<Unit>()
        val releaseIdle = CompletableDeferred<Unit>()
        val statuses = AtomicReference<List<ModuleStatus>>(emptyList())

        bus.on(Event.SyncIdle) {
            idleEntered.complete(Unit)
            runBlocking { releaseIdle.await() }
        }
        bus.on(Event.ModuleStatus) { payload ->
            if (payload.module != "sync-idle") return@on
            while (true) {
                val current = statuses.load()
                if (statuses.compareAndSet(current, current + payload.status)) break
            }
        }

        val mod = createSyncIdleModule(
            ModuleContext(bus, db),
            SyncIdleOptions(evalIntervalMs = 10_000, minAliveCompactFilters = 1),
        )
        mod.start()
        bus.emit(Event.HeadersProgress, HeadersProgressPayload(nowMillis(), 1, 1, 1))
        val idleEmission = async(Dispatchers.Default) {
            bus.emit(Event.BlocksProgress, BlocksProgressPayload(nowMillis(), 0, 0))
        }
        idleEntered.await()

        val stopped = async(Dispatchers.Default) { mod.stop() }
        delay(50)
        val stopWaitedForTransition = !stopped.isCompleted
        releaseIdle.complete(Unit)
        stopped.await()
        idleEmission.await()

        assertTrue(stopWaitedForTransition)
        assertEquals(ModuleStatus.STOPPED, statuses.load().last())
        db.close()
    }

    @Test
    fun logs_idle_and_catchup_transitions() = runBlocking {
        val logs = mutableListOf<String>()
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val tip = seedCaughtUpDb(db)
        val idles = mutableListOf<Long>()
        val catchups = mutableListOf<SyncCatchupReason>()
        bus.on(Event.SyncIdle) { idles.add(it.at) }
        bus.on(Event.SyncCatchup) { catchups.add(it.reason) }

        val mod = createSyncIdleModule(
            ModuleContext(bus, db),
            SyncIdleOptions(evalIntervalMs = 10_000, minAliveCompactFilters = 1, log = { logs.add(it) }),
        )
        mod.start()
        enterIdle(bus)
        waitFor { idles.size >= 1 }

        db.matchedBlocks.insert(MatchedBlock(tip.height, tip.hashInternalHex))
        bus.emit(Event.FiltersMatch, FiltersMatchPayload(tip.height, tip.hashInternalHex))
        waitFor { catchups.contains(SyncCatchupReason.BLOCKS) }
        mod.stop()

        val text = logs.joinToString("\n")
        db.close()
        assertTrue(text.contains("start"))
        assertTrue(text.contains("idle"))
        assertTrue(text.contains("catchup reason=blocks"))
        assertTrue(text.contains("stop"))
    }

    @Test
    fun idle_to_catchup_blocks_when_a_matched_block_needs_download() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val tip = seedCaughtUpDb(db)

        val idles = mutableListOf<Long>()
        val catchups = mutableListOf<SyncCatchupReason>()
        bus.on(Event.SyncIdle) { idles.add(it.at) }
        bus.on(Event.SyncCatchup) { catchups.add(it.reason) }

        val mod = createSyncIdleModule(
            ModuleContext(bus, db),
            SyncIdleOptions(evalIntervalMs = 10_000, minAliveCompactFilters = 1),
        )
        mod.start()
        enterIdle(bus)
        waitFor { idles.size >= 1 }

        db.matchedBlocks.insert(MatchedBlock(tip.height, tip.hashInternalHex))
        bus.emit(Event.FiltersMatch, FiltersMatchPayload(tip.height, tip.hashInternalHex))
        waitFor { catchups.contains(SyncCatchupReason.BLOCKS) }
        assertEquals(listOf(SyncCatchupReason.BLOCKS), catchups)

        mod.stop()
        db.close()
    }

    @Test
    fun idle_to_catchup_blocks_when_db_has_a_match_without_download() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val tip = seedCaughtUpDb(db)

        val idles = mutableListOf<Long>()
        val catchups = mutableListOf<SyncCatchupReason>()
        bus.on(Event.SyncIdle) { idles.add(it.at) }
        bus.on(Event.SyncCatchup) { catchups.add(it.reason) }

        val mod = createSyncIdleModule(
            ModuleContext(bus, db),
            SyncIdleOptions(evalIntervalMs = 10_000, minAliveCompactFilters = 1),
        )
        mod.start()
        enterIdle(bus)
        waitFor { idles.size >= 1 }

        db.matchedBlocks.insert(MatchedBlock(tip.height, tip.hashInternalHex))
        bus.emit(Event.BlocksProgress, BlocksProgressPayload(nowMillis(), downloaded = 0, matched = 1))
        waitFor { catchups.contains(SyncCatchupReason.BLOCKS) }
        assertEquals(listOf(SyncCatchupReason.BLOCKS), catchups)

        db.blocks.insert(DownloadedBlock(tip.height, tip.hashInternalHex, byteArrayOf(1)))
        val at = nowMillis()
        bus.emit(Event.BlocksProgress, BlocksProgressPayload(at, downloaded = 1, matched = 1))
        bus.emit(Event.FiltersProgress, FiltersProgressPayload(at, 1, 1))
        waitFor { idles.size >= 2 }

        mod.stop()
        db.close()
    }

    @Test
    fun stale_blocks_progress_does_not_catchup_when_db_is_caught_up() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val tip = seedCaughtUpDb(db)
        db.matchedBlocks.insert(MatchedBlock(tip.height, tip.hashInternalHex))
        db.blocks.insert(DownloadedBlock(tip.height, tip.hashInternalHex, byteArrayOf(1)))

        val idles = mutableListOf<Long>()
        val catchups = mutableListOf<SyncCatchupReason>()
        bus.on(Event.SyncIdle) { idles.add(it.at) }
        bus.on(Event.SyncCatchup) { catchups.add(it.reason) }

        val mod = createSyncIdleModule(
            ModuleContext(bus, db),
            SyncIdleOptions(evalIntervalMs = 10_000, minAliveCompactFilters = 1),
        )
        mod.start()
        enterIdle(bus)
        waitFor { idles.size >= 1 }

        bus.emit(Event.BlocksProgress, BlocksProgressPayload(nowMillis(), downloaded = 0, matched = 1))
        delay(40)
        assertEquals(emptyList(), catchups)
        assertEquals(1, idles.size)

        mod.stop()
        db.close()
    }

    @Test
    fun birthday_wallet_idles_when_filters_cover_birthday_to_tip_only() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(PeerWrite("1.1.1.1", 8333, CF, true, false, null))
        db.headers.ensureCheckpoint(checkpointDbRecord())
        val cp = db.headers.tip()!!
        val tipHeight = cp.height + 1
        val tipHash = "ab".repeat(32)
        db.headers.append(
            listOf(
                HeaderWrite(
                    height = tipHeight,
                    hashInternalHex = tipHash,
                    header = ByteArray(80),
                    cumulativeWork = cp.cumulativeWork + BigInteger.ONE,
                ),
            ),
        )
        markWalletBirthdayPending(db)
        maybeFreezeWalletBirthday(db, tipHeight)
        db.filterHeaders.append(listOf(FilterHeaderRecord(tipHeight, ByteArray(32) { 0x11 })))
        db.filters.append(listOf(FilterRecord(tipHeight, tipHash, byteArrayOf(0x00))))
        db.filters.markScanned(listOf(tipHeight))

        val idles = mutableListOf<Long>()
        bus.on(Event.SyncIdle) { idles.add(it.at) }
        val mod = createSyncIdleModule(
            ModuleContext(bus, db),
            SyncIdleOptions(evalIntervalMs = 10_000, minAliveCompactFilters = 1),
        )
        mod.start()
        enterIdle(bus)
        waitFor { idles.size >= 1 }
        assertEquals(1, idles.size)
        mod.stop()
        db.close()
    }

    @Test
    fun stays_idle_when_last_alive_peer_dies_after_local_catch_up() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        seedCaughtUpDb(db)

        val idles = mutableListOf<Long>()
        val catchups = mutableListOf<SyncCatchupReason>()
        bus.on(Event.SyncIdle) { idles.add(it.at) }
        bus.on(Event.SyncCatchup) { catchups.add(it.reason) }

        val mod = createSyncIdleModule(
            ModuleContext(bus, db),
            SyncIdleOptions(evalIntervalMs = 10_000, minAliveCompactFilters = 1),
        )
        mod.start()
        enterIdle(bus)
        waitFor { idles.size >= 1 }

        db.peers.markAlive("1.1.1.1", 8333, false)
        bus.emit(Event.PeersUpdated, PeersUpdatedPayload(nowMillis()))
        delay(40)
        assertEquals(emptyList(), catchups)

        mod.stop()
        db.close()
    }

    @Test
    fun idle_to_catchup_filters_when_tip_advances_without_cfilters() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val tip = seedCaughtUpDb(db)

        val idles = mutableListOf<Long>()
        val catchups = mutableListOf<SyncCatchupReason>()
        bus.on(Event.SyncIdle) { idles.add(it.at) }
        bus.on(Event.SyncCatchup) { catchups.add(it.reason) }

        val mod = createSyncIdleModule(
            ModuleContext(bus, db),
            SyncIdleOptions(evalIntervalMs = 10_000, minAliveCompactFilters = 1),
        )
        mod.start()
        enterIdle(bus)
        waitFor { idles.size >= 1 }

        growTipWithoutFilter(db, tip.height)
        bus.emit(
            Event.HeadersProgress,
            HeadersProgressPayload(nowMillis(), 1, 1, tip.height + 1),
        )
        waitFor { catchups.contains(SyncCatchupReason.FILTERS) }
        assertEquals(listOf(SyncCatchupReason.FILTERS), catchups)

        mod.stop()
        db.close()
    }

    @Test
    fun idle_to_catchup_peers_when_filter_work_meets_a_thin_cf_pool() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val tip = seedCaughtUpDb(db)

        val idles = mutableListOf<Long>()
        val catchups = mutableListOf<SyncCatchupReason>()
        bus.on(Event.SyncIdle) { idles.add(it.at) }
        bus.on(Event.SyncCatchup) { catchups.add(it.reason) }

        val mod = createSyncIdleModule(
            ModuleContext(bus, db),
            SyncIdleOptions(evalIntervalMs = 10_000, minAliveCompactFilters = 2),
        )
        mod.start()
        enterIdle(bus)
        waitFor { idles.size >= 1 }

        growTipWithoutFilter(db, tip.height)
        bus.emit(
            Event.HeadersProgress,
            HeadersProgressPayload(nowMillis(), 1, 1, tip.height + 1),
        )
        waitFor { catchups.contains(SyncCatchupReason.PEERS) }
        assertEquals(listOf(SyncCatchupReason.PEERS), catchups)

        mod.stop()
        db.close()
    }

    @Test
    fun catchup_skips_match_and_peer_churn_snapshots() = runBlocking {
        val bus = createMessageBus()
        val inner = createSqliteDatabase(":memory:")
        seedCaughtUpDb(inner)
        val spy = RecordingPeers(inner.peers)
        val db = DatabaseWithPeers(inner, spy)

        val mod = createSyncIdleModule(
            ModuleContext(bus, db),
            SyncIdleOptions(evalIntervalMs = 10_000, minAliveCompactFilters = 1),
        )
        mod.start()

        repeat(20) {
            bus.emit(Event.FiltersMatch, FiltersMatchPayload(1, "aa".repeat(32)))
            bus.emit(Event.PeersUpdated, PeersUpdatedPayload(nowMillis()))
        }
        assertEquals(emptyList(), spy.limits)

        mod.stop()
        inner.close()
    }

    @Test
    fun catchup_eval_does_not_scan_the_compact_filter_pool() = runBlocking {
        val bus = createMessageBus()
        val inner = createSqliteDatabase(":memory:")
        seedCaughtUpDb(inner)
        val spy = RecordingPeers(inner.peers)
        val db = DatabaseWithPeers(inner, spy)

        val mod = createSyncIdleModule(
            ModuleContext(bus, db),
            SyncIdleOptions(evalIntervalMs = 10_000, minAliveCompactFilters = 16),
        )
        mod.start()

        bus.emit(Event.HeadersProgress, HeadersProgressPayload(nowMillis(), 1, 1, 1))
        assertTrue(!spy.limits.contains(16))

        mod.stop()
        inner.close()
    }

    @Test
    fun does_not_idle_while_filters_are_unscanned_and_ignores_stale_progress_counts() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val tip = seedCaughtUpDb(db, scanned = false)
        val idles = mutableListOf<Long>()
        bus.on(Event.SyncIdle) { idles.add(it.at) }
        val mod = createSyncIdleModule(
            ModuleContext(bus, db),
            SyncIdleOptions(evalIntervalMs = 10_000, minAliveCompactFilters = 1),
        )
        mod.start()
        enterIdle(bus)
        delay(40)
        assertEquals(emptyList(), idles)

        db.filters.markScanned(listOf(tip.height))
        val at = nowMillis()
        bus.emit(Event.MatchingProgress, MatchingProgressPayload(at, 0, 1))
        bus.emit(Event.MatchingProgress, MatchingProgressPayload(at, 0, 1))
        waitFor { idles.size >= 1 }

        mod.stop()
        db.close()
    }
}
