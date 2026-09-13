package io.bluewallet.blueberry.peers.modules

import io.bluewallet.bip157.NODE_COMPACT_FILTERS
import io.bluewallet.bip324.Networks
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.ModuleStatus
import io.bluewallet.blueberry.bus.ModuleStatusPayload
import io.bluewallet.blueberry.bus.PeerSocketKind
import io.bluewallet.blueberry.bus.PeersSocketsPayload
import io.bluewallet.blueberry.bus.PeersUpdatedPayload
import io.bluewallet.blueberry.peers.Config
import io.bluewallet.blueberry.peers.currentTimeMillis
import io.bluewallet.blueberry.peers.log
import io.bluewallet.blueberry.peers.logError
import io.bluewallet.blueberry.peers.net.MAINNET_DNS_SEEDS
import io.bluewallet.blueberry.peers.net.PeerCandidate
import io.bluewallet.blueberry.peers.net.PlatformNet
import io.bluewallet.blueberry.peers.net.ProbeOptions
import io.bluewallet.blueberry.peers.net.ProbeResult
import io.bluewallet.blueberry.peers.net.probePeer
import io.bluewallet.blueberry.peers.net.resolveSeedPeers
import io.bluewallet.blueberry.storage.AliveServiceOptions
import io.bluewallet.blueberry.storage.Peer
import io.bluewallet.blueberry.storage.PeerWrite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.ceil
import kotlin.math.max

/** Bitcoin NODE_NETWORK — peer can serve historical blocks. */
private val NODE_NETWORK = 1uL

/** Cap for `stop()` join: wait out in-flight SQLite, not blocking DNS/connect. */
private const val STOP_JOIN_MS = 1_000L

private class DiscoveryState {
    @Volatile var stopped = true

    @Volatile var paused = false

    @Volatile var syncIdle = false
}

class ProbeCallOptions(
    val wantAddr: Boolean = false,
    val timeoutMs: Long? = null,
)

class PeersDiscoveryOptions(
    val net: PlatformNet,
    val resolveSeeds: (suspend () -> List<PeerCandidate>)? = null,
    val probe: (suspend (String, Int, ProbeCallOptions) -> ProbeResult)? = null,
    val concurrency: Int? = null,
    val idleDelayMs: Long? = null,
    val probeTimeoutMs: Long? = null,
    val addrTimeoutMs: Long? = null,
    val crawlIntervalMs: Long? = null,
    val now: (() -> Long)? = null,
    val minAliveCompactFilters: Int? = null,
    val minAliveBlockPeers: Int? = null,
    val reseedIntervalMs: Long? = null,
    val retryProbeTimeoutMs: Long? = null,
)

@OptIn(ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class)
fun createPeersDiscoveryModule(
    ctx: ModuleContext,
    options: PeersDiscoveryOptions,
): Module {
    val port = Networks.mainnet.defaultPort
    val resolveSeeds =
        options.resolveSeeds ?: {
            resolveSeedPeers(MAINNET_DNS_SEEDS, port, options.net.dns)
        }
    val probeTimeoutMs = options.probeTimeoutMs ?: Config.peerProbeTimeoutMs
    val retryProbeTimeoutMs = options.retryProbeTimeoutMs ?: Config.peerRetryProbeTimeoutMs
    val addrTimeoutMs = options.addrTimeoutMs ?: Config.peerAddrTimeoutMs
    val crawlIntervalMs = options.crawlIntervalMs ?: Config.peerCrawlIntervalMs
    val probe =
        options.probe ?: { host, p, call ->
            probePeer(
                host,
                p,
                ProbeOptions(
                    timeoutMs = call.timeoutMs ?: probeTimeoutMs,
                    addrTimeoutMs = addrTimeoutMs,
                    wantAddr = call.wantAddr,
                    connect = options.net.connect,
                ),
            )
        }
    val concurrency = options.concurrency ?: Config.peerConcurrency
    val idleDelayMs = options.idleDelayMs ?: 500L
    val now = options.now ?: { currentTimeMillis() }
    val minAliveCompactFilters = options.minAliveCompactFilters ?: 16
    val minAliveBlockPeers = options.minAliveBlockPeers ?: Config.blockConcurrency
    val reseedIntervalMs = options.reseedIntervalMs ?: 60_000L

    val state = DiscoveryState()
    var unsubIdle: (() -> Unit)? = null
    var unsubCatchup: (() -> Unit)? = null
    var unsubPeers: (() -> Unit)? = null
    var wake: (() -> Unit)? = null
    val durableWake = AtomicBoolean(false)
    var lastReseedAt = 0L
    var dnsInFlight = false
    var lastCrawlAt: Long? = null
    var crawlsInFlight = 0
    var epoch = 0
    val inflight = mutableSetOf<String>()
    var loopJob: Job? = null
    var scope: CoroutineScope? = null

    fun kick() {
        wake?.invoke()
    }

    fun durableKick() {
        durableWake.store(true)
        kick()
    }

    fun pendingBlockDownloads(): Boolean =
        ctx.db.matchedBlocks
            .listNeedingDownload(1)
            .isNotEmpty()

    fun unusedAliveNetworkCount(): Int =
        ctx.db.peers
            .listAliveWithServices(
                NODE_NETWORK,
                minAliveBlockPeers,
                AliveServiceOptions(unusedForBlocks = true),
            ).size

    fun needsBlockPeers(): Boolean = pendingBlockDownloads() && unusedAliveNetworkCount() < minAliveBlockPeers

    fun needsMoreBlockAddrs(): Boolean =
        needsBlockPeers() &&
            ctx.db.peers
                .listUnprobedWithServicesUnused(NODE_NETWORK, minAliveBlockPeers)
                .size <
            minAliveBlockPeers

    fun reseedEveryMs(): Long = if (needsBlockPeers()) Config.peerDnsReseedWhenBlocksMs else reseedIntervalMs

    fun refreshPause() {
        val wantPause =
            state.syncIdle &&
                ctx.db.peers
                    .listAlive()
                    .isNotEmpty() &&
                !pendingBlockDownloads()
        if (wantPause == state.paused) return
        state.paused = wantPause
        log("peers-discovery", if (state.paused) "pause" else "resume")
        durableKick()
    }

    fun emitUpdated() {
        ctx.bus.emit(Event.PeersUpdated, PeersUpdatedPayload(at = now()))
    }

    fun emitSockets() {
        ctx.bus.emit(
            Event.PeersSockets,
            PeersSocketsPayload(at = now(), kind = PeerSocketKind.PROBE, open = inflight.size),
        )
    }

    fun upsertCandidate(candidate: PeerCandidate) {
        ctx.db.peers.upsert(
            PeerWrite(
                host = candidate.host,
                port = candidate.port,
                services = candidate.services,
                alive = false,
                usedForBlocks = false,
                lastProbedAt = null,
            ),
        )
    }

    suspend fun waitForKick(ms: Long) {
        if (state.stopped) return
        if (durableWake.exchange(false)) return
        val done = CompletableDeferred<Unit>()
        val complete = {
            done.complete(Unit)
            Unit
        }
        wake = complete
        if (durableWake.exchange(false)) complete()
        try {
            withTimeoutOrNull(ms.coerceAtLeast(1)) { done.await() }
        } finally {
            if (wake === complete) wake = null
            durableWake.store(false)
        }
    }

    suspend fun pullSeeds() {
        if (dnsInFlight) return
        dnsInFlight = true
        try {
            val seeds = resolveSeeds()
            if (state.stopped || state.paused) return
            log("peers-discovery", "dns seeds=${seeds.size}")
            for (candidate in seeds) upsertCandidate(candidate)
            if (seeds.isNotEmpty()) {
                emitUpdated()
                kick()
            }
        } catch (err: Throwable) {
            logError("peers-discovery", "dns", err)
        } finally {
            lastReseedAt = now()
            dnsInFlight = false
        }
    }

    suspend fun bootstrap() {
        if (ctx.db.peers
                .listAlive()
                .isNotEmpty()
        ) {
            return
        }
        pullSeeds()
    }

    fun aliveCompactFilterCount(): Int =
        ctx.db.peers
            .listAliveWithServices(NODE_COMPACT_FILTERS.toULong(), minAliveCompactFilters)
            .size

    suspend fun maybeReseed() {
        if (dnsInFlight) return
        if (now() - lastReseedAt < reseedEveryMs()) return
        if (aliveCompactFilterCount() >= minAliveCompactFilters && !needsBlockPeers()) return
        pullSeeds()
    }

    fun probeTimeoutFor(peer: Peer): Long {
        val last = peer.lastProbedAt
        return if (last != null && !peer.alive && (peer.services and NODE_NETWORK) != 0uL) {
            retryProbeTimeoutMs
        } else {
            probeTimeoutMs
        }
    }

    fun takeProbeBatch(
        limit: Int,
        inflightKeys: Set<String>,
    ): List<Peer> {
        if (limit <= 0) return emptyList()
        val picked = mutableListOf<Peer>()
        val seen = inflightKeys.toMutableSet()
        val t = now()

        fun due(peer: Peer): Boolean {
            val last = peer.lastProbedAt ?: return true
            return t - last >= probeTimeoutFor(peer)
        }

        fun take(
            peers: List<Peer>,
            max: Int = limit,
        ) {
            for (peer in peers) {
                if (picked.size >= max) return
                val key = "${peer.host}:${peer.port}"
                if (key in seen) continue
                seen.add(key)
                picked.add(peer)
            }
        }
        if (aliveCompactFilterCount() < minAliveCompactFilters) {
            val cfMax = if (limit < 2) limit else ceil(limit / 2.0).toInt()
            take(
                ctx.db.peers
                    .listWithServices(
                        NODE_COMPACT_FILTERS.toULong(),
                        minAliveCompactFilters + concurrency + 32,
                    ).filter { !it.alive && due(it) },
                cfMax,
            )
        }
        if (needsBlockPeers() && picked.size < limit) {
            val retryMax = if (limit < 2) limit else ceil(limit / 2.0).toInt()
            take(
                ctx.db.peers
                    .listOldestDeadWithServices(
                        NODE_NETWORK,
                        retryMax + concurrency + 32,
                    ).filter { due(it) },
                (picked.size + retryMax).coerceAtMost(limit),
            )
        }
        if (needsBlockPeers() && picked.size < limit) {
            val nnMax = if (limit < 2) limit else ceil(limit / 2.0).toInt()
            take(
                ctx.db.peers.listUnprobedWithServicesUnused(
                    NODE_NETWORK,
                    nnMax + concurrency + 32,
                ),
                (picked.size + nnMax).coerceAtMost(limit),
            )
        }
        if (picked.size < limit) {
            take(
                ctx.db.peers
                    .listProbeQueue(concurrency + inflightKeys.size + 16)
                    .filter { due(it) },
            )
        }
        return picked
    }

    suspend fun runLoop() {
        while (!state.stopped) {
            if (state.paused) {
                waitForKick(60_000)
                continue
            }
            val moduleScope = scope ?: break
            moduleScope.launch { runCatching { maybeReseed() } }
            val inflightKeys = inflight.toSet()
            val batch = takeProbeBatch(concurrency - inflightKeys.size, inflightKeys)
            val huntAddrs = needsMoreBlockAddrs()
            val addrBudget = if (huntAddrs) max(2, concurrency / 2) else 1
            val addrEveryMs = if (huntAddrs) 0L else crawlIntervalMs
            var spawned = 0
            for (next in batch) {
                if (state.stopped || state.paused) break
                val key = "${next.host}:${next.port}"
                inflight.add(key)
                spawned++
                val crawlable = next.lastProbedAt == null || next.alive
                val wantAddr =
                    !state.paused &&
                        crawlable &&
                        crawlsInFlight < addrBudget &&
                        (lastCrawlAt == null || now() - lastCrawlAt!! >= addrEveryMs)
                if (wantAddr) crawlsInFlight++
                val timeoutMs = probeTimeoutFor(next)
                val spawnedEpoch = epoch
                moduleScope.launch {
                    try {
                        val result =
                            probe(
                                next.host,
                                next.port,
                                ProbeCallOptions(wantAddr = wantAddr, timeoutMs = timeoutMs),
                            )
                        if (state.stopped || spawnedEpoch != epoch) return@launch
                        val probedAt = now()
                        ctx.db.transaction {
                            ctx.db.peers.markProbed(next.host, next.port, probedAt)
                            if (result is ProbeResult.Ok) {
                                ctx.db.peers.upsert(
                                    PeerWrite(
                                        host = next.host,
                                        port = next.port,
                                        services = result.services,
                                        alive = true,
                                        usedForBlocks = false,
                                        lastProbedAt = probedAt,
                                    ),
                                )
                                for (p in result.peers) upsertCandidate(p)
                                ctx.db.peers.markAlive(next.host, next.port, true)
                            } else {
                                ctx.db.peers.markAlive(next.host, next.port, false)
                            }
                        }
                        if (result is ProbeResult.Err) {
                            log("peers-discovery", "probe fail $key error=${result.error}")
                        }
                        if (wantAddr) {
                            val n = if (result is ProbeResult.Ok) result.peers.size else 0
                            log("peers-discovery", "crawl source=$key addrs=$n")
                        }
                        emitUpdated()
                    } catch (err: CancellationException) {
                        if (state.stopped || spawnedEpoch != epoch) return@launch
                        throw err
                    } catch (err: Throwable) {
                        if (state.stopped || spawnedEpoch != epoch) return@launch
                        logError("peers-discovery", "probe fail $key", err)
                        ctx.db.peers.markProbed(next.host, next.port, now())
                        ctx.db.peers.markAlive(next.host, next.port, false)
                        emitUpdated()
                    } finally {
                        if (wantAddr && spawnedEpoch == epoch) {
                            crawlsInFlight = (crawlsInFlight - 1).coerceAtLeast(0)
                            lastCrawlAt = now()
                        }
                        if (state.stopped || spawnedEpoch != epoch) return@launch
                        inflight.remove(key)
                        emitSockets()
                        kick()
                    }
                }
            }
            if (spawned > 0) emitSockets()
            if (state.stopped) break
            val inflightSize = inflight.size
            if (inflightSize >= concurrency || spawned == 0) {
                val waitMs = if (inflightSize > 0) max(probeTimeoutMs, idleDelayMs) else idleDelayMs
                waitForKick(waitMs)
            } else {
                waitForKick(1)
            }
            yield()
        }
    }

    return object : Module {
        override val name: String = "peers-discovery"

        override suspend fun start() {
            if (!state.stopped) return
            ctx.bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(module = "peers-discovery", status = ModuleStatus.STARTING),
            )
            log("peers-discovery", "start")
            state.stopped = false
            val job = SupervisorJob()
            loopJob = job
            val serial = Dispatchers.Default.limitedParallelism(1)
            val moduleScope = CoroutineScope(job + serial)
            scope = moduleScope
            unsubIdle =
                ctx.bus.on(Event.SyncIdle) {
                    state.syncIdle = true
                    refreshPause()
                }
            unsubCatchup =
                ctx.bus.on(Event.SyncCatchup) {
                    state.syncIdle = false
                    refreshPause()
                }
            unsubPeers =
                ctx.bus.on(Event.PeersUpdated) {
                    if (state.syncIdle) refreshPause()
                }
            moduleScope.launch { runCatching { bootstrap() } }
            detachLoop(ctx, "peers-discovery", moduleScope.launch { runLoop() })
            ctx.bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(module = "peers-discovery", status = ModuleStatus.RUNNING),
            )
        }

        override fun stop() {
            if (state.stopped) return
            state.stopped = true
            log("peers-discovery", "stop")
            kick()
            val toCancel = loopJob
            loopJob = null
            scope = null
            toCancel?.cancel()
            if (toCancel != null) {
                runBlocking { withTimeoutOrNull(STOP_JOIN_MS) { toCancel.join() } }
            }
            unsubIdle?.invoke()
            unsubCatchup?.invoke()
            unsubPeers?.invoke()
            unsubIdle = null
            unsubCatchup = null
            unsubPeers = null
            state.paused = false
            state.syncIdle = false
            epoch++
            crawlsInFlight = 0
            lastCrawlAt = null
            inflight.clear()
            emitSockets()
            ctx.bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(module = "peers-discovery", status = ModuleStatus.STOPPED),
            )
        }
    }
}
