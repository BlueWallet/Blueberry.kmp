package io.bluewallet.blueberry.blocks.modules

import io.bluewallet.bip324.assertBlockPayload
import io.bluewallet.bip324.encodeBlock
import io.bluewallet.bip324.hexToBytes
import io.bluewallet.blueberry.bus.BlocksProgressPayload
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.ModuleStatus
import io.bluewallet.blueberry.bus.ModuleStatusPayload
import io.bluewallet.blueberry.bus.PeerSocketKind
import io.bluewallet.blueberry.bus.PeersSocketsPayload
import io.bluewallet.blueberry.headers.internalHexToDisplayHex
import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.peers.Config
import io.bluewallet.blueberry.peers.log
import io.bluewallet.blueberry.peers.modules.Module
import io.bluewallet.blueberry.peers.modules.ModuleContext
import io.bluewallet.blueberry.peers.modules.detachLoop
import io.bluewallet.blueberry.peers.net.BlockBatchResult
import io.bluewallet.blueberry.peers.net.BlockSessionApi
import io.bluewallet.blueberry.peers.net.BlockSyncOptions
import io.bluewallet.blueberry.peers.net.PlatformNet
import io.bluewallet.blueberry.peers.net.openBlockSession
import io.bluewallet.blueberry.storage.AliveServiceOptions
import io.bluewallet.blueberry.storage.DownloadedBlock
import io.bluewallet.blueberry.storage.MatchedBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.math.max

/** Bitcoin NODE_NETWORK — peer can serve historical blocks. */
private const val NODE_NETWORK = 1uL

private const val UI_MIN_MS = 100L
private const val PEER_COOL_MS = 3_000L

/** Poll while pending work remains but peers are scarce. */
private const val PEER_WAIT_MS = 1_000L

class BlocksDownloadOptions(
    val net: PlatformNet,
    val openSession: (suspend (String, Int, BlockSyncOptions) -> BlockBatchResult<BlockSessionApi>)? = null,
    val connectTimeoutMs: Long? = null,
    val syncTimeoutMs: Long? = null,
    val concurrency: Int? = null,
    val idleDelayMs: Long? = null,
    val now: (() -> Long)? = null,
    val onDownloadRun: (() -> Unit)? = null,
    val log: ((String) -> Unit)? = null,
)

private data class PeerRef(
    val host: String,
    val port: Int,
)

private fun peerKey(peer: PeerRef): String = "${peer.host}:${peer.port}"

private fun formatError(err: Throwable): String = err.message ?: err.toString()

@OptIn(ExperimentalAtomicApi::class)
fun createBlocksDownloadModule(
    ctx: ModuleContext,
    options: BlocksDownloadOptions,
): Module {
    val openSession = options.openSession ?: { host, port, opts -> openBlockSession(host, port, opts) }
    val connectTimeoutMs = options.connectTimeoutMs ?: Config.blockConnectTimeoutMs
    val syncTimeoutMs = options.syncTimeoutMs ?: Config.blockSyncTimeoutMs
    val concurrency = max(1, options.concurrency ?: Config.blockConcurrency)
    val idleDelayMs = max(0L, options.idleDelayMs ?: 500L)
    val now = options.now ?: { nowMillis() }
    val diagnosticLog = options.log ?: { message -> log("blocks-download", message) }

    val stopped = AtomicBoolean(true)
    val quiet = AtomicBoolean(false)
    val lastEmitAt = AtomicLong(0)
    val lastQueueDiagnostic = AtomicReference("")
    val attemptSequence = AtomicInt(0)
    val waiters = AtomicReference<List<CompletableDeferred<Unit>>>(emptyList())

    val lock = Mutex()
    val leasedPeers = mutableSetOf<String>()
    val peerCoolUntil = mutableMapOf<String, Long>()
    val inFlight = mutableMapOf<Int, Job>()

    var unsubMatch: (() -> Unit)? = null
    var unsubPeers: (() -> Unit)? = null
    var unsubIdle: (() -> Unit)? = null
    var unsubCatchup: (() -> Unit)? = null
    var loopJob: Job? = null
    var parentJob: Job? = null
    var downloadScope: CoroutineScope? = null

    fun isStopped() = stopped.load()

    fun kick() {
        val current = waiters.exchange(emptyList())
        for (wake in current) wake.complete(Unit)
    }

    suspend fun waitForKick(ms: Long) {
        if (isStopped()) return
        val done = CompletableDeferred<Unit>()
        while (true) {
            val cur = waiters.load()
            if (waiters.compareAndSet(cur, cur + done)) break
        }
        try {
            if (isStopped()) return
            withTimeout(ms) { done.await() }
        } catch (_: TimeoutCancellationException) {
        } finally {
            while (true) {
                val cur = waiters.load()
                val next = cur - done
                if (next === cur || waiters.compareAndSet(cur, next)) break
            }
        }
    }

    fun emitProgress(force: Boolean = false) {
        val t = now()
        if (!force && t - lastEmitAt.load() < UI_MIN_MS) return
        lastEmitAt.store(t)
        ctx.bus.emit(
            Event.BlocksProgress,
            BlocksProgressPayload(
                at = t,
                downloaded = ctx.db.blocks.count(),
                matched = ctx.db.matchedBlocks.count(),
            ),
        )
    }

    suspend fun emitSockets() {
        val open = lock.withLock { inFlight.size }
        ctx.bus.emit(
            Event.PeersSockets,
            PeersSocketsPayload(at = now(), kind = PeerSocketKind.BLK, open = open),
        )
    }

    fun pruneCooldownsLocked() {
        val t = now()
        val stale = peerCoolUntil.filter { it.value <= t }.keys
        for (key in stale) peerCoolUntil.remove(key)
    }

    suspend fun leasePeer(): PeerRef? =
        lock.withLock {
            pruneCooldownsLocked()
            val candidates =
                ctx.db.peers
                    .listAliveWithServices(NODE_NETWORK, 512, AliveServiceOptions(unusedForBlocks = true))
                    .filter { p ->
                        val key = peerKey(PeerRef(p.host, p.port))
                        !leasedPeers.contains(key) && !peerCoolUntil.containsKey(key)
                    }
            if (candidates.isEmpty()) return@withLock null
            val peer = PeerRef(candidates[0].host, candidates[0].port)
            leasedPeers.add(peerKey(peer))
            peer
        }

    suspend fun coolPeer(peer: PeerRef) {
        lock.withLock { peerCoolUntil[peerKey(peer)] = now() + PEER_COOL_MS }
    }

    suspend fun downloadOne(
        job: MatchedBlock,
        peer: PeerRef,
    ): Boolean {
        val startedAt = now()
        val attempt = attemptSequence.incrementAndFetch()
        var session: BlockSessionApi? = null
        var phase = "session"
        try {
            diagnosticLog("block start attempt=$attempt peer=${peerKey(peer)}")
            val opened =
                openSession(
                    peer.host,
                    peer.port,
                    BlockSyncOptions(
                        connect = options.net.connect,
                        connectTimeoutMs = connectTimeoutMs,
                        syncTimeoutMs = syncTimeoutMs,
                    ),
                )
            if (opened is BlockBatchResult.Err) {
                coolPeer(peer)
                diagnosticLog(
                    "session open failure attempt=$attempt peer=${peerKey(
                        peer,
                    )} elapsedMs=${max(0, now() - startedAt)} cooldownMs=$PEER_COOL_MS error=${opened.error}",
                )
                return false
            }
            session = (opened as BlockBatchResult.Ok).value
            phase = "download"
            val hashInternal = hexToBytes(job.blockHashInternalHex)
            val payload = session.getBlock(hashInternal)
            phase = "validate"
            val hashDisplay = internalHexToDisplayHex(job.blockHashInternalHex)
            assertBlockPayload(payload, hashDisplay)
            val blockBytes = encodeBlock(payload)
            phase = "persist"
            val inserted =
                ctx.db.blocks.insertIfMatched(
                    DownloadedBlock(job.height, job.blockHashInternalHex, blockBytes),
                )
            val stored = if (inserted) null else ctx.db.blocks.get(job.height)
            if (!inserted && stored?.blockHashInternalHex != job.blockHashInternalHex) {
                diagnosticLog(
                    "block discarded stale attempt=$attempt peer=${peerKey(
                        peer,
                    )} height=${job.height} elapsedMs=${max(0, now() - startedAt)}",
                )
                return false
            }
            if (inserted) {
                ctx.db.peers.markUsedForBlocks(peer.host, peer.port)
                emitProgress(true)
            }
            diagnosticLog(
                "block success attempt=$attempt peer=${peerKey(peer)} bytes=${blockBytes.size} elapsedMs=${max(0, now() - startedAt)}",
            )
            return true
        } catch (err: CancellationException) {
            throw err
        } catch (err: Throwable) {
            coolPeer(peer)
            diagnosticLog(
                "block failure attempt=$attempt peer=${peerKey(
                    peer,
                )} phase=$phase elapsedMs=${max(0, now() - startedAt)} cooldownMs=$PEER_COOL_MS error=${formatError(err)}",
            )
            return false
        } finally {
            if (session != null) {
                try {
                    withContext(NonCancellable) { session.close() }
                } catch (_: Throwable) {
                }
            }
        }
    }

    suspend fun launchDownload(
        job: MatchedBlock,
        peer: PeerRef,
    ) {
        val scope = downloadScope ?: return
        val task =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    downloadOne(job, peer)
                } finally {
                    withContext(NonCancellable) {
                        lock.withLock {
                            leasedPeers.remove(peerKey(peer))
                            inFlight.remove(job.height)
                        }
                        emitSockets()
                    }
                }
            }
        lock.withLock { inFlight[job.height] = task }
        task.start()
        emitSockets()
    }

    suspend fun snapshotInFlight(): List<Job> = lock.withLock { inFlight.values.toList() }

    suspend fun inFlightSize(): Int = lock.withLock { inFlight.size }

    suspend fun inFlightHeights(): Set<Int> = lock.withLock { inFlight.keys.toSet() }

    suspend fun leasedAndCoolingCounts(): Pair<Int, Int> =
        lock.withLock {
            leasedPeers.size to peerCoolUntil.size
        }

    suspend fun waitForAnyInFlightOrKick() {
        val jobs = snapshotInFlight()
        val done = CompletableDeferred<Unit>()
        val scope = downloadScope ?: return
        val watchers =
            jobs.map { job ->
                scope.launch {
                    job.join()
                    done.complete(Unit)
                }
            }
        val kickWaiter =
            scope.launch {
                waitForKick(PEER_WAIT_MS)
                done.complete(Unit)
            }
        try {
            done.await()
        } finally {
            watchers.forEach { it.cancel() }
            kickWaiter.cancel()
        }
    }

    suspend fun loop() {
        options.onDownloadRun?.invoke()
        emitProgress(true)
        while (!isStopped()) {
            val flying = inFlightHeights()
            val pending =
                ctx.db.matchedBlocks
                    .listNeedingDownload(concurrency)
                    .filter { !flying.contains(it.height) }

            if (pending.isEmpty() && inFlightSize() == 0) {
                lastQueueDiagnostic.store("")
                waitForKick(idleDelayMs)
                continue
            }

            var starved = false
            for (job in pending) {
                if (isStopped()) break
                if (inFlightSize() >= concurrency) break
                val peer = leasePeer()
                if (peer == null) {
                    starved = true
                    break
                }
                launchDownload(job, peer)
            }

            if (inFlightSize() == 0) {
                // Kotlin can finish a download before this check. helix3 never
                // sees that — the Promise stays in inFlight until the next turn.
                // Only wait on peers when we actually failed to lease one.
                if (!starved) {
                    emitProgress()
                    delay(1)
                    continue
                }
                val counts = leasedAndCoolingCounts()
                val message =
                    "queue stalled pending=${pending.size} inFlight=0 " +
                        "leasedPeers=${counts.first} coolingPeers=${counts.second}"
                if (message != lastQueueDiagnostic.load()) {
                    diagnosticLog(message)
                    lastQueueDiagnostic.store(message)
                }
                waitForKick(PEER_WAIT_MS)
                continue
            }

            lastQueueDiagnostic.store("")
            waitForAnyInFlightOrKick()
            emitProgress()
            delay(1)
        }

        val leftover = snapshotInFlight()
        leftover.forEach { it.join() }
    }

    return object : Module {
        override val name = "blocks-download"

        override suspend fun start() {
            if (!isStopped()) return
            stopped.store(false)
            quiet.store(false)
            diagnosticLog(
                "module start concurrency=$concurrency connectTimeoutMs=$connectTimeoutMs syncTimeoutMs=$syncTimeoutMs",
            )
            ctx.bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(module = "blocks-download", status = ModuleStatus.STARTING),
            )
            emitProgress(true)
            unsubMatch = ctx.bus.on(Event.FiltersMatch) { kick() }
            unsubIdle = ctx.bus.on(Event.SyncIdle) { quiet.store(true) }
            unsubCatchup =
                ctx.bus.on(Event.SyncCatchup) {
                    quiet.store(false)
                    kick()
                }
            unsubPeers =
                ctx.bus.on(Event.PeersUpdated) {
                    if (quiet.load()) return@on
                    kick()
                }
            val job = SupervisorJob()
            parentJob = job
            val scope = CoroutineScope(job + Dispatchers.Default)
            downloadScope = scope
            val launched =
                scope.launch {
                    if (isStopped()) return@launch
                    loop()
                }
            loopJob = launched
            detachLoop(ctx, "blocks-download", launched)
            ctx.bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(module = "blocks-download", status = ModuleStatus.RUNNING),
            )
        }

        override fun stop() {
            if (isStopped()) return
            stopped.store(true)
            unsubMatch?.invoke()
            unsubMatch = null
            unsubIdle?.invoke()
            unsubIdle = null
            unsubCatchup?.invoke()
            unsubCatchup = null
            unsubPeers?.invoke()
            unsubPeers = null
            kick()
            parentJob?.cancel()
            runBlocking {
                parentJob?.join()
                lock.withLock {
                    leasedPeers.clear()
                    inFlight.clear()
                }
                emitSockets()
            }
            loopJob = null
            parentJob = null
            downloadScope = null
            ctx.bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(module = "blocks-download", status = ModuleStatus.STOPPED),
            )
            diagnosticLog("module stopped")
        }
    }
}
