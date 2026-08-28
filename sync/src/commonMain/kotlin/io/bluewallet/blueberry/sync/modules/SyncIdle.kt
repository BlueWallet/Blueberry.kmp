package io.bluewallet.blueberry.sync.modules

import io.bluewallet.bip157.NODE_COMPACT_FILTERS
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.ModuleStatus
import io.bluewallet.blueberry.bus.ModuleStatusPayload
import io.bluewallet.blueberry.bus.SyncCatchupPayload
import io.bluewallet.blueberry.bus.SyncIdlePayload
import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.peers.log
import io.bluewallet.blueberry.peers.modules.Module
import io.bluewallet.blueberry.peers.modules.ModuleContext
import io.bluewallet.blueberry.peers.modules.detachLoop
import io.bluewallet.blueberry.sync.SyncEvaluation
import io.bluewallet.blueberry.sync.SyncMode
import io.bluewallet.blueberry.sync.SyncSnapshot
import io.bluewallet.blueberry.sync.evaluateSyncState
import io.bluewallet.blueberry.wallet.WalletBirthdayInspection
import io.bluewallet.blueberry.wallet.compactFilterFrom
import io.bluewallet.blueberry.wallet.inspectWalletBirthday
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.max

/** Bitcoin NODE_NETWORK — peer can serve historical blocks. */
private val NODE_NETWORK = 1uL

class SyncIdleOptions(
    val evalIntervalMs: Long? = null,
    val minAliveCompactFilters: Int? = null,
    val now: (() -> Long)? = null,
    val log: ((String) -> Unit)? = null,
)

private data class SyncIdleState(
    val stopped: Boolean = true,
    val mode: SyncMode = SyncMode.CATCHUP,
    val idleStreak: Int = 0,
    val headersDownloaded: Int = 0,
    val headersTotal: Int = 0,
)

private data class EvaluationRequest(
    val update: (SyncIdleState) -> SyncIdleState = { it },
    val churnOnly: Boolean = false,
)

@OptIn(ExperimentalAtomicApi::class)
fun createSyncIdleModule(
    ctx: ModuleContext,
    options: SyncIdleOptions = SyncIdleOptions(),
): Module {
    val evalIntervalMs = options.evalIntervalMs ?: 5_000L
    val minAliveCompactFilters = options.minAliveCompactFilters ?: 16
    val now = options.now ?: { nowMillis() }
    val diagnosticLog = options.log ?: { message -> log("sync-idle", message) }

    val state = AtomicReference(SyncIdleState())
    // Every requested evaluation contributes to the two-sample idle
    // confirmation, so requests must remain ordered and must not be conflated.
    val evaluationRequests = Channel<EvaluationRequest>(Channel.UNLIMITED)
    val unsubs = mutableListOf<() -> Unit>()
    var loopJob: Job? = null
    var parentJob: Job? = null

    fun writeLog(message: String) {
        diagnosticLog(message)
    }

    fun buildSnapshot(cur: SyncIdleState): SyncSnapshot {
        val minH = ctx.db.headers.minHeight()
        val tip = ctx.db.headers.tip()
        // Avoid filters.missingRanges here: with internal gaps it scans the fat
        // BLOB table (~250ms) and starves keypress/quit on every filters:progress.
        // Create-wallets only sync cfilters from birthday→tip (not header checkpoint).
        val birthday = inspectWalletBirthday(ctx.db)
        var filterMissingRangeCount = 1
        if (minH != null && tip != null && birthday !is WalletBirthdayInspection.Pending) {
            val filterFrom = compactFilterFrom(ctx.db)!!
            filterMissingRangeCount =
                if (tip.height < filterFrom || !ctx.db.filters.completeInRange(filterFrom, tip.height)) {
                    1
                } else {
                    0
                }
        }

        val alivePeerCount =
            if (
                ctx.db.peers.listAliveWithServices(NODE_NETWORK, 1).isNotEmpty() ||
                ctx.db.peers.listAliveWithServices(NODE_COMPACT_FILTERS.toULong(), 1).isNotEmpty()
            ) {
                1
            } else {
                0
            }

        val needingDownloadCount = ctx.db.matchedBlocks.listNeedingDownload(1).size
        // CF pool size only changes the catchup *reason* when leaving idle with
        // filter work. Skip the extra scan on the catchup/idle-complete path.
        val filterWorkNeedsPeers =
            cur.mode == SyncMode.IDLE &&
                filterMissingRangeCount > 0 &&
                ctx.db.peers.listAliveWithServices(
                    NODE_COMPACT_FILTERS.toULong(),
                    minAliveCompactFilters,
                ).size < minAliveCompactFilters

        return SyncSnapshot(
            headersDownloaded = cur.headersDownloaded,
            headersTotal = cur.headersTotal,
            filterMissingRangeCount = filterMissingRangeCount,
            filterWorkNeedsPeers = filterWorkNeedsPeers,
            // Re-read blocks. A throttled or stale blocks:progress must not
            // keep CATCHUP after SQLite is already caught up (parse stays paused).
            blocksDownloaded = ctx.db.blocks.count(),
            blocksMatched = ctx.db.matchedBlocks.count(),
            needingDownloadCount = needingDownloadCount,
            alivePeerCount = alivePeerCount,
        )
    }

    fun applyEvaluation(cur: SyncIdleState, evalResult: SyncEvaluation): Pair<SyncIdleState, SyncEvaluation?> {
        if (evalResult is SyncEvaluation.Idle) {
            val idleStreak = cur.idleStreak + 1
            if (cur.mode == SyncMode.CATCHUP && idleStreak >= 2) {
                return cur.copy(mode = SyncMode.IDLE, idleStreak = idleStreak) to SyncEvaluation.Idle
            }
            return cur.copy(idleStreak = idleStreak) to null
        }
        val catchup = evalResult as SyncEvaluation.Catchup
        val reset = cur.copy(idleStreak = 0)
        if (cur.mode == SyncMode.IDLE) {
            return reset.copy(mode = SyncMode.CATCHUP) to catchup
        }
        return reset to null
    }

    fun evaluateOnce() {
        while (true) {
            val cur = state.load()
            if (cur.stopped) return
            val (next, transition) = applyEvaluation(cur, evaluateSyncState(buildSnapshot(cur)))
            if (!state.compareAndSet(cur, next)) continue
            when (transition) {
                SyncEvaluation.Idle -> {
                    writeLog("idle")
                    ctx.bus.emit(Event.SyncIdle, SyncIdlePayload(at = now()))
                    ctx.bus.emit(
                        Event.ModuleStatus,
                        ModuleStatusPayload(
                            module = "sync-idle",
                            status = ModuleStatus.RUNNING,
                            detail = "idle",
                        ),
                    )
                }
                is SyncEvaluation.Catchup -> {
                    writeLog("catchup reason=${transition.reason.wireName}")
                    ctx.bus.emit(
                        Event.SyncCatchup,
                        SyncCatchupPayload(at = now(), reason = transition.reason),
                    )
                    ctx.bus.emit(
                        Event.ModuleStatus,
                        ModuleStatusPayload(
                            module = "sync-idle",
                            status = ModuleStatus.RUNNING,
                            detail = "catchup:${transition.reason.wireName}",
                        ),
                    )
                }
                null -> {}
            }
            return
        }
    }

    fun requestEvaluation(request: EvaluationRequest = EvaluationRequest()) {
        if (!state.load().stopped) evaluationRequests.trySend(request)
    }

    fun update(transform: (SyncIdleState) -> SyncIdleState) {
        while (true) {
            val cur = state.load()
            if (state.compareAndSet(cur, transform(cur))) return
        }
    }

    return object : Module {
        override val name: String = "sync-idle"

        override suspend fun start() {
            if (!state.load().stopped) return
            ctx.bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(module = "sync-idle", status = ModuleStatus.STARTING),
            )
            writeLog("start")
            val tip = ctx.db.headers.tip()
            val minH = ctx.db.headers.minHeight()
            val headersDownloaded: Int
            val headersTotal: Int
            if (tip != null && minH != null) {
                headersDownloaded = max(0, tip.height - minH)
                headersTotal = headersDownloaded
            } else {
                headersDownloaded = 0
                headersTotal = 0
            }
            while (evaluationRequests.tryReceive().isSuccess) {}
            state.store(
                SyncIdleState(
                    stopped = false,
                    mode = SyncMode.CATCHUP,
                    idleStreak = 0,
                    headersDownloaded = headersDownloaded,
                    headersTotal = headersTotal,
                ),
            )
            unsubs += ctx.bus.on(Event.HeadersProgress) { p ->
                requestEvaluation(
                    EvaluationRequest(
                        update = {
                            it.copy(headersDownloaded = p.downloaded, headersTotal = p.total)
                        },
                    ),
                )
            }
            unsubs += ctx.bus.on(Event.BlocksProgress) { requestEvaluation() }
            unsubs += ctx.bus.on(Event.FiltersProgress) { requestEvaluation() }
            unsubs += ctx.bus.on(Event.FiltersMatch) {
                requestEvaluation(EvaluationRequest(churnOnly = true))
            }
            unsubs += ctx.bus.on(Event.PeersUpdated) {
                requestEvaluation(EvaluationRequest(churnOnly = true))
            }

            val job = SupervisorJob()
            parentJob = job
            val launched = CoroutineScope(job + Dispatchers.Default).launch {
                while (isActive) {
                    val request = withTimeoutOrNull(evalIntervalMs) {
                        evaluationRequests.receive()
                    }
                    if (request != null) {
                        update(request.update)
                        val cur = state.load()
                        if (
                            request.churnOnly &&
                            cur.mode == SyncMode.CATCHUP &&
                            cur.idleStreak == 0
                        ) {
                            continue
                        }
                    }
                    evaluateOnce()
                }
            }
            loopJob = launched
            detachLoop(ctx, "sync-idle", launched)
            ctx.bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(module = "sync-idle", status = ModuleStatus.RUNNING),
            )
        }

        override fun stop() {
            if (state.load().stopped) return
            update { it.copy(stopped = true) }
            for (unsub in unsubs) unsub()
            unsubs.clear()
            parentJob?.cancel()
            runBlocking {
                loopJob?.join()
            }
            loopJob = null
            parentJob = null
            writeLog("stop")
            ctx.bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(module = "sync-idle", status = ModuleStatus.STOPPED),
            )
        }
    }
}
