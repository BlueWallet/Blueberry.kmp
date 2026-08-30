package io.bluewallet.blueberry.peers.modules

import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.PeerSocketKind
import io.bluewallet.blueberry.bus.SyncCatchupPayload
import io.bluewallet.blueberry.bus.SyncCatchupReason
import io.bluewallet.blueberry.bus.SyncIdlePayload
import io.bluewallet.blueberry.bus.createMessageBus
import io.bluewallet.blueberry.peers.net.PeerCandidate
import io.bluewallet.blueberry.peers.net.ProbeResult
import io.bluewallet.blueberry.peers.net.stubPlatformNet
import io.bluewallet.blueberry.peers.waitFor
import io.bluewallet.blueberry.storage.MatchedBlock
import io.bluewallet.blueberry.storage.PeerWrite
import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun peer(
    host: String,
    services: ULong = 0uL,
    alive: Boolean = false,
    lastProbedAt: Long? = null,
    usedForBlocks: Boolean = false,
) = PeerWrite(host, 8333, services, alive, usedForBlocks, lastProbedAt)

private fun hangingSeeds(): suspend () -> List<PeerCandidate> = {
    CompletableDeferred<List<PeerCandidate>>().await()
}

@OptIn(ExperimentalAtomicApi::class)
class PeersDiscoveryTest {
    @Test
    fun emits_peers_sockets_probe_counts_while_probing() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("1.1.1.1", alive = true))
        val opens = mutableListOf<Int>()
        bus.on(Event.PeersSockets) { if (it.kind == PeerSocketKind.PROBE) opens.add(it.open) }
        val gate = CompletableDeferred<Unit>()
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                probe = { _, _, _ ->
                    gate.await()
                    ProbeResult.Err("skip")
                },
                concurrency = 1,
                idleDelayMs = 50,
                minAliveCompactFilters = 0,
            ),
        )
        mod.start()
        waitFor { opens.contains(1) }
        gate.complete(Unit)
        waitFor { opens.contains(0) && opens.indexOf(0) > opens.indexOf(1) }
        mod.stop()
        db.close()
    }

    @Test
    fun stop_joins_in_flight_probe_before_returning() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("1.1.1.1"))
        val entered = CompletableDeferred<Unit>()
        var finishedProbe = false
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                probe = { _, _, _ ->
                    entered.complete(Unit)
                    withContext(NonCancellable) { delay(80) }
                    finishedProbe = true
                    ProbeResult.Err("skip")
                },
                concurrency = 1,
                idleDelayMs = 50,
                minAliveCompactFilters = 0,
            ),
        )
        mod.start()
        entered.await()
        mod.stop()
        assertTrue(finishedProbe)
        db.close()
    }

    @Test
    fun stop_does_not_wait_out_uncancellable_dns() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val entered = CompletableDeferred<Unit>()
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = {
                    entered.complete(Unit)
                    withContext(NonCancellable) { delay(5_000) }
                    emptyList()
                },
                probe = { _, _, _ -> ProbeResult.Err("skip") },
                concurrency = 1,
                idleDelayMs = 50,
                minAliveCompactFilters = 0,
            ),
        )
        mod.start()
        entered.await()
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        mod.stop()
        assertTrue(started.elapsedNow().inWholeMilliseconds < 2_500)
        db.close()
    }

    @Test
    fun dns_bootstrap_inserts_seed_peers_and_emits_peers_updated() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        var updates = 0
        bus.on(Event.PeersUpdated) { updates++ }

        var dnsCalls = 0
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = {
                    dnsCalls++
                    listOf(
                        PeerCandidate("10.0.0.1", 8333, 0uL),
                        PeerCandidate("10.0.0.2", 8333, 0uL),
                    )
                },
                probe = { _, _, _ -> ProbeResult.Err("skip") },
                concurrency = 1,
                idleDelayMs = 50,
                minAliveCompactFilters = 0,
            ),
        )

        mod.start()
        waitFor { db.peers.count() == 2 }
        assertEquals(1, dnsCalls)
        assertTrue(updates >= 1)
        assertEquals(emptyList(), db.peers.listAlive())
        mod.stop()
        db.close()
    }

    @Test
    fun alive_peers_skip_dns_successful_probe_stores_neighbors_and_marks_source_alive() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("8.8.8.8", alive = true))

        var dnsCalls = 0
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = {
                    dnsCalls++
                    listOf(PeerCandidate("should.not.appear", 8333, 0uL))
                },
                probe = { host, _, _ ->
                    if (host == "8.8.8.8") {
                        ProbeResult.Ok(
                            peers = listOf(PeerCandidate("9.9.9.9", 8333, 1033uL)),
                            services = 64uL,
                        )
                    } else {
                        ProbeResult.Err("no")
                    }
                },
                concurrency = 1,
                idleDelayMs = 50,
                minAliveCompactFilters = 0,
            ),
        )

        mod.start()
        waitFor { db.peers.list().any { it.host == "9.9.9.9" } }
        assertEquals(0, dnsCalls)
        assertFalse(db.peers.list().any { it.host == "should.not.appear" })
        val neighbor = db.peers.list().find { it.host == "9.9.9.9" }
        assertEquals(1033uL, neighbor?.services)
        assertEquals(false, neighbor?.alive)
        assertEquals(true, db.peers.list().find { it.host == "8.8.8.8" }?.alive)
        assertEquals(64uL, db.peers.list().find { it.host == "8.8.8.8" }?.services)
        assertNotNull(db.peers.list().find { it.host == "8.8.8.8" }?.lastProbedAt)
        mod.stop()
        db.close()
    }

    @Test
    fun reseeds_dns_when_alive_compact_filter_peers_are_scarce() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("1.1.1.1", alive = true, lastProbedAt = 1))

        var dnsCalls = 0
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = {
                    dnsCalls++
                    listOf(PeerCandidate("10.0.0.9", 8333, 0uL))
                },
                probe = { _, _, _ -> ProbeResult.Err("skip") },
                concurrency = 1,
                idleDelayMs = 20,
                minAliveCompactFilters = 2,
                reseedIntervalMs = 1,
            ),
        )

        mod.start()
        waitFor { dnsCalls >= 1 }
        waitFor { db.peers.list().any { it.host == "10.0.0.9" } }
        assertTrue(dnsCalls >= 1)
        mod.stop()
        db.close()
    }

    @Test
    fun dns_reseed_does_not_zero_learned_service_bits_on_known_peers() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("1.1.1.1", services = 64uL, alive = true, lastProbedAt = 1))

        var dnsCalls = 0
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = {
                    dnsCalls++
                    listOf(PeerCandidate("1.1.1.1", 8333, 0uL))
                },
                probe = { _, _, _ -> ProbeResult.Err("skip") },
                concurrency = 1,
                idleDelayMs = 20,
                minAliveCompactFilters = 2,
                reseedIntervalMs = 1,
            ),
        )

        mod.start()
        waitFor { dnsCalls >= 1 }
        val found = db.peers.list().find { it.host == "1.1.1.1" }
        assertEquals(64uL, found?.services)
        assertTrue(db.peers.listWithServices(64uL, 10).map { it.host }.contains("1.1.1.1"))
        mod.stop()
        db.close()
    }

    @Test
    fun probes_known_peers_while_dns_bootstrap_is_still_in_flight() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("5.5.5.5", services = 64uL, alive = false))

        var probed = false
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = hangingSeeds(),
                probe = { host, _, _ ->
                    if (host == "5.5.5.5") probed = true
                    ProbeResult.Err("skip")
                },
                concurrency = 1,
                idleDelayMs = 20,
                minAliveCompactFilters = 0,
            ),
        )

        mod.start()
        waitFor { probed }
        mod.stop()
        db.close()
    }

    @Test
    fun prefers_compact_filter_candidates_when_that_pool_is_thin() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("1.1.1.1"))
        db.peers.upsert(peer("2.2.2.2", services = 64uL, lastProbedAt = 99))

        val probed = mutableListOf<String>()
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                probe = { host, _, _ ->
                    probed.add(host)
                    ProbeResult.Err("skip")
                },
                concurrency = 1,
                idleDelayMs = 20,
                minAliveCompactFilters = 1,
            ),
        )

        mod.start()
        waitFor { probed.size >= 1 }
        assertEquals("2.2.2.2", probed[0])
        mod.stop()
        db.close()
    }

    @Test
    fun probes_never_probed_peers_even_when_many_dead_compact_filter_peers_exist() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        for (i in 0 until 8) {
            db.peers.upsert(peer("2.2.2.$i", services = 64uL, lastProbedAt = 1))
        }
        db.peers.upsert(peer("9.9.9.9"))

        val probed = mutableListOf<String>()
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                probe = { host, _, _ ->
                    probed.add(host)
                    ProbeResult.Err("skip")
                },
                concurrency = 4,
                idleDelayMs = 20,
                minAliveCompactFilters = 16,
                now = { 1_000 },
            ),
        )

        mod.start()
        waitFor { probed.contains("9.9.9.9") }
        mod.stop()
        db.close()
    }

    @Test
    fun reseeds_immediately_when_compact_filter_peers_are_scarce() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("1.1.1.1", alive = true, lastProbedAt = 1))

        var dnsCalls = 0
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = {
                    dnsCalls++
                    listOf(PeerCandidate("10.0.0.9", 8333, 0uL))
                },
                probe = { _, _, _ -> ProbeResult.Err("skip") },
                concurrency = 1,
                idleDelayMs = 20,
                minAliveCompactFilters = 2,
                reseedIntervalMs = 60_000,
            ),
        )

        mod.start()
        waitFor { dnsCalls >= 1 }
        waitFor { db.peers.list().any { it.host == "10.0.0.9" } }
        mod.stop()
        db.close()
    }

    @Test
    fun probes_known_peers_while_dns_reseed_is_still_in_flight() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("1.1.1.1", alive = true, lastProbedAt = 1))
        db.peers.upsert(peer("2.2.2.2"))

        var probedNever = false
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = hangingSeeds(),
                probe = { host, _, _ ->
                    if (host == "2.2.2.2") probedNever = true
                    ProbeResult.Err("skip")
                },
                concurrency = 1,
                idleDelayMs = 20,
                minAliveCompactFilters = 2,
                reseedIntervalMs = 0,
            ),
        )

        mod.start()
        waitFor { probedNever }
        mod.stop()
        db.close()
    }

    @Test
    fun default_probe_path_calls_net_connect() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("1.1.1.1", alive = true))

        var connectHost: String? = null
        val stub = stubPlatformNet()
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = io.bluewallet.blueberry.peers.net.PlatformNet(
                    connect = { host, _ ->
                        connectHost = host
                        error("ECONNREFUSED")
                    },
                    dns = stub.dns,
                ),
                resolveSeeds = { emptyList() },
                concurrency = 1,
                idleDelayMs = 50,
                minAliveCompactFilters = 0,
            ),
        )

        mod.start()
        waitFor { connectHost == "1.1.1.1" }
        waitFor { db.peers.list()[0].alive == false }
        mod.stop()
        db.close()
    }

    @Test
    fun failed_probe_updates_last_probed_at_and_clears_alive() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("1.1.1.1", alive = true))

        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                probe = { _, _, _ -> ProbeResult.Err("down") },
                concurrency = 1,
                idleDelayMs = 50,
                now = { 12345 },
            ),
        )

        mod.start()
        waitFor { db.peers.list()[0].lastProbedAt == 12345L }
        assertEquals(false, db.peers.list()[0].alive)
        mod.stop()
        db.close()
    }

    @Test
    fun does_not_immediately_reprobe_a_peer_that_just_failed() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("1.1.1.1", alive = true))

        var probes = 0
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                probe = { _, _, _ ->
                    probes++
                    ProbeResult.Err("down")
                },
                concurrency = 1,
                idleDelayMs = 20,
                probeTimeoutMs = 180,
                minAliveCompactFilters = 0,
            ),
        )

        mod.start()
        waitFor { probes >= 1 }
        val atFirst = probes
        delay(40)
        assertEquals(atFirst, probes)
        waitFor { probes > atFirst }
        mod.stop()
        db.close()
    }

    @Test
    fun inflight_probe_after_stop_does_not_persist_results() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("1.1.1.1", alive = true))

        val opens = mutableListOf<Int>()
        bus.on(Event.PeersSockets) { if (it.kind == PeerSocketKind.PROBE) opens.add(it.open) }

        val gate = CompletableDeferred<Unit>()
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                probe = { _, _, _ ->
                    gate.await()
                    ProbeResult.Err("down")
                },
                concurrency = 1,
                idleDelayMs = 50,
                now = { 12345 },
                minAliveCompactFilters = 0,
            ),
        )

        mod.start()
        waitFor { opens.contains(1) }
        mod.stop()
        gate.complete(Unit)
        delay(40)
        assertEquals(null, db.peers.list()[0].lastProbedAt)
        assertEquals(true, db.peers.list()[0].alive)
        db.close()
    }

    @Test
    fun sync_idle_pauses_probes_sync_catchup_resumes() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val recent = 1_000_000L
        db.peers.upsert(peer("1.1.1.1", alive = true, lastProbedAt = recent))
        db.peers.upsert(peer("2.2.2.2"))

        val probes = AtomicInt(0)
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                probe = { _, _, _ ->
                    probes.incrementAndFetch()
                    ProbeResult.Err("no")
                },
                concurrency = 1,
                idleDelayMs = 20,
                probeTimeoutMs = 60_000,
                minAliveCompactFilters = 0,
                now = { recent },
            ),
        )
        mod.start()
        waitFor { probes.load() >= 1 }
        val atIdle = probes.load()
        bus.emit(Event.SyncIdle, SyncIdlePayload(at = recent))
        delay(80)
        assertEquals(atIdle, probes.load())
        db.peers.upsert(peer("3.3.3.3"))
        bus.emit(Event.SyncCatchup, SyncCatchupPayload(at = recent, reason = SyncCatchupReason.HEADERS))
        waitFor { probes.load() > atIdle }
        mod.stop()
        db.close()
    }

    @Test
    fun sync_idle_keeps_probing_when_blocks_still_need_download() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val recent = 1_000_000L
        db.peers.upsert(peer("1.1.1.1", alive = true, lastProbedAt = recent))
        db.peers.upsert(peer("2.2.2.2"))
        db.matchedBlocks.insert(MatchedBlock(10, "aa".repeat(32)))

        val probes = AtomicInt(0)
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                probe = { _, _, _ ->
                    probes.incrementAndFetch()
                    ProbeResult.Err("no")
                },
                concurrency = 1,
                idleDelayMs = 20,
                probeTimeoutMs = 60_000,
                minAliveCompactFilters = 0,
                now = { recent },
            ),
        )
        mod.start()
        waitFor { probes.load() >= 1 }
        val atIdle = probes.load()
        bus.emit(Event.SyncIdle, SyncIdlePayload(at = recent))
        db.peers.upsert(peer("3.3.3.3"))
        waitFor { probes.load() > atIdle }
        mod.stop()
        db.close()
    }

    @Test
    fun sync_idle_keeps_probing_when_no_peer_is_alive() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("2.2.2.2"))

        var probes = 0
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                probe = { _, _, _ ->
                    probes++
                    ProbeResult.Err("offline")
                },
                concurrency = 1,
                idleDelayMs = 20,
                probeTimeoutMs = 20,
                minAliveCompactFilters = 0,
            ),
        )
        mod.start()
        bus.emit(Event.SyncIdle, SyncIdlePayload(at = 0))
        waitFor { probes >= 1 }
        mod.stop()
        db.close()
    }

    @Test
    fun sync_idle_resumes_probes_after_the_last_alive_peer_dies() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("1.1.1.1", alive = true))
        db.peers.upsert(peer("2.2.2.2"))

        var probes = 0
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                probe = { _, _, _ ->
                    probes++
                    ProbeResult.Err("no")
                },
                concurrency = 1,
                idleDelayMs = 20,
                probeTimeoutMs = 20,
                minAliveCompactFilters = 0,
            ),
        )
        mod.start()
        waitFor { probes >= 1 }
        bus.emit(Event.SyncIdle, SyncIdlePayload(at = 0))
        delay(80)
        val atIdle = probes
        db.peers.markAlive("1.1.1.1", 8333, false)
        bus.emit(Event.PeersUpdated, io.bluewallet.blueberry.bus.PeersUpdatedPayload(at = 0))
        waitFor { probes > atIdle }
        mod.stop()
        db.close()
    }

    @Test
    fun only_one_crawl_probe_at_a_time_interval_gates_the_next() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("1.1.1.1", alive = true))
        db.peers.upsert(peer("2.2.2.2"))

        val t = AtomicLong(0)
        val flags = mutableListOf<Boolean>()
        val flagsLock = Any()
        fun snapshotFlags() = synchronized(flagsLock) { flags.toList() }
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                now = { t.load() },
                crawlIntervalMs = 15_000,
                probeTimeoutMs = 0,
                concurrency = 2,
                idleDelayMs = 20,
                minAliveCompactFilters = 0,
                probe = { _, _, options ->
                    synchronized(flagsLock) { flags.add(options.wantAddr) }
                    ProbeResult.Ok(emptyList(), 1uL)
                },
            ),
        )

        mod.start()
        waitFor { snapshotFlags().size >= 2 }
        assertEquals(1, snapshotFlags().count { it })
        waitFor { snapshotFlags().size >= 3 }
        assertEquals(1, snapshotFlags().count { it })
        t.store(15_000)
        waitFor { snapshotFlags().count { it } >= 2 }
        mod.stop()
        db.close()
    }

    @Test
    fun crawls_several_peers_at_once_when_blocks_need_download() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("1.1.1.1"))
        db.peers.upsert(peer("2.2.2.2"))
        db.peers.upsert(peer("3.3.3.3"))
        db.peers.upsert(peer("4.4.4.4"))
        db.matchedBlocks.insert(MatchedBlock(10, "aa".repeat(32)))

        val flags = mutableListOf<Boolean>()
        val flagsLock = Any()
        fun snapshotFlags() = synchronized(flagsLock) { flags.toList() }
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                now = { 0 },
                crawlIntervalMs = 15_000,
                probeTimeoutMs = 0,
                concurrency = 4,
                idleDelayMs = 20,
                minAliveCompactFilters = 0,
                probe = { _, _, options ->
                    synchronized(flagsLock) { flags.add(options.wantAddr) }
                    ProbeResult.Err("skip")
                },
            ),
        )

        mod.start()
        waitFor { snapshotFlags().size >= 4 }
        assertTrue(snapshotFlags().count { it } >= 2)
        mod.stop()
        db.close()
    }

    @Test
    fun does_not_burst_crawl_when_unused_unprobed_network_backlog_is_full() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        for (i in 1..4) {
            db.peers.upsert(peer("$i.$i.$i.$i", services = 1uL))
        }
        db.matchedBlocks.insert(MatchedBlock(10, "aa".repeat(32)))

        val flags = mutableListOf<Boolean>()
        val lock = Any()
        val firstBatchGate = CompletableDeferred<Unit>()
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                now = { 0 },
                crawlIntervalMs = 15_000,
                probeTimeoutMs = 0,
                concurrency = 4,
                idleDelayMs = 20,
                minAliveCompactFilters = 0,
                minAliveBlockPeers = 2,
                probe = { _, _, options ->
                    synchronized(lock) { flags.add(options.wantAddr) }
                    firstBatchGate.await()
                    ProbeResult.Err("skip")
                },
            ),
        )

        mod.start()
        waitFor { synchronized(lock) { flags.size >= 4 } }
        assertEquals(1, synchronized(lock) { flags.count { it } })
        firstBatchGate.complete(Unit)
        mod.stop()
        db.close()
    }

    @Test
    fun block_crawl_burst_repeats_without_waiting_for_the_normal_interval() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        for (i in 1..4) {
            db.peers.upsert(peer("$i.$i.$i.$i", services = 1uL, alive = true))
        }
        db.matchedBlocks.insert(MatchedBlock(10, "aa".repeat(32)))

        val flags = mutableListOf<Boolean>()
        val lock = Any()
        val firstBatchGate = CompletableDeferred<Unit>()
        val secondBatchGate = CompletableDeferred<Unit>()
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                now = { 0 },
                crawlIntervalMs = 15_000,
                probeTimeoutMs = 0,
                concurrency = 4,
                idleDelayMs = 20,
                minAliveCompactFilters = 0,
                probe = { _, _, options ->
                    val call = synchronized(lock) {
                        flags.add(options.wantAddr)
                        flags.size
                    }
                    when (call) {
                        in 1..4 -> firstBatchGate.await()
                        in 5..8 -> secondBatchGate.await()
                    }
                    ProbeResult.Ok(emptyList(), 1uL)
                },
            ),
        )

        mod.start()
        waitFor { synchronized(lock) { flags.size >= 4 } }
        firstBatchGate.complete(Unit)
        waitFor { synchronized(lock) { flags.size >= 8 } }
        assertEquals(4, synchronized(lock) { flags.take(8).count { it } })
        secondBatchGate.complete(Unit)
        mod.stop()
        db.close()
    }

    @Test
    fun stop_then_start_can_crawl_again() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("1.1.1.1", alive = true))

        val flags = mutableListOf<Boolean>()
        val flagsLock = Any()
        fun snapshotFlags() = synchronized(flagsLock) { flags.toList() }
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                now = { 0 },
                crawlIntervalMs = 15_000,
                probeTimeoutMs = 0,
                concurrency = 1,
                idleDelayMs = 20,
                minAliveCompactFilters = 0,
                probe = { _, _, options ->
                    synchronized(flagsLock) { flags.add(options.wantAddr) }
                    ProbeResult.Ok(emptyList(), 1uL)
                },
            ),
        )

        mod.start()
        waitFor { snapshotFlags().contains(true) }
        mod.stop()
        synchronized(flagsLock) { flags.clear() }
        mod.start()
        waitFor { snapshotFlags().contains(true) }
        mod.stop()
        db.close()
    }

    @Test
    fun crawl_upserts_candidates_from_wantAddr_probe() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("8.8.8.8", alive = true))

        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                concurrency = 1,
                idleDelayMs = 50,
                minAliveCompactFilters = 0,
                crawlIntervalMs = 15_000,
                probe = { _, _, options ->
                    if (options.wantAddr) {
                        ProbeResult.Ok(
                            peers = listOf(PeerCandidate("9.9.9.9", 8333, 1033uL)),
                            services = 64uL,
                        )
                    } else {
                        ProbeResult.Err("skip")
                    }
                },
            ),
        )

        mod.start()
        waitFor { db.peers.list().any { it.host == "9.9.9.9" } }
        mod.stop()
        db.close()
    }

    @Test
    fun sync_idle_does_not_start_a_crawl() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("1.1.1.1", alive = true))

        val t = AtomicLong(0)
        val flags = mutableListOf<Boolean>()
        val flagsLock = Any()
        fun snapshotFlags() = synchronized(flagsLock) { flags.toList() }
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                now = { t.load() },
                concurrency = 1,
                idleDelayMs = 20,
                probeTimeoutMs = 0,
                minAliveCompactFilters = 0,
                crawlIntervalMs = 15_000,
                probe = { _, _, options ->
                    synchronized(flagsLock) { flags.add(options.wantAddr) }
                    ProbeResult.Ok(emptyList(), 0uL)
                },
            ),
        )
        mod.start()
        waitFor { snapshotFlags().contains(true) }
        val beforeIdle = snapshotFlags().size
        bus.emit(Event.SyncIdle, SyncIdlePayload(at = 0))
        delay(20)
        t.store(15_000)
        delay(80)
        assertEquals(emptyList(), snapshotFlags().drop(beforeIdle).filter { it })
        mod.stop()
        db.close()
    }

    @Test
    fun reseeds_dns_when_compact_filter_peers_are_plenty_but_block_peers_are_scarce() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("1.1.1.1", services = 64uL, alive = true, lastProbedAt = 1))
        db.matchedBlocks.insert(MatchedBlock(10, "aa".repeat(32)))

        val dnsCalls = AtomicInt(0)
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = {
                    dnsCalls.incrementAndFetch()
                    listOf(PeerCandidate("10.0.0.9", 8333, 0uL))
                },
                probe = { _, _, _ -> ProbeResult.Err("skip") },
                concurrency = 1,
                idleDelayMs = 20,
                minAliveCompactFilters = 1,
                reseedIntervalMs = 60_000,
            ),
        )

        mod.start()
        waitFor { dnsCalls.load() >= 1 }
        waitFor { db.peers.list().any { it.host == "10.0.0.9" } }
        mod.stop()
        db.close()
    }

    @Test
    fun reseeds_dns_every_15s_when_blocks_need_download_even_if_normal_interval_is_longer() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("1.1.1.1", services = 64uL, alive = true, lastProbedAt = 1))
        db.matchedBlocks.insert(MatchedBlock(10, "aa".repeat(32)))

        val t = AtomicLong(60_000L)
        val dnsCalls = AtomicInt(0)
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                now = { t.load() },
                resolveSeeds = {
                    val call = dnsCalls.incrementAndFetch()
                    listOf(PeerCandidate("10.0.0.$call", 8333, 0uL))
                },
                probe = { _, _, _ -> ProbeResult.Err("skip") },
                concurrency = 1,
                idleDelayMs = 20,
                minAliveCompactFilters = 1,
                reseedIntervalMs = 60_000,
            ),
        )

        mod.start()
        waitFor { dnsCalls.load() >= 1 }
        t.store(76_000)
        waitFor { dnsCalls.load() >= 2 }
        waitFor { db.peers.list().any { it.host == "10.0.0.2" } }
        mod.stop()
        db.close()
    }

    @Test
    fun does_not_reseed_when_blocks_pending_but_unused_network_pool_is_full() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("1.1.1.1", services = 1uL, alive = true, lastProbedAt = 1))
        db.matchedBlocks.insert(MatchedBlock(10, "aa".repeat(32)))

        val dnsCalls = AtomicInt(0)
        val probeGate = CompletableDeferred<Unit>()
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                now = { 60_000 },
                resolveSeeds = {
                    dnsCalls.incrementAndFetch()
                    emptyList()
                },
                probe = { _, _, _ ->
                    probeGate.await()
                    ProbeResult.Err("skip")
                },
                concurrency = 1,
                idleDelayMs = 20,
                minAliveCompactFilters = 0,
                minAliveBlockPeers = 1,
                reseedIntervalMs = 60_000,
            ),
        )

        mod.start()
        delay(80)
        assertEquals(0, dnsCalls.load())
        probeGate.complete(Unit)
        mod.stop()
        db.close()
    }

    @Test
    fun prefers_network_peers_and_crawls_them_when_blocks_need_download() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("1.1.1.1"))
        db.peers.upsert(peer("4.4.4.4", services = 1uL, lastProbedAt = 99))
        db.matchedBlocks.insert(MatchedBlock(10, "aa".repeat(32)))

        val probed = mutableListOf<String>()
        val crawled = mutableListOf<String>()
        val lock = Any()
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                probe = { host, _, options ->
                    synchronized(lock) {
                        probed.add(host)
                        if (options.wantAddr) crawled.add(host)
                    }
                    ProbeResult.Err("skip")
                },
                concurrency = 1,
                idleDelayMs = 20,
                minAliveCompactFilters = 0,
            ),
        )

        mod.start()
        waitFor { synchronized(lock) { probed.isNotEmpty() } }
        assertEquals("4.4.4.4", synchronized(lock) { probed[0] })
        waitFor { synchronized(lock) { crawled.contains("1.1.1.1") } }
        assertFalse(synchronized(lock) { crawled.contains("4.4.4.4") })
        mod.stop()
        db.close()
    }

    @Test
    fun prefers_fresh_network_candidate_when_used_peers_fill_the_service_query_limit() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        repeat(100) { i ->
            db.peers.upsert(
                peer(
                    host = "20.0.${i / 256}.${i % 256}",
                    services = 1uL,
                    alive = true,
                    lastProbedAt = 1,
                    usedForBlocks = true,
                ),
            )
        }
        repeat(20) { i -> db.peers.upsert(peer("10.0.0.$i")) }
        db.peers.upsert(peer("250.0.0.1", services = 1uL))
        db.matchedBlocks.insert(MatchedBlock(10, "aa".repeat(32)))

        val probed = mutableListOf<String>()
        val lock = Any()
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                probe = { host, _, _ ->
                    synchronized(lock) { probed.add(host) }
                    ProbeResult.Err("skip")
                },
                concurrency = 1,
                idleDelayMs = 20,
                minAliveCompactFilters = 0,
            ),
        )

        mod.start()
        waitFor { synchronized(lock) { probed.isNotEmpty() } }
        assertEquals("250.0.0.1", synchronized(lock) { probed.first() })
        mod.stop()
        db.close()
    }

    @Test
    fun still_probes_network_peers_when_compact_filter_pool_is_also_thin() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("1.1.1.1"))
        db.peers.upsert(peer("2.2.2.2", services = 64uL, lastProbedAt = 99))
        db.peers.upsert(peer("4.4.4.4", services = 1uL, lastProbedAt = 99))
        db.matchedBlocks.insert(MatchedBlock(10, "aa".repeat(32)))

        val probed = mutableListOf<String>()
        val lock = Any()
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                probe = { host, _, _ ->
                    synchronized(lock) { probed.add(host) }
                    ProbeResult.Err("skip")
                },
                concurrency = 2,
                idleDelayMs = 20,
                minAliveCompactFilters = 1,
            ),
        )

        mod.start()
        waitFor { synchronized(lock) { probed.contains("4.4.4.4") } }
        mod.stop()
        db.close()
    }

    @Test
    fun re_probes_oldest_dead_peer_even_when_many_unprobed_remain_if_blocks_need_download() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        for (i in 0 until 80) {
            db.peers.upsert(peer("10.0.0.$i"))
        }
        db.peers.upsert(peer("8.8.8.8", services = 64uL, lastProbedAt = 1))
        db.peers.upsert(peer("9.9.9.9", services = 1uL, lastProbedAt = 50))
        db.matchedBlocks.insert(MatchedBlock(10, "aa".repeat(32)))

        val probed = mutableListOf<String>()
        val lock = Any()
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                now = { 100_000 },
                probeTimeoutMs = 3_000,
                probe = { host, _, _ ->
                    synchronized(lock) { probed.add(host) }
                    ProbeResult.Err("skip")
                },
                concurrency = 4,
                idleDelayMs = 20,
                minAliveCompactFilters = 0,
            ),
        )

        mod.start()
        waitFor { synchronized(lock) { probed.size >= 8 } }
        val first = synchronized(lock) { probed.take(8) }
        assertTrue(first.contains("9.9.9.9"))
        assertTrue(!first.contains("8.8.8.8") || first.indexOf("9.9.9.9") < first.indexOf("8.8.8.8"))
        mod.stop()
        db.close()
    }

    @Test
    fun retries_dead_network_peers_with_a_longer_probe_timeout() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("1.1.1.1"))
        db.peers.upsert(peer("9.9.9.9", services = 1uL, lastProbedAt = 1))
        db.matchedBlocks.insert(MatchedBlock(10, "aa".repeat(32)))

        val timeouts = mutableMapOf<String, Long?>()
        val lock = Any()
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                now = { 100_000 },
                probeTimeoutMs = 3_000,
                probe = { host, _, call ->
                    synchronized(lock) { timeouts[host] = call.timeoutMs }
                    ProbeResult.Err("skip")
                },
                concurrency = 2,
                idleDelayMs = 20,
                minAliveCompactFilters = 0,
            ),
        )

        mod.start()
        waitFor { synchronized(lock) { timeouts.keys.containsAll(listOf("1.1.1.1", "9.9.9.9")) } }
        assertEquals(3_000L, synchronized(lock) { timeouts["1.1.1.1"] })
        assertEquals(15_000L, synchronized(lock) { timeouts["9.9.9.9"] })
        mod.stop()
        db.close()
    }

    @Test
    fun uses_short_timeout_for_dead_compact_filter_retries() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("2.2.2.2", services = 64uL, lastProbedAt = 1))
        db.peers.upsert(peer("9.9.9.9", services = 1uL, lastProbedAt = 1))
        db.matchedBlocks.insert(MatchedBlock(10, "aa".repeat(32)))

        val timeouts = mutableMapOf<String, Long?>()
        val lock = Any()
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                now = { 100_000 },
                probeTimeoutMs = 3_000,
                retryProbeTimeoutMs = 15_000,
                probe = { host, _, call ->
                    synchronized(lock) { timeouts[host] = call.timeoutMs }
                    ProbeResult.Err("skip")
                },
                concurrency = 2,
                idleDelayMs = 20,
                minAliveCompactFilters = 1,
            ),
        )

        mod.start()
        waitFor { synchronized(lock) { timeouts.keys.containsAll(listOf("2.2.2.2", "9.9.9.9")) } }
        assertEquals(3_000L, synchronized(lock) { timeouts["2.2.2.2"] })
        assertEquals(15_000L, synchronized(lock) { timeouts["9.9.9.9"] })
        mod.stop()
        db.close()
    }

    @Test
    fun does_not_retry_burned_dead_network_peers_when_blocks_need_download() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        for (i in 0 until 80) {
            db.peers.upsert(peer("10.0.0.$i"))
        }
        db.peers.upsert(
            peer("7.7.7.7", services = 1uL, lastProbedAt = 1, usedForBlocks = true),
        )
        db.peers.upsert(peer("9.9.9.9", services = 1uL, lastProbedAt = 50))
        db.matchedBlocks.insert(MatchedBlock(10, "aa".repeat(32)))

        val probed = mutableListOf<String>()
        val lock = Any()
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                now = { 100_000 },
                probeTimeoutMs = 3_000,
                probe = { host, _, _ ->
                    synchronized(lock) { probed.add(host) }
                    ProbeResult.Err("skip")
                },
                concurrency = 4,
                idleDelayMs = 20,
                minAliveCompactFilters = 0,
            ),
        )

        mod.start()
        waitFor { synchronized(lock) { probed.size >= 8 } }
        val first = synchronized(lock) { probed.take(8) }
        assertTrue(first.contains("9.9.9.9"))
        assertFalse(first.contains("7.7.7.7"))
        mod.stop()
        db.close()
    }

    @Test
    fun does_not_start_more_probes_than_concurrency_when_cf_and_block_pools_are_thin() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("2.2.2.2", services = 64uL, lastProbedAt = 1))
        db.peers.upsert(peer("3.3.3.3", services = 64uL, lastProbedAt = 1))
        db.peers.upsert(peer("4.4.4.4", services = 64uL, lastProbedAt = 1))
        for (i in 0 until 20) {
            db.peers.upsert(peer("9.9.9.$i", services = 1uL, lastProbedAt = 1))
        }
        db.matchedBlocks.insert(MatchedBlock(10, "aa".repeat(32)))

        val started = mutableListOf<String>()
        val lock = Any()
        val gate = CompletableDeferred<Unit>()
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                now = { 100_000 },
                probeTimeoutMs = 3_000,
                probe = { host, _, _ ->
                    synchronized(lock) { started.add(host) }
                    gate.await()
                    ProbeResult.Err("skip")
                },
                concurrency = 8,
                idleDelayMs = 20,
                minAliveCompactFilters = 16,
            ),
        )

        mod.start()
        waitFor { synchronized(lock) { started.size >= 8 } }
        delay(50)
        assertTrue(synchronized(lock) { started.size <= 8 })
        gate.complete(Unit)
        mod.stop()
        db.close()
    }

    @Test
    fun waits_out_retry_timeout_before_reprobing_a_dead_network_peer() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("9.9.9.9", services = 1uL, lastProbedAt = 1))
        db.matchedBlocks.insert(MatchedBlock(10, "aa".repeat(32)))

        val t = AtomicLong(100_000L)
        val probed = mutableListOf<String>()
        val lock = Any()
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                now = { t.load() },
                probeTimeoutMs = 3_000,
                retryProbeTimeoutMs = 15_000,
                probe = { host, _, _ ->
                    synchronized(lock) { probed.add(host) }
                    ProbeResult.Err("skip")
                },
                concurrency = 1,
                idleDelayMs = 20,
                minAliveCompactFilters = 0,
            ),
        )

        mod.start()
        waitFor { synchronized(lock) { probed.isNotEmpty() } }
        val afterFirst = synchronized(lock) { probed.size }
        t.store(104_000)
        delay(80)
        assertEquals(afterFirst, synchronized(lock) { probed.size })
        t.store(116_000)
        waitFor { synchronized(lock) { probed.size > afterFirst } }
        mod.stop()
        db.close()
    }
}
