package io.bluewallet.blueberry.headers.modules

import io.bluewallet.blueberry.bus.BlocksProgressPayload
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.HeadersProgressPayload
import io.bluewallet.blueberry.bus.ModuleStatus
import io.bluewallet.blueberry.bus.ModuleStatusPayload
import io.bluewallet.blueberry.bus.PeerSocketKind
import io.bluewallet.blueberry.bus.PeersSocketsPayload
import io.bluewallet.blueberry.bus.PeersUpdatedPayload
import io.bluewallet.blueberry.bus.WalletTxsPayload
import io.bluewallet.blueberry.headers.BLUEBERRY_HEADER_CONSENSUS
import io.bluewallet.blueberry.headers.TRUSTED_CHAIN_WINDOW
import io.bluewallet.blueberry.headers.currentTimeMillis
import io.bluewallet.blueberry.headers.trustedChainFromStored
import io.bluewallet.blueberry.peers.Config
import io.bluewallet.blueberry.peers.log
import io.bluewallet.blueberry.peers.logError
import io.bluewallet.blueberry.peers.modules.Module
import io.bluewallet.blueberry.peers.modules.ModuleContext
import io.bluewallet.blueberry.peers.modules.detachLoop
import io.bluewallet.blueberry.peers.net.HeaderBatchResult
import io.bluewallet.blueberry.peers.net.HeaderFetchOptions
import io.bluewallet.blueberry.peers.net.HeaderSessionPool
import io.bluewallet.blueberry.peers.net.PlatformNet
import io.bluewallet.blueberry.peers.net.SESSION_BUSY_ERROR
import io.bluewallet.blueberry.peers.net.createHeaderSessionPool
import io.bluewallet.blueberry.storage.HeaderRecord as DbHeaderRecord
import io.bluewallet.blueberry.storage.HeaderWrite
import io.bluewallet.blueberry.wallet.maybeFreezeWalletBirthday
import io.bluewallet.headers.BlockHeader
import io.bluewallet.headers.HeaderBranchBuilder
import io.bluewallet.headers.HeaderConsensusError
import io.bluewallet.headers.HeaderConsensusParams
import io.bluewallet.headers.HeaderRecord
import io.bluewallet.headers.ValidatedHeaderBranch
import io.bluewallet.headers.ValidatedHeaderChain
import io.bluewallet.headers.bytesToHex
import io.bluewallet.headers.decodeBlockHeader
import io.bluewallet.headers.headerHashInternal
import io.bluewallet.headers.hexToBytes
import io.bluewallet.headers.storedHeaderFromBlockHeader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.Volatile
import kotlin.math.max

class ChainHeadersOptions(
    val net: PlatformNet,
    val fetchBatch: (suspend (String, Int, HeaderFetchOptions) -> HeaderBatchResult)? = null,
    val connectTimeoutMs: Long? = null,
    val headersTimeoutMs: Long? = null,
    val racePeers: Int? = null,
    val pollIntervalMs: Long? = null,
    val consensus: HeaderConsensusParams? = null,
    val now: (() -> Long)? = null,
    val nowSeconds: (() -> Long)? = null,
)

private data class PeerRef(val host: String, val port: Int)

private fun checkpointSeedFromConsensus(consensus: HeaderConsensusParams): DbHeaderRecord {
    val header = decodeBlockHeader(consensus.checkpoint.headerBytes)
    val hashInternal = headerHashInternal(header)
    return DbHeaderRecord(
        height = consensus.checkpoint.height.toInt(),
        hashInternalHex = bytesToHex(hashInternal),
        header = consensus.checkpoint.headerBytes.copyOf(),
    )
}

private fun peerKey(host: String, port: Int) = "$host:$port"

private fun buildLocatorHashes(
    ctx: ModuleContext,
    tipHeight: Int,
    tipHashInternalHex: String,
    checkpointHeight: Int,
): List<ByteArray> {
    val hashesNewestFirst = ArrayList<ByteArray>()
    hashesNewestFirst.add(hexToBytes(tipHashInternalHex))
    var step = 1
    var height = tipHeight - 1
    while (height >= checkpointHeight && hashesNewestFirst.size < 32) {
        val row = ctx.db.headers.get(height)
        if (row != null) hashesNewestFirst.add(hexToBytes(row.hashInternalHex))
        height -= step
        if (hashesNewestFirst.size > 10) step *= 2
    }
    val checkpoint = ctx.db.headers.get(checkpointHeight)
    if (checkpoint != null && checkpointHeight < tipHeight) {
        val hex = checkpoint.hashInternalHex
        val hasCheckpoint = hashesNewestFirst.any { bytesToHex(it) == hex }
        if (!hasCheckpoint) {
            if (hashesNewestFirst.size >= 32) hashesNewestFirst.removeAt(hashesNewestFirst.lastIndex)
            hashesNewestFirst.add(hexToBytes(hex))
        }
    }
    return hashesNewestFirst
}

private fun persistBranch(
    ctx: ModuleContext,
    branch: ValidatedHeaderBranch,
    mode: String,
    ancestorHeight: Int,
) {
    val writes = branch.headers.map { record ->
        HeaderWrite(
            height = record.height.toInt(),
            hashInternalHex = record.hashInternalHex,
            header = hexToBytes(record.headerHex),
            cumulativeWork = branch.cumulativeWorkByHeight.getValue(record.height),
        )
    }
    if (mode == "append") {
        ctx.db.headers.append(writes)
    } else {
        ctx.db.transaction {
            ctx.db.rewindAfter(ancestorHeight)
            ctx.db.headers.replaceAfter(ancestorHeight, writes)
        }
        val at = currentTimeMillis()
        ctx.bus.emit(Event.WalletTxs, WalletTxsPayload(at))
        ctx.bus.emit(
            Event.BlocksProgress,
            BlocksProgressPayload(at, ctx.db.blocks.count(), ctx.db.matchedBlocks.count()),
        )
    }
    val tipHeight = writes.last().height
    log("chain-headers", "$mode after=$ancestorHeight tip=$tipHeight n=${writes.size}")
}

private fun chainAfterBranch(
    base: ValidatedHeaderChain,
    branch: ValidatedHeaderBranch,
): ValidatedHeaderChain {
    val ancestor = branch.commonAncestorHeight
    val headers = ArrayList<HeaderRecord>()
    val byHeight = LinkedHashMap<Long, HeaderRecord>()
    val heightByHashInternal = LinkedHashMap<String, Long>()
    val entriesByHeight = base.entriesByHeight.filterKeys { it <= ancestor }.toMutableMap()
    val cumulativeWorkByHeight = base.cumulativeWorkByHeight.filterKeys { it <= ancestor }.toMutableMap()

    for (record in base.headers) {
        if (record.height > ancestor) break
        headers.add(record)
        byHeight[record.height] = record
        heightByHashInternal[record.hashInternalHex] = record.height
    }
    for (record in branch.headers) {
        headers.add(record)
        byHeight[record.height] = record
        heightByHashInternal[record.hashInternalHex] = record.height
    }
    entriesByHeight.putAll(branch.entriesByHeight)
    cumulativeWorkByHeight.putAll(branch.cumulativeWorkByHeight)

    return ValidatedHeaderChain(
        headers = headers,
        tipHeight = branch.tipHeight,
        tipHashInternal = branch.tipHashInternal.copyOf(),
        tipHashDisplay = branch.tipHashDisplay,
        chainWork = branch.chainWork,
        params = base.params,
        byHeight = byHeight,
        heightByHashInternal = heightByHashInternal,
        entriesByHeight = entriesByHeight,
        cumulativeWorkByHeight = cumulativeWorkByHeight,
    )
}

private sealed class ApplyResult {
    data class Applied(val chain: ValidatedHeaderChain) : ApplyResult()
    data object NothingNew : ApplyResult()
    data object Weaker : ApplyResult()
}

private fun applyHeaderBatch(
    ctx: ModuleContext,
    headers: List<BlockHeader>,
    base: ValidatedHeaderChain,
    consensus: HeaderConsensusParams,
    nowSeconds: () -> Long,
    loadChainThrough: (Int) -> ValidatedHeaderChain,
): ApplyResult {
    if (headers.isEmpty()) return ApplyResult.NothingNew

    val prevHex = bytesToHex(headers[0].previousBlockHash)
    var ancestorHeight = base.heightByHashInternal[prevHex]
    if (ancestorHeight == null) {
        val fromDb = ctx.db.headers.heightForHashInternal(prevHex) ?: throw HeaderConsensusError(
            consensus.checkpoint.height + 1,
            "batch does not link to known chain",
        )
        ancestorHeight = fromDb.toLong()
    }

    val needFrom = max(
        consensus.checkpoint.height,
        ancestorHeight - max(consensus.retargetInterval, consensus.medianTimeSpan),
    )
    val chain =
        if (base.entriesByHeight.containsKey(ancestorHeight) && base.entriesByHeight.containsKey(needFrom)) {
            base
        } else {
            loadChainThrough(ancestorHeight.toInt())
        }

    val builder = HeaderBranchBuilder(chain, ancestorHeight, nowSeconds())
    val records = headers.mapIndexed { i, h ->
        storedHeaderFromBlockHeader(ancestorHeight + 1 + i, h)
    }
    builder.append(records)
    val branch = builder.finish()
    if (branch.headers.isEmpty()) return ApplyResult.NothingNew
    if (ancestorHeight == base.tipHeight) {
        persistBranch(ctx, branch, "append", ancestorHeight.toInt())
    } else if (branch.chainWork > base.chainWork) {
        persistBranch(ctx, branch, "replace", ancestorHeight.toInt())
    } else {
        return ApplyResult.Weaker
    }
    return ApplyResult.Applied(chainAfterBranch(chain, branch))
}

private data class RaceWinner(val peer: PeerRef, val result: HeaderBatchResult.Ok)

private data class RaceOutcome(
    val winner: RaceWinner?,
    val failed: List<PeerRef>,
    val busyOnly: Boolean,
)

private class HeadersState {
    @Volatile var stopped = true
    @Volatile var quiet = false
    @Volatile var waitingForPeers = false
}

/**
 * WHY: the pool rarely hits the 20-watcher cap (device: a handful stay up).
 * Napping only when isFull() therefore never naps, and the loop races
 * getheaders every 100ms — worse than the old 30s poll.
 * HOW: short wait only with zero live watchers (offline recover). Any live
 * watcher takes the 10min health check; inv hints and last-socket drop still wake.
 */
internal const val HEADER_WATCHER_FILL_MS = 100L

internal fun atTipWaitMs(pollIntervalMs: Long, hasLiveWatchers: Boolean): Long =
    if (hasLiveWatchers) pollIntervalMs else HEADER_WATCHER_FILL_MS

/**
 * WHY: SyncIdle sets quiet so a chatty peer DB does not spin the loop.
 * HOW: still wake when we have no live hdr sockets — that is recover-from-offline,
 * not chatter. Tests inject fetchBatch (no pool) and pass hasLiveWatchers=true
 * so their quiet behavior stays the old "ignore PeersUpdated" path.
 */
internal fun ignoreQuietPeerKick(
    quiet: Boolean,
    waitingForPeers: Boolean,
    hasLiveWatchers: Boolean,
): Boolean = quiet && !waitingForPeers && hasLiveWatchers

/** WHY: last hdr FIN must not leave us in the 10min wait with hdr=0. */
internal fun lostLastWatcher(prevOpen: Int, nextOpen: Int): Boolean =
    prevOpen > 0 && nextOpen == 0

@OptIn(ExperimentalAtomicApi::class)
fun createChainHeadersModule(
    ctx: ModuleContext,
    options: ChainHeadersOptions,
): Module {
    val connectTimeoutMs = options.connectTimeoutMs ?: Config.peerProbeTimeoutMs
    val headersTimeoutMs = options.headersTimeoutMs ?: Config.headerSyncTimeoutMs
    val racePeers = max(1, options.racePeers ?: Config.headerRacePeers)
    val pollIntervalMs = options.pollIntervalMs ?: Config.headerIdleCheckMs
    val consensus = options.consensus ?: BLUEBERRY_HEADER_CONSENSUS
    val checkpointHeight = consensus.checkpoint.height.toInt()
    val now = options.now ?: { currentTimeMillis() }
    val nowSeconds = options.nowSeconds ?: { currentTimeMillis() / 1_000 }

    var hdrOpen = 0
    var onAdvance: () -> Unit = {}

    fun emitSockets(open: Int) {
        val lostLast = lostLastWatcher(hdrOpen, open)
        hdrOpen = open
        ctx.bus.emit(Event.PeersSockets, PeersSocketsPayload(now(), PeerSocketKind.HDR, open))
        // HOW: pool already dropped the socket; durableKick aborts the 10min nap.
        if (lostLast) onAdvance()
    }
    val pool: HeaderSessionPool? =
        if (options.fetchBatch != null) {
            null
        } else {
            createHeaderSessionPool(
                io.bluewallet.blueberry.peers.net.HeaderSessionPoolOptions(
                    connect = options.net.connect,
                    connectTimeoutMs = connectTimeoutMs,
                    headersTimeoutMs = headersTimeoutMs,
                    onOpenCount = ::emitSockets,
                    onTipHint = { onAdvance() },
                ),
            )
        }
    val fetchBatch = options.fetchBatch ?: { host, port, opts -> pool!!.fetchBatch(host, port, opts) }

    val state = HeadersState()
    var unsubIdle: (() -> Unit)? = null
    var unsubCatchup: (() -> Unit)? = null
    var unsubPeers: (() -> Unit)? = null
    var wake: (() -> Unit)? = null
    val durableWake = AtomicBoolean(false)
    var parentJob: Job? = null
    var moduleScope: CoroutineScope? = null
    var loopJob: Job? = null
    var maxPeerStartHeight = 0
    var peerIndex = 0
    var sticky: PeerRef? = null
    var chain: ValidatedHeaderChain? = null

    fun pickRacePeers(alive: List<PeerRef>, ignore: Set<String>): List<PeerRef> {
        val n = minOf(racePeers, alive.size)
        val picked = ArrayList<PeerRef>()
        val seen = HashSet<String>()

        fun push(peer: PeerRef) {
            val key = peerKey(peer.host, peer.port)
            if (seen.contains(key) || ignore.contains(key)) return
            if (pool?.isBusy(peer.host, peer.port) == true) return
            seen.add(key)
            picked.add(peer)
        }

        val stick = sticky
        if (stick != null && alive.any { it.host == stick.host && it.port == stick.port }) {
            push(stick)
        }
        if (pool != null) {
            for (peer in alive) {
                if (picked.size >= n) break
                if (pool.has(peer.host, peer.port)) push(peer)
            }
        }
        var i = 0
        while (i < alive.size && picked.size < n) {
            val peer = alive[(peerIndex + i) % alive.size]
            if (pool != null && pool.isFull() && !pool.has(peer.host, peer.port)) {
                i++
                continue
            }
            push(peer)
            i++
        }
        return picked
    }

    suspend fun raceHeaderFetch(
        peers: List<PeerRef>,
        locatorHashes: List<ByteArray>,
    ): RaceOutcome {
        if (peers.isEmpty()) return RaceOutcome(null, emptyList(), false)
        val scope = moduleScope ?: return RaceOutcome(null, emptyList(), false)
        val done = CompletableDeferred<RaceOutcome>()
        val lock = Mutex()
        val failed = ArrayList<PeerRef>()
        var pending = peers.size
        var hardFails = 0
        var emptyWinner: RaceWinner? = null

        fun finish(value: RaceOutcome) {
            done.complete(value)
        }

        suspend fun onSettledPeer() {
            val outcome = lock.withLock {
                pending--
                if (pending != 0) null
                else if (emptyWinner != null) RaceOutcome(emptyWinner, failed.toList(), false)
                else RaceOutcome(null, failed.toList(), hardFails == 0)
            }
            if (outcome != null) finish(outcome)
        }

        for (peer in peers) {
            scope.launch {
                try {
                    val result = fetchBatch(
                        peer.host,
                        peer.port,
                        HeaderFetchOptions(
                            locatorHashes = locatorHashes,
                            connectTimeoutMs = connectTimeoutMs,
                            headersTimeoutMs = headersTimeoutMs,
                        ),
                    )
                    if (done.isCompleted) {
                        // Race already timed out and the loop may be in the 10min
                        // at-tip nap. A late non-empty batch is the missed tip —
                        // onAdvance aborts the nap. Empty leftovers stay ignored.
                        if (result is HeaderBatchResult.Ok && result.headers.isNotEmpty()) {
                            onAdvance()
                        }
                        return@launch
                    }
                    when (result) {
                        is HeaderBatchResult.Ok -> {
                            if (result.headers.isNotEmpty()) {
                                lock.withLock {
                                    finish(RaceOutcome(RaceWinner(peer, result), failed.toList(), false))
                                }
                            } else {
                                lock.withLock { if (emptyWinner == null) emptyWinner = RaceWinner(peer, result) }
                                onSettledPeer()
                            }
                        }
                        is HeaderBatchResult.Err -> {
                            if (result.error == SESSION_BUSY_ERROR) {
                                onSettledPeer()
                            } else {
                                log(
                                    "chain-headers",
                                    "peer fail ${peerKey(peer.host, peer.port)} error=${result.error}",
                                )
                                lock.withLock {
                                    hardFails++
                                    failed.add(peer)
                                }
                                onSettledPeer()
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (done.isCompleted) return@launch
                    logError("chain-headers", "peer fail ${peerKey(peer.host, peer.port)}", e)
                    lock.withLock {
                        hardFails++
                        failed.add(peer)
                    }
                    onSettledPeer()
                }
            }
        }
        return try {
            withTimeout(headersTimeoutMs) { done.await() }
        } catch (_: TimeoutCancellationException) {
            // Complete `done` so a leftover non-empty Ok takes the late-kick
            // path. If that leftover already finish()'d, use its winner.
            // Do not cancel racers — their own fetch timeout drops a stuck
            // session; cancel here would tear down live watchers.
            lock.withLock {
                if (!done.isCompleted) {
                    done.complete(RaceOutcome(null, failed.toList(), hardFails == 0))
                }
            }
            done.await()
        }
    }

    fun kick() {
        wake?.invoke()
    }

    fun durableKick() {
        durableWake.store(true)
        kick()
    }
    onAdvance = { durableKick() }

    suspend fun waitForKick(ms: Long) {
        if (state.stopped) return
        if (durableWake.exchange(false)) return
        val deferred = CompletableDeferred<Unit>()
        val complete = { deferred.complete(Unit); Unit }
        wake = complete
        if (durableWake.exchange(false)) complete()
        try {
            withTimeoutOrNull(ms) { deferred.await() }
        } finally {
            if (wake === complete) wake = null
            durableWake.store(false)
        }
    }

    fun loadTrustedWindow(throughHeight: Int? = null): ValidatedHeaderChain {
        val tip = ctx.db.headers.tip() ?: error("headers DB has no tip")
        val to = if (throughHeight == null) {
            tip.height
        } else {
            minOf(tip.height, max(checkpointHeight, throughHeight))
        }
        val from = max(checkpointHeight, to - TRUSTED_CHAIN_WINDOW)
        val rows = ctx.db.headers.loadRange(from, to)
        if (rows.isEmpty()) error("trusted header window is empty")
        return trustedChainFromStored(rows, consensus)
    }

    fun ensureChain(): ValidatedHeaderChain {
        val existing = chain
        if (existing != null) return existing
        val loaded = loadTrustedWindow()
        chain = loaded
        return loaded
    }

    fun trimChainMemory(next: ValidatedHeaderChain): ValidatedHeaderChain {
        if (next.headers.size <= TRUSTED_CHAIN_WINDOW * 2) return next
        return loadTrustedWindow()
    }

    fun emitProgress() {
        if (maxPeerStartHeight <= checkpointHeight) return
        val tipHeight = chain?.tipHeight?.toInt() ?: ctx.db.headers.tip()!!.height
        val peerTip = max(maxPeerStartHeight, tipHeight)
        ctx.bus.emit(
            Event.HeadersProgress,
            HeadersProgressPayload(
                at = now(),
                downloaded = tipHeight - checkpointHeight,
                total = max(0, peerTip - checkpointHeight),
                height = tipHeight,
            ),
        )
    }

    fun tryFreezeBirthday() {
        if (maxPeerStartHeight <= checkpointHeight) return
        val tipHeight = chain?.tipHeight?.toInt() ?: ctx.db.headers.tip()?.height ?: return
        if (tipHeight < maxPeerStartHeight) return
        maybeFreezeWalletBirthday(ctx.db, tipHeight)
    }

    suspend fun runLoop() {
        val dead = HashSet<String>()
        val skipped = HashSet<String>()
        var loggedWaiting = false
        var loggedTipHeight = -1

        suspend fun markPeerHardFailed(peer: PeerRef) {
            dead.add(peerKey(peer.host, peer.port))
            pool?.drop(peer.host, peer.port)
            ctx.db.peers.markAlive(peer.host, peer.port, false)
            ctx.bus.emit(Event.PeersUpdated, PeersUpdatedPayload(now()))
            val stick = sticky
            if (stick != null && stick.host == peer.host && stick.port == peer.port) {
                sticky = null
            }
        }

        while (!state.stopped) {
          try {
            val allAlive = ctx.db.peers.listAlive().map { PeerRef(it.host, it.port) }
            val alive = allAlive.filter { !dead.contains(peerKey(it.host, it.port)) }

            if (alive.isEmpty()) {
                if (!loggedWaiting) {
                    loggedWaiting = true
                    log("chain-headers", "waiting for peers")
                }
                state.waitingForPeers = true
                try {
                    if (allAlive.isEmpty()) {
                        waitForKick(pollIntervalMs)
                    } else {
                        dead.clear()
                        skipped.clear()
                        sticky = null
                        waitForKick(250)
                    }
                } finally {
                    state.waitingForPeers = false
                }
                continue
            }

            loggedWaiting = false
            val raced = pickRacePeers(alive, skipped)
            if (raced.isEmpty()) {
                val hasUnskipped = alive.any { !skipped.contains(peerKey(it.host, it.port)) }
                if (!hasUnskipped) skipped.clear()
                waitForKick(100)
                continue
            }

            val tipChain = ensureChain()
            val locatorHashes = buildLocatorHashes(
                ctx,
                tipChain.tipHeight.toInt(),
                bytesToHex(tipChain.tipHashInternal),
                checkpointHeight,
            )
            val outcome = raceHeaderFetch(raced, locatorHashes)
            if (state.stopped) break

            for (peer in outcome.failed) {
                markPeerHardFailed(peer)
            }

            val winner = outcome.winner
            if (winner == null) {
                peerIndex += max(1, raced.size)
                waitForKick(if (outcome.busyOnly) 100 else 500)
                continue
            }

            sticky = winner.peer
            peerIndex += max(1, raced.size)

            if (winner.result.headers.isEmpty()) {
                skipped.clear()
                maxPeerStartHeight = ensureChain().tipHeight.toInt()
                emitProgress()
                tryFreezeBirthday()
                val tipHeight = ensureChain().tipHeight.toInt()
                if (loggedTipHeight != tipHeight) {
                    loggedTipHeight = tipHeight
                    log("chain-headers", "at tip height=$tipHeight")
                }
                // HOW: no pool (injected-fetch tests) counts as "watchers exist"
                // so existing pollIntervalMs backoff tests still hold.
                waitForKick(atTipWaitMs(pollIntervalMs, pool == null || hdrOpen > 0))
                continue
            }

            try {
                when (
                    val applied = applyHeaderBatch(
                        ctx,
                        winner.result.headers,
                        ensureChain(),
                        consensus,
                        nowSeconds,
                        ::loadTrustedWindow,
                    )
                ) {
                    is ApplyResult.Applied -> {
                        skipped.clear()
                        chain = trimChainMemory(applied.chain)
                        if (winner.result.startHeight > checkpointHeight) {
                            maxPeerStartHeight = max(maxPeerStartHeight, winner.result.startHeight)
                        }
                        emitProgress()
                        tryFreezeBirthday()
                    }
                    ApplyResult.NothingNew, ApplyResult.Weaker -> {
                        skipped.add(peerKey(winner.peer.host, winner.peer.port))
                        sticky = null
                        maxPeerStartHeight = ensureChain().tipHeight.toInt()
                        emitProgress()
                    }
                }
            } catch (err: HeaderConsensusError) {
                logError("chain-headers", "peer fail ${peerKey(winner.peer.host, winner.peer.port)}", err)
                markPeerHardFailed(winner.peer)
                waitForKick(500)
            } catch (err: kotlinx.coroutines.CancellationException) {
                throw err
            } catch (err: Throwable) {
                logError("chain-headers", "persist fail ${peerKey(winner.peer.host, winner.peer.port)}", err)
                waitForKick(500)
            }
          } catch (err: kotlinx.coroutines.CancellationException) {
              if (state.stopped) break
              logError("chain-headers", "loop cancelled", err)
              waitForKick(500)
          } catch (err: Throwable) {
              logError("chain-headers", "loop fail", err)
              waitForKick(500)
          }
        }
    }

    return object : Module {
        override val name: String = "chain-headers"

        override suspend fun start() {
            if (!state.stopped) return
            ctx.bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(module = "chain-headers", status = ModuleStatus.STARTING),
            )
            log("chain-headers", "start")
            state.stopped = false
            ctx.db.headers.ensureCheckpoint(checkpointSeedFromConsensus(consensus))
            unsubIdle = ctx.bus.on(Event.SyncIdle) { state.quiet = true }
            unsubCatchup = ctx.bus.on(Event.SyncCatchup) {
                state.quiet = false
                durableKick()
            }
            unsubPeers = ctx.bus.on(Event.PeersUpdated) {
                // hasLiveWatchers: no pool (tests) counts as "watchers exist".
                if (
                    ignoreQuietPeerKick(
                        quiet = state.quiet,
                        waitingForPeers = state.waitingForPeers,
                        hasLiveWatchers = pool == null || hdrOpen > 0,
                    )
                ) {
                    return@on
                }
                kick()
            }
            val job = SupervisorJob()
            parentJob = job
            val scope = CoroutineScope(job + Dispatchers.Default)
            moduleScope = scope
            val launched = scope.launch { runLoop() }
            loopJob = launched
            detachLoop(ctx, "chain-headers", launched)
            ctx.bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(module = "chain-headers", status = ModuleStatus.RUNNING),
            )
        }

        override fun stop() {
            if (state.stopped) return
            state.stopped = true
            unsubIdle?.invoke()
            unsubIdle = null
            unsubCatchup?.invoke()
            unsubCatchup = null
            unsubPeers?.invoke()
            unsubPeers = null
            state.waitingForPeers = false
            state.quiet = false
            durableKick()
            runBlocking {
                pool?.closeAll()
                loopJob?.join()
                parentJob?.cancel()
                pool?.closeAll()
            }
            loopJob = null
            parentJob = null
            moduleScope = null
            sticky = null
            log("chain-headers", "stop")
            ctx.bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(module = "chain-headers", status = ModuleStatus.STOPPED),
            )
        }
    }
}
