package io.bluewallet.blueberry.parse.modules

import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.FiltersProgressPayload
import io.bluewallet.blueberry.bus.ModuleStatus
import io.bluewallet.blueberry.bus.ModuleStatusPayload
import io.bluewallet.blueberry.bus.WalletTxsPayload
import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.parse.TxRow
import io.bluewallet.blueberry.parse.buildUtxoMap
import io.bluewallet.blueberry.parse.decodeBlockTxs
import io.bluewallet.blueberry.parse.extractWatchTxs
import io.bluewallet.blueberry.parse.netDeltasForTxs
import io.bluewallet.blueberry.parse.usedWatchIndexes
import io.bluewallet.blueberry.peers.log
import io.bluewallet.blueberry.peers.logError
import io.bluewallet.blueberry.peers.modules.Module
import io.bluewallet.blueberry.peers.modules.ModuleContext
import io.bluewallet.blueberry.peers.modules.detachLoop
import io.bluewallet.blueberry.storage.StoredTx
import io.bluewallet.blueberry.wallet.Wallet
import io.bluewallet.blueberry.wallet.WatchWalletKind
import io.bluewallet.blueberry.wallet.compactFilterFrom
import io.bluewallet.blueberry.wallet.growWatchGapsIfNeeded
import io.bluewallet.blueberry.wallet.loadWatchGaps
import io.bluewallet.blueberry.wallet.saveWatchGaps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.max

private const val DEFAULT_BATCH_SIZE = 32
private const val DEFAULT_IDLE_DELAY_MS = 1_000L
private const val DEFAULT_BLOCK_GAP_MS = 0L

class ParseBlocksOptions(
    val wallet: Wallet,
    val batchSize: Int? = null,
    val idleDelayMs: Long? = null,
    val blockGapMs: Long? = null,
    val now: (() -> Long)? = null,
    val onParseBatch: (suspend () -> Unit)? = null,
    val log: ((String) -> Unit)? = null,
    val logError: ((String, Throwable?) -> Unit)? = null,
)

@OptIn(ExperimentalAtomicApi::class)
fun createParseBlocksModule(
    ctx: ModuleContext,
    options: ParseBlocksOptions,
): Module {
    val wallet = options.wallet
    val batchSize = max(1, options.batchSize ?: DEFAULT_BATCH_SIZE)
    val idleDelayMs = max(0L, options.idleDelayMs ?: DEFAULT_IDLE_DELAY_MS)
    val blockGapMs = max(0L, options.blockGapMs ?: DEFAULT_BLOCK_GAP_MS)
    val now = options.now ?: { nowMillis() }
    val onParseBatch = options.onParseBatch
    val diagnosticLog = options.log ?: { message -> log("parse-blocks", message) }
    val diagnosticError = options.logError ?: { message, err ->
        logError("parse-blocks", message, err)
    }

    val stopped = AtomicBoolean(true)
    val allowed = AtomicBoolean(false)
    val busy = AtomicBoolean(false)
    val needsRun = AtomicBoolean(false)
    val waiters = AtomicReference<List<CompletableDeferred<Unit>>>(emptyList())
    val pendingKick = AtomicBoolean(false)
    val failedHeights = mutableSetOf<Int>()

    var unsubProgress: (() -> Unit)? = null
    var unsubIdle: (() -> Unit)? = null
    var unsubCatchup: (() -> Unit)? = null
    var loopJob: Job? = null
    var parentJob: Job? = null

    fun isStopped() = stopped.load()

    fun kick() {
        pendingKick.store(true)
        val current = waiters.exchange(emptyList())
        for (wake in current) wake.complete(Unit)
    }

    suspend fun waitForKick(ms: Long? = null) {
        if (isStopped()) return
        if (pendingKick.compareAndSet(true, false)) return
        val done = CompletableDeferred<Unit>()
        while (true) {
            val cur = waiters.load()
            if (waiters.compareAndSet(cur, cur + done)) break
        }
        try {
            if (isStopped()) return
            if (pendingKick.compareAndSet(true, false)) return
            if (ms != null) {
                withTimeout(ms) { done.await() }
            } else {
                done.await()
            }
        } catch (_: TimeoutCancellationException) {
        } finally {
            while (true) {
                val cur = waiters.load()
                val next = cur - done
                if (next === cur || waiters.compareAndSet(cur, next)) break
            }
        }
    }

    suspend fun yieldOnce() {
        yield()
    }

    fun storedToRows(rows: List<StoredTx>): List<TxRow> =
        rows.map { TxRow(it.txid, it.height, it.txIndex, it.tx) }

    fun refreshNetDeltasAndEmit() {
        val snap = wallet.snapshot()
        val rows = ctx.db.transactions.list()
        val deltas = netDeltasForTxs(storedToRows(rows), snap.scripts)
        for (row in rows) {
            val d = deltas[row.txid] ?: 0L
            if (row.netDeltaSats != d) {
                ctx.db.transactions.setNetDelta(row.txid, d)
            }
        }
        ctx.bus.emit(Event.WalletTxs, WalletTxsPayload(now()))
    }

    fun maybeGrowWatch(): Boolean {
        val snap = wallet.snapshot()
        if (snap.kind == WatchWalletKind.WIF || snap.kind == WatchWalletKind.ADDRESS) return false
        val used = usedWatchIndexes(ctx.db.transactions.list().map { it.tx }, snap)
        val result = growWatchGapsIfNeeded(loadWatchGaps(ctx.db), used.external, used.internal)
        if (!result.grew) return false
        val fromHeight = compactFilterFrom(ctx.db) ?: ctx.db.transactions.minHeight()
        ctx.db.transaction {
            saveWatchGaps(ctx.db, result.gaps)
            if (fromHeight != null) {
                ctx.db.filters.markUnscannedFrom(fromHeight)
                ctx.db.parsedBlocks.clearFrom(fromHeight)
            }
        }
        wallet.refresh()
        val tip = ctx.db.headers.tip()
        val downloaded = ctx.db.filters.count()
        val filterFrom = compactFilterFrom(ctx.db)
        val total =
            if (tip != null && filterFrom != null) max(0, tip.height - filterFrom + 1)
            else downloaded
        ctx.bus.emit(
            Event.FiltersProgress,
            FiltersProgressPayload(
                at = now(),
                downloaded = minOf(downloaded, total),
                total = total,
            ),
        )
        needsRun.store(true)
        return true
    }

    suspend fun parseBatch() {
        onParseBatch?.invoke()

        val listed = ctx.db.blocks.listNeedingParse(batchSize + failedHeights.size + 1)
        val blocks = mutableListOf<io.bluewallet.blueberry.storage.DownloadedBlock>()
        for (block in listed) {
            if (failedHeights.contains(block.height)) continue
            if (blocks.size >= batchSize) {
                needsRun.store(true)
                break
            }
            blocks.add(block)
        }
        if (blocks.isEmpty()) {
            maybeGrowWatch()
            refreshNetDeltasAndEmit()
            return
        }

        diagnosticLog("batch n=${blocks.size} from=${blocks.first().height} to=${blocks.last().height}")

        val scripts = wallet.scripts()
        val utxos = buildUtxoMap(storedToRows(ctx.db.transactions.list()), scripts)
        var sawWatchTx = false
        for (i in blocks.indices) {
            if (isStopped() || !allowed.load()) return
            val block = blocks[i]
            yieldOnce()
            if (isStopped() || !allowed.load()) return
            val watchTxs = try {
                extractWatchTxs(decodeBlockTxs(block.block), scripts, utxos)
            } catch (err: CancellationException) {
                throw err
            } catch (err: Throwable) {
                diagnosticError("decode height=${block.height}", err)
                failedHeights.add(block.height)
                ctx.bus.emit(
                    Event.ModuleStatus,
                    ModuleStatusPayload(
                        module = "parse-blocks",
                        status = ModuleStatus.ERROR,
                        detail = "height ${block.height}: ${err.message ?: err.toString()}",
                    ),
                )
                null
            }
            if (watchTxs != null) {
                try {
                    for (tx in watchTxs) {
                        ctx.db.transactions.upsert(
                            StoredTx(
                                txid = tx.txid,
                                height = block.height,
                                txIndex = tx.txIndex,
                                blockHashInternalHex = block.blockHashInternalHex,
                                tx = tx.tx,
                                netDeltaSats = 0,
                            ),
                        )
                    }
                    ctx.db.parsedBlocks.mark(block.height)
                    if (watchTxs.isNotEmpty()) {
                        sawWatchTx = true
                        refreshNetDeltasAndEmit()
                        if (maybeGrowWatch()) return
                    } else {
                        ctx.bus.emit(Event.WalletTxs, WalletTxsPayload(now()))
                    }
                } catch (err: CancellationException) {
                    throw err
                } catch (err: Throwable) {
                    diagnosticError("persist height=${block.height}", err)
                    ctx.bus.emit(
                        Event.ModuleStatus,
                        ModuleStatusPayload(
                            module = "parse-blocks",
                            status = ModuleStatus.ERROR,
                            detail = "height ${block.height}: ${err.message ?: err.toString()}",
                        ),
                    )
                    needsRun.store(true)
                    if (idleDelayMs > 0) delay(idleDelayMs)
                    return
                }
            }
            if (!allowed.load()) return
            if (i + 1 < blocks.size) {
                if (blockGapMs > 0) delay(blockGapMs)
                else yieldOnce()
                if (isStopped() || !allowed.load()) return
            }
        }

        if (!sawWatchTx) maybeGrowWatch()
    }

    suspend fun loop() {
        while (!isStopped()) {
            busy.store(true)
            needsRun.store(false)
            pendingKick.store(false)
            if (!allowed.load()) {
                busy.store(false)
                failedHeights.clear()
                if (needsRun.load()) continue
                waitForKick()
                continue
            }
            try {
                parseBatch()
            } catch (err: CancellationException) {
                throw err
            } catch (err: Throwable) {
                diagnosticError("batch", err)
                ctx.bus.emit(
                    Event.ModuleStatus,
                    ModuleStatusPayload(
                        module = "parse-blocks",
                        status = ModuleStatus.ERROR,
                        detail = err.message ?: err.toString(),
                    ),
                )
                busy.store(false)
                if (isStopped()) return
                waitForKick(idleDelayMs)
                continue
            }
            busy.store(false)
            if (isStopped()) return
            if (needsRun.load()) {
                yieldOnce()
                continue
            }
            failedHeights.clear()
            waitForKick()
        }
    }

    return object : Module {
        override val name = "parse-blocks"

        override suspend fun start() {
            if (!isStopped()) return
            stopped.store(false)
            failedHeights.clear()
            diagnosticLog("start")
            ctx.bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(module = "parse-blocks", status = ModuleStatus.STARTING),
            )
            wallet.refresh()

            unsubProgress = ctx.bus.on(Event.BlocksProgress) {
                if (isStopped()) return@on
                if (busy.load()) {
                    needsRun.store(true)
                    return@on
                }
                kick()
            }
            unsubIdle = ctx.bus.on(Event.SyncIdle) {
                if (isStopped()) return@on
                allowed.store(true)
                diagnosticLog("allowed")
                if (busy.load()) {
                    needsRun.store(true)
                    return@on
                }
                kick()
            }
            unsubCatchup = ctx.bus.on(Event.SyncCatchup) {
                allowed.store(false)
                diagnosticLog("paused")
            }

            ctx.bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(module = "parse-blocks", status = ModuleStatus.RUNNING),
            )

            val job = SupervisorJob()
            parentJob = job
            val scope = CoroutineScope(job + Dispatchers.Default)
            val launched = scope.launch {
                yieldOnce()
                if (isStopped()) return@launch
                loop()
            }
            loopJob = launched
            detachLoop(ctx, "parse-blocks", launched)
        }

        override fun stop() {
            if (isStopped()) return
            stopped.store(true)
            allowed.store(false)
            unsubProgress?.invoke()
            unsubProgress = null
            unsubIdle?.invoke()
            unsubIdle = null
            unsubCatchup?.invoke()
            unsubCatchup = null
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
                ModuleStatusPayload(module = "parse-blocks", status = ModuleStatus.STOPPED),
            )
        }
    }
}
