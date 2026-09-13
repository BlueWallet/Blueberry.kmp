package io.bluewallet.blueberry.filters.modules

import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.FiltersMatchPayload
import io.bluewallet.blueberry.bus.MatchingProgressPayload
import io.bluewallet.blueberry.bus.ModuleStatus
import io.bluewallet.blueberry.bus.ModuleStatusPayload
import io.bluewallet.blueberry.filters.currentTimeMillis
import io.bluewallet.blueberry.filters.match.MATCH_BATCH_GAP_MS
import io.bluewallet.blueberry.filters.match.MATCH_FILTER_BATCH_SIZE
import io.bluewallet.blueberry.filters.match.MatchScanCallbacks
import io.bluewallet.blueberry.filters.match.MatchScanOptions
import io.bluewallet.blueberry.filters.match.scanFiltersForMatches
import io.bluewallet.blueberry.peers.log
import io.bluewallet.blueberry.peers.logError
import io.bluewallet.blueberry.peers.modules.Module
import io.bluewallet.blueberry.peers.modules.ModuleContext
import io.bluewallet.blueberry.peers.modules.detachLoop
import io.bluewallet.blueberry.wallet.Wallet
import io.bluewallet.blueberry.wallet.WatchGaps
import io.bluewallet.blueberry.wallet.compactFilterFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.max

class FiltersMatchingOptions(
    val wallet: Wallet,
    val batchSize: Int? = null,
    val batchGapMs: Long? = null,
    val yieldFn: (suspend () -> Unit)? = null,
    val now: (() -> Long)? = null,
    val log: ((String) -> Unit)? = null,
)

private const val IDLE_POLL_MS = 1_000L

private suspend fun yieldOnce() {
    yield()
}

@OptIn(ExperimentalAtomicApi::class)
fun createFiltersMatchingModule(
    ctx: ModuleContext,
    options: FiltersMatchingOptions,
): Module {
    val wallet = options.wallet
    val batchSize = max(1, options.batchSize ?: MATCH_FILTER_BATCH_SIZE)
    val batchGapMs = max(0L, options.batchGapMs ?: MATCH_BATCH_GAP_MS)
    val yieldFn = options.yieldFn ?: { yieldOnce() }
    val now = options.now ?: { currentTimeMillis() }
    val diagnosticLog = options.log ?: { message -> log("filters-matching", message) }

    val stopped = AtomicBoolean(true)
    val busy = AtomicBoolean(false)
    val needsRun = AtomicBoolean(false)
    val waiters = AtomicReference<List<CompletableDeferred<Unit>>>(emptyList())
    var unsubProgress: (() -> Unit)? = null
    var loopJob: Job? = null
    var parentJob: Job? = null
    var loadedGaps: WatchGaps? = null
    var scannedCount = 0
    var totalCount = 0

    fun isStopped() = stopped.load()

    fun kick() {
        val current = waiters.exchange(emptyList())
        for (wake in current) wake.complete(Unit)
    }

    suspend fun waitForKick(ms: Long = IDLE_POLL_MS) {
        if (isStopped()) return
        val done = CompletableDeferred<Unit>()
        while (true) {
            val cur = waiters.load()
            if (waiters.compareAndSet(cur, cur + done)) break
        }
        try {
            // stop()/kick() can win the gap after the first isStopped() check
            // and before this waiter is visible; don't sit out the idle poll.
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

    fun emitProgress() {
        ctx.bus.emit(
            Event.MatchingProgress,
            MatchingProgressPayload(at = now(), scanned = scannedCount, total = totalCount),
        )
    }

    fun seedProgress() {
        scannedCount = ctx.db.filters.countScanned()
        totalCount = ctx.db.filters.count()
        emitProgress()
    }

    suspend fun loop() {
        while (!isStopped()) {
            busy.store(true)
            needsRun.store(false)
            try {
                wallet.syncFromDb()
                val gaps = wallet.gaps()
                // Use loadedGaps, not syncFromDb().grew — parse-blocks refresh() would
                // hide growth and skip re-queue after an in-flight markScanned.
                val previous = loadedGaps
                if (
                    previous != null &&
                    (previous.external != gaps.external || previous.internal != gaps.internal)
                ) {
                    val fromHeight = compactFilterFrom(ctx.db) ?: ctx.db.transactions.minHeight()
                    if (fromHeight != null) {
                        diagnosticLog(
                            "rematch from=$fromHeight external=${gaps.external} internal=${gaps.internal}",
                        )
                        ctx.db.filters.markUnscannedFrom(fromHeight)
                    }
                }
                loadedGaps = gaps
                val scannedWith = gaps
                var matches = 0
                val scanTotal = ctx.db.filters.count()
                val scanScanned = ctx.db.filters.countScanned()
                if (scanTotal != scanScanned) {
                    diagnosticLog(
                        "scan start scanned=$scanScanned total=$scanTotal external=${gaps.external} internal=${gaps.internal}",
                    )
                }
                val advanced =
                    scanFiltersForMatches(
                        ctx.db,
                        wallet.scripts(),
                        MatchScanCallbacks(
                            onMatch = { m ->
                                matches++
                                ctx.bus.emit(
                                    Event.FiltersMatch,
                                    FiltersMatchPayload(
                                        height = m.height,
                                        blockHashInternalHex = m.blockHashInternalHex,
                                    ),
                                )
                            },
                            onProgress = { p ->
                                if (p.scanned != scannedCount || p.total != totalCount) {
                                    scannedCount = p.scanned
                                    totalCount = p.total
                                    emitProgress()
                                }
                            },
                        ),
                        MatchScanOptions(
                            batchSize = batchSize,
                            batchGapMs = batchGapMs,
                            yieldFn = yieldFn,
                            // Abort when gaps grow mid-scan — stale scripts must not keep
                            // draining a rematch queue (wasted CPU until the next loop).
                            shouldContinue = {
                                if (isStopped()) {
                                    false
                                } else {
                                    val g = wallet.peekGaps()
                                    g.external == scannedWith.external &&
                                        g.internal == scannedWith.internal
                                }
                            },
                        ),
                    )
                if (advanced > 0) {
                    diagnosticLog(
                        "scan done scanned=${ctx.db.filters.countScanned()} total=${ctx.db.filters.count()} matches=$matches",
                    )
                }
                // Peek only — sync here would advance loadedGaps before rematch.
                val gapsNow = wallet.peekGaps()
                if (
                    scannedWith.external != gapsNow.external ||
                    scannedWith.internal != gapsNow.internal
                ) {
                    needsRun.store(true)
                }
            } catch (err: CancellationException) {
                throw err
            } catch (err: Throwable) {
                logError("filters-matching", "scan", err)
                ctx.bus.emit(
                    Event.ModuleStatus,
                    ModuleStatusPayload(
                        module = "filters-matching",
                        status = ModuleStatus.ERROR,
                        detail = err.message ?: err.toString(),
                    ),
                )
                busy.store(false)
                if (isStopped()) return
                waitForKick()
                continue
            }
            busy.store(false)
            if (isStopped()) return
            if (needsRun.load()) continue
            waitForKick()
        }
    }

    return object : Module {
        override val name = "filters-matching"

        override suspend fun start() {
            if (!isStopped()) return
            stopped.store(false)
            diagnosticLog("start")
            ctx.bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(module = "filters-matching", status = ModuleStatus.STARTING),
            )
            seedProgress()
            wallet.refresh()
            loadedGaps = wallet.gaps()
            unsubProgress =
                ctx.bus.on(Event.FiltersProgress) {
                    if (isStopped()) return@on
                    if (busy.load()) {
                        needsRun.store(true)
                        return@on
                    }
                    kick()
                }
            ctx.bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(module = "filters-matching", status = ModuleStatus.RUNNING),
            )
            val job = SupervisorJob()
            parentJob = job
            val scope = CoroutineScope(job + Dispatchers.Default)
            val launched =
                scope.launch {
                    yieldOnce()
                    if (isStopped()) return@launch
                    loop()
                }
            loopJob = launched
            detachLoop(ctx, "filters-matching", launched)
        }

        override fun stop() {
            if (isStopped()) return
            stopped.store(true)
            unsubProgress?.invoke()
            unsubProgress = null
            kick()
            runBlocking {
                loopJob?.join()
                parentJob?.cancel()
            }
            loopJob = null
            parentJob = null
            busy.store(false)
            needsRun.store(false)
            diagnosticLog("stop")
            ctx.bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(module = "filters-matching", status = ModuleStatus.STOPPED),
            )
        }
    }
}
