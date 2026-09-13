package io.bluewallet.blueberry.filters.modules

import io.bluewallet.bip157.CF_CHECKPT_INTERVAL
import io.bluewallet.bip157.MAX_GETCFHEADERS_RANGE
import io.bluewallet.bip157.MAX_GETCFILTERS_RANGE
import io.bluewallet.bip157.NODE_COMPACT_FILTERS
import io.bluewallet.bip157.bytesToHex
import io.bluewallet.bip157.deriveFilterHeaders
import io.bluewallet.bip157.equalBytes
import io.bluewallet.bip157.hexToBytes
import io.bluewallet.bip157.verifyCFilterAgainstHeader
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.FiltersProgressPayload
import io.bluewallet.blueberry.bus.ModuleStatus
import io.bluewallet.blueberry.bus.ModuleStatusPayload
import io.bluewallet.blueberry.bus.PeerSocketKind
import io.bluewallet.blueberry.bus.PeersSocketsPayload
import io.bluewallet.blueberry.filters.currentTimeMillis
import io.bluewallet.blueberry.peers.Config
import io.bluewallet.blueberry.peers.log
import io.bluewallet.blueberry.peers.modules.Module
import io.bluewallet.blueberry.peers.modules.ModuleContext
import io.bluewallet.blueberry.peers.modules.detachLoop
import io.bluewallet.blueberry.peers.net.CFilterItem
import io.bluewallet.blueberry.peers.net.FilterBatchResult
import io.bluewallet.blueberry.peers.net.FilterPoolPeer
import io.bluewallet.blueberry.peers.net.FilterSessionApi
import io.bluewallet.blueberry.peers.net.FilterSessionPoolOptions
import io.bluewallet.blueberry.peers.net.FilterSyncOptions
import io.bluewallet.blueberry.peers.net.PlatformNet
import io.bluewallet.blueberry.peers.net.createFilterSessionPool
import io.bluewallet.blueberry.storage.FilterHeaderRecord
import io.bluewallet.blueberry.storage.FilterRecord
import io.bluewallet.blueberry.storage.HeightRange
import io.bluewallet.blueberry.storage.WipeFiltersFromOptions
import io.bluewallet.blueberry.wallet.WalletBirthdayInspection
import io.bluewallet.blueberry.wallet.compactFilterFrom
import io.bluewallet.blueberry.wallet.inspectWalletBirthday
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.max
import kotlin.math.min

class FiltersDownloadOptions(
    val net: PlatformNet,
    val openSession: (suspend (String, Int, FilterSyncOptions) -> FilterBatchResult<FilterSessionApi>)? = null,
    val connectTimeoutMs: Long? = null,
    val syncTimeoutMs: Long? = null,
    val concurrency: Int? = null,
    val filterBatchSize: Int? = null,
    val headerBatchSize: Int? = null,
    val persistBatchSize: Int? = null,
    val idleDelayMs: Long? = null,
    val coolMs: Long? = null,
    val now: (() -> Long)? = null,
    val onDownloadRun: (() -> Unit)? = null,
    val log: ((String) -> Unit)? = null,
)

private data class PeerRef(
    val host: String,
    val port: Int,
)

private const val UI_MIN_MS = 100L

private fun nextCheckpointHeight(from: Int): Int = ((from + 1 + CF_CHECKPT_INTERVAL - 1) / CF_CHECKPT_INTERVAL) * CF_CHECKPT_INTERVAL

private fun isBip157CheckpointHeight(height: Int): Boolean = height > 0 && height % CF_CHECKPT_INTERVAL == 0

private fun formatError(err: Throwable): String = err.message ?: err.toString()

@OptIn(ExperimentalAtomicApi::class)
fun createFiltersDownloadModule(
    ctx: ModuleContext,
    options: FiltersDownloadOptions,
): Module {
    val openSession = options.openSession
    val connectTimeoutMs = options.connectTimeoutMs ?: Config.peerProbeTimeoutMs
    val syncTimeoutMs = options.syncTimeoutMs ?: Config.filterSyncTimeoutMs
    val concurrency = max(1, options.concurrency ?: Config.filterConcurrency)
    val headerBatchSize =
        min(
            options.headerBatchSize ?: Config.filterHeaderBatchSize,
            MAX_GETCFHEADERS_RANGE,
        )
    val filterBatchSize =
        min(
            options.filterBatchSize ?: Config.filterBatchSize,
            MAX_GETCFILTERS_RANGE,
        )
    val persistBatchSize = max(1, options.persistBatchSize ?: 25)
    val idleDelayMs = options.idleDelayMs ?: 250L
    val now = options.now ?: { currentTimeMillis() }
    val diagnosticLog = options.log ?: { message -> log("filters-download", message) }
    var runSequence = 0

    val pool =
        createFilterSessionPool(
            FilterSessionPoolOptions(
                connect = options.net.connect,
                openSession = openSession,
                max = concurrency,
                connectTimeoutMs = connectTimeoutMs,
                syncTimeoutMs = syncTimeoutMs,
                coolMs = options.coolMs ?: 30_000L,
                now = now,
                onOpenCount = { open ->
                    ctx.bus.emit(
                        Event.PeersSockets,
                        PeersSocketsPayload(at = now(), kind = PeerSocketKind.FILT, open = open),
                    )
                },
                onDiagnostic = diagnosticLog,
            ),
        )

    val stopped = AtomicBoolean(true)
    var quiet = false
    val runRequests = Channel<Unit>(Channel.CONFLATED)
    val waiters = AtomicReference<List<CompletableDeferred<Unit>>>(emptyList())
    var unsubHeaders: (() -> Unit)? = null
    var unsubPeers: (() -> Unit)? = null
    var unsubIdle: (() -> Unit)? = null
    var unsubCatchup: (() -> Unit)? = null
    var loopJob: Job? = null
    var parentJob: Job? = null
    var moduleScope: CoroutineScope? = null
    val haveCached = AtomicInt(0)
    var lastEmitAt = 0L
    var hashCheckedThrough = -1
    val persistLock = Mutex()

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

    suspend fun refreshPeers(): List<PeerRef> {
        val bits = NODE_COMPACT_FILTERS.toULong()
        val peers =
            ctx.db.peers
                .listAliveWithServices(bits, 512)
                .map { PeerRef(it.host, it.port) }
        if (peers.isNotEmpty()) {
            pool.setPeers(peers.map { FilterPoolPeer(it.host, it.port) })
            return peers
        }
        val stored =
            ctx.db.peers
                .listWithServices(bits, 256)
                .map { PeerRef(it.host, it.port) }
        pool.setPeers(stored.map { FilterPoolPeer(it.host, it.port) })
        return stored
    }

    fun emitProgress(
        chainFrom: Int,
        tipTo: Int,
        force: Boolean = false,
    ) {
        val t = now()
        if (!force && t - lastEmitAt < UI_MIN_MS) return
        lastEmitAt = t
        val total = max(0, tipTo - chainFrom + 1)
        ctx.bus.emit(
            Event.FiltersProgress,
            FiltersProgressPayload(at = t, downloaded = min(haveCached.load(), total), total = total),
        )
    }

    fun refreshHaveCached() {
        haveCached.store(ctx.db.filters.count())
    }

    fun wipeFilterTablesFrom(
        height: Int,
        rangeFrom: Int,
    ) {
        ctx.db.wipeFiltersFrom(
            height,
            if (height == rangeFrom && rangeFrom > 0) {
                WipeFiltersFromOptions(prevHeaderHeight = rangeFrom - 1)
            } else {
                null
            },
        )
        hashCheckedThrough = min(hashCheckedThrough, height - 1)
    }

    fun reconcileReorg(
        from: Int,
        to: Int,
    ) {
        val filterHeaderTip = ctx.db.filterHeaders.tip()
        if (filterHeaderTip != null && filterHeaderTip.height > to) {
            wipeFilterTablesFrom(to + 1, from)
        }
        val maxFilter = ctx.db.filters.maxHeight() ?: return
        if (maxFilter > to) wipeFilterTablesFrom(to + 1, from)
        val scanTo = min(maxFilter, to)
        val scanFrom = max(from, hashCheckedThrough + 1)
        if (scanFrom > scanTo) return
        val mismatch = ctx.db.filters.firstHashMismatch(scanFrom, scanTo)
        if (mismatch != null) {
            wipeFilterTablesFrom(mismatch, from)
            return
        }
        hashCheckedThrough = scanTo
    }

    fun checkpointMap(headers: List<ByteArray>): Map<Int, ByteArray> {
        val map = mutableMapOf<Int, ByteArray>()
        for (i in headers.indices) {
            map[(i + 1) * CF_CHECKPT_INTERVAL] = headers[i]
        }
        return map
    }

    fun verifyCfHeadersBatch(
        rangeFrom: Int,
        next: Int,
        stop: Int,
        previousFilterHeader: ByteArray,
        filterHashes: List<ByteArray>,
        checkpoints: Map<Int, ByteArray>,
    ): List<ByteArray>? {
        val count = stop - next + 1
        if (filterHashes.size != count) return null
        val derived = deriveFilterHeaders(previousFilterHeader, filterHashes)
        if (derived.size != count) return null
        for (h in next..stop) {
            val cp = checkpoints[h]
            if (cp != null && !equalBytes(derived[h - next], cp)) return null
        }
        if (next == rangeFrom && !checkpoints.containsKey(rangeFrom)) {
            var checkpointInRange = false
            for (h in next..stop) {
                if (checkpoints.containsKey(h)) checkpointInRange = true
            }
            if (!checkpointInRange) return null
        } else if (next != rangeFrom) {
            val prevRow = ctx.db.filterHeaders.get(next - 1)
            if (prevRow == null || !equalBytes(prevRow.header, previousFilterHeader)) return null
        }
        return derived
    }

    fun firstMissingFilterHeader(
        from: Int,
        to: Int,
    ): Int? {
        if (ctx.db.filterHeaders.get(from) == null) return from
        val tip = ctx.db.filterHeaders.tip() ?: return from
        if (tip.height < from) return from
        if (tip.height >= to) return null
        return tip.height + 1
    }

    fun buildHeaderClaim(
        cursor: Int,
        chainFrom: Int,
        tipTo: Int,
    ): HeightRange? {
        if (cursor > tipTo) return null
        var stop = min(cursor + headerBatchSize - 1, tipTo)
        if (cursor == chainFrom && !isBip157CheckpointHeight(chainFrom)) {
            val nextCp = nextCheckpointHeight(chainFrom)
            if (nextCp > tipTo) return null
            stop = min(tipTo, max(stop, nextCp))
            stop = min(stop, cursor + MAX_GETCFHEADERS_RANGE - 1)
        }
        return HeightRange(cursor, stop)
    }

    suspend fun syncFilterHeadersPhase(
        chainFrom: Int,
        tipTo: Int,
        tipHashInternalHex: String,
    ): Boolean {
        var checkpointCache: Pair<String, Map<Int, ByteArray>>? = null
        while (!isStopped()) {
            val missing = firstMissingFilterHeader(chainFrom, tipTo) ?: return true
            val claim = buildHeaderClaim(missing, chainFrom, tipTo)
            if (claim == null) {
                waitForKick(idleDelayMs)
                return false
            }
            var ok = false
            try {
                val leased =
                    pool.withSession { session, peer ->
                        val startedAt = now()
                        if (ctx.db.filterHeaders.get(claim.from) != null) {
                            ok = true
                            return@withSession
                        }
                        try {
                            val cache = checkpointCache
                            if (cache == null || cache.first != tipHashInternalHex) {
                                val cpHeaders = session.getCFCheckpt(hexToBytes(tipHashInternalHex))
                                checkpointCache = tipHashInternalHex to checkpointMap(cpHeaders)
                            }
                            val stopRow = ctx.db.headers.get(claim.to) ?: error("missing stop header")
                            val stopHashInternalHex = stopRow.hashInternalHex
                            val response = session.getCFHeaders(claim.from, hexToBytes(stopHashInternalHex))
                            val derived =
                                verifyCfHeadersBatch(
                                    chainFrom,
                                    claim.from,
                                    claim.to,
                                    response.previousFilterHeader,
                                    response.filterHashes,
                                    checkpointCache!!.second,
                                ) ?: error("cfheaders verification failed")
                            val stopNow = ctx.db.headers.get(claim.to)
                            if (stopNow == null || stopNow.hashInternalHex != stopHashInternalHex) {
                                error("stale cfheaders stop hash after reorg")
                            }
                            val rows = mutableListOf<FilterHeaderRecord>()
                            if (claim.from == chainFrom && chainFrom > 0) {
                                val prevHeight = chainFrom - 1
                                val prevHeader = response.previousFilterHeader
                                val existing = ctx.db.filterHeaders.get(prevHeight)
                                if (existing != null) {
                                    if (!equalBytes(existing.header, prevHeader)) {
                                        error("cfheaders previous header mismatch")
                                    }
                                } else {
                                    rows.add(FilterHeaderRecord(prevHeight, prevHeader.copyOf()))
                                }
                            }
                            for (i in derived.indices) {
                                rows.add(FilterHeaderRecord(claim.from + i, derived[i].copyOf()))
                            }
                            ctx.db.filterHeaders.append(rows)
                            ctx.db.peers.markAlive(peer.host, peer.port, true)
                            diagnosticLog(
                                "header batch success range=${claim.from}-${claim.to} peer=${peer.host}:${peer.port} received=${response.filterHashes.size} saved=${rows.size} elapsedMs=${max(
                                    0,
                                    now() - startedAt,
                                )}",
                            )
                            ok = true
                        } catch (err: Throwable) {
                            diagnosticLog(
                                "header batch failure range=${claim.from}-${claim.to} peer=${peer.host}:${peer.port} elapsedMs=${max(
                                    0,
                                    now() - startedAt,
                                )} error=${formatError(err)}",
                            )
                            throw err
                        }
                    }
                if (leased == null || !ok) {
                    if (refreshPeers().isEmpty()) waitForKick(idleDelayMs) else waitForKick(50)
                    continue
                }
            } catch (_: Throwable) {
                if (refreshPeers().isEmpty()) waitForKick(idleDelayMs) else waitForKick(50)
                continue
            }
        }
        return false
    }

    suspend fun downloadFilterRange(
        session: FilterSessionApi,
        peer: PeerRef,
        range: HeightRange,
        chainFrom: Int,
        tipTo: Int,
    ): Int {
        val startedAt = now()
        val stopRow = ctx.db.headers.get(range.to) ?: error("missing stop header")
        val expectCount = range.to - range.from + 1
        var received = 0
        var receivedBytes = 0
        val blockHeaders = ctx.db.headers.loadRange(range.from, range.to)
        val hashToHeight = blockHeaders.associate { it.hashInternalHex to it.height }
        val fhRows = ctx.db.filterHeaders.loadRange(max(0, range.from - 1), range.to)
        val filterHeaderByHeight = fhRows.associate { it.height to it.header }
        val receivedHeights = mutableSetOf<Int>()
        val toStore = mutableListOf<FilterRecord>()
        var saved = 0

        suspend fun flushVerified() {
            if (toStore.isEmpty()) return
            val rows = toStore.toList()
            toStore.clear()
            persistLock.withLock {
                val canonical =
                    rows.filter {
                        ctx.db.headers
                            .get(it.height)
                            ?.hashInternalHex == it.blockHashInternalHex &&
                            !ctx.db.filters.has(it.height)
                    }
                if (canonical.size < rows.size) {
                    diagnosticLog(
                        "filter flush dropped stale range=${range.from}-${range.to} dropped=${rows.size - canonical.size}",
                    )
                }
                if (canonical.isEmpty()) return
                ctx.db.filters.append(canonical)
                saved += canonical.size
                haveCached.store(haveCached.load() + canonical.size)
            }
            emitProgress(chainFrom, tipTo, false)
        }

        fun accept(msg: CFilterItem) {
            val hashHex = bytesToHex(msg.blockHash)
            val height = hashToHeight[hashHex]
            if (height == null || height < range.from || height > range.to) {
                error("cfilter block hash out of range")
            }
            val expected = filterHeaderByHeight[height] ?: error("missing filter header")
            val prev = if (height == 0) ByteArray(32) else filterHeaderByHeight[height - 1]
            if (prev == null) error("missing previous filter header")
            if (!verifyCFilterAgainstHeader(msg.filterBytes, prev, expected)) {
                error("cfilter verification failed")
            }
            if (height in receivedHeights) error("duplicate cfilter height $height")
            receivedHeights.add(height)
            received++
            receivedBytes += msg.filterBytes.size
            if (!ctx.db.filters.has(height)) {
                toStore.add(FilterRecord(height, hashHex, msg.filterBytes))
            }
        }

        try {
            var streamed = false
            val filters =
                session.getCFilters(range.from, hexToBytes(stopRow.hashInternalHex), expectCount) { msg ->
                    streamed = true
                    accept(msg)
                    if (toStore.size >= persistBatchSize) flushVerified()
                }
            if (!streamed) {
                for (msg in filters) {
                    accept(msg)
                    if (toStore.size >= persistBatchSize) flushVerified()
                }
            }
            flushVerified()
            persistLock.withLock { ctx.db.peers.markAlive(peer.host, peer.port, true) }
            diagnosticLog(
                "filter batch success range=${range.from}-${range.to} peer=${peer.host}:${peer.port} received=$received saved=$saved bytes=$receivedBytes elapsedMs=${max(
                    0,
                    now() - startedAt,
                )}",
            )
            return saved
        } catch (err: Throwable) {
            var persistenceError: Throwable? = null
            try {
                flushVerified()
            } catch (flushErr: Throwable) {
                persistenceError = flushErr
            }
            diagnosticLog(
                "filter batch failure range=${range.from}-${range.to} peer=${peer.host}:${peer.port} received=$received saved=$saved bytes=$receivedBytes elapsedMs=${max(
                    0,
                    now() - startedAt,
                )} error=${formatError(err)}" +
                    if (persistenceError == null) "" else " persistenceError=${formatError(persistenceError)}",
            )
            if (persistenceError != null) {
                throw Exception("filter batch and final persistence failed")
            }
            throw err
        }
    }

    suspend fun syncFiltersPhase(
        chainFrom: Int,
        tipTo: Int,
    ) {
        val initial = ctx.db.filters.missingRanges(chainFrom, tipTo, filterBatchSize)
        val queue = ArrayDeque(initial)
        val queueLock = Mutex()
        val missing = initial.sumOf { it.to - it.from + 1 }
        diagnosticLog("filter queue range=$chainFrom-$tipTo batches=${initial.size} missing=$missing")
        if (initial.isEmpty()) return
        val failures = mutableMapOf<String, Int>()
        val workerCount = min(concurrency, max(1, initial.size))
        coroutineScope {
            repeat(workerCount) {
                launch {
                    while (!isStopped()) {
                        val range =
                            queueLock.withLock {
                                if (queue.isEmpty()) null else queue.removeFirst()
                            } ?: break
                        val key = "${range.from}-${range.to}"
                        try {
                            val saved =
                                pool.withSession { session, peer ->
                                    downloadFilterRange(session, PeerRef(peer.host, peer.port), range, chainFrom, tipTo)
                                }
                            if (saved == null) {
                                queueLock.withLock { queue.addLast(range) }
                                val coolWait = min(1_000L, pool.coolDelayMs().let { if (it == 0L) 50L else it })
                                waitForKick(coolWait)
                                continue
                            }
                            queueLock.withLock { failures.remove(key) }
                        } catch (_: Throwable) {
                            val attempts =
                                queueLock.withLock {
                                    val n = (failures[key] ?: 0) + 1
                                    failures[key] = n
                                    n
                                }
                            val remaining =
                                if (attempts <= 8) {
                                    ctx.db.filters.missingRanges(range.from, range.to, filterBatchSize)
                                } else {
                                    emptyList()
                                }
                            if (remaining.isNotEmpty()) queueLock.withLock { queue.addAll(remaining) }
                            diagnosticLog(
                                "filter batch retry range=$key failure=$attempts/9 action=${if (attempts > 8) {
                                    "drop"
                                } else if (remaining.isNotEmpty()) {
                                    "requeue"
                                } else {
                                    "complete"
                                }} remaining=${remaining.joinToString(",") { "${it.from}-${it.to}" }.ifEmpty { "none" }}",
                            )
                            val coolWait = min(1_000L, pool.coolDelayMs().let { if (it == 0L) 50L else it })
                            waitForKick(coolWait)
                        }
                    }
                }
            }
        }
        emitProgress(chainFrom, tipTo, true)
    }

    lateinit var requestRun: (String) -> Unit

    suspend fun runDownload() {
        options.onDownloadRun?.invoke()
        val runId = ++runSequence
        val runStartedAt = now()
        diagnosticLog("run start id=$runId")
        while (!isStopped()) {
            try {
                val birthday = inspectWalletBirthday(ctx.db)
                if (birthday is WalletBirthdayInspection.Pending) {
                    ctx.bus.emit(Event.FiltersProgress, FiltersProgressPayload(now(), 0, 0))
                    waitForKick(idleDelayMs)
                    continue
                }
                val minH = ctx.db.headers.minHeight()
                val tip = ctx.db.headers.tip()
                if (minH == null || tip == null) {
                    ctx.bus.emit(Event.FiltersProgress, FiltersProgressPayload(now(), 0, 0))
                    break
                }
                val filterFrom = compactFilterFrom(ctx.db)
                if (filterFrom == null) {
                    ctx.bus.emit(Event.FiltersProgress, FiltersProgressPayload(now(), 0, 0))
                    break
                }
                if (tip.height < filterFrom) {
                    waitForKick(idleDelayMs)
                    continue
                }
                val chainFrom =
                    if (birthday is WalletBirthdayInspection.Ok) {
                        max(minH, (filterFrom / CF_CHECKPT_INTERVAL) * CF_CHECKPT_INTERVAL)
                    } else {
                        minH
                    }
                val tipTo = tip.height
                reconcileReorg(chainFrom, tipTo)
                refreshHaveCached()
                emitProgress(filterFrom, tipTo, true)
                val peers = refreshPeers()
                diagnosticLog(
                    "sync plan filterRange=$filterFrom-$tipTo headerRange=$chainFrom-$tipTo cached=${haveCached.load()} peers=${peers.size}",
                )
                if (peers.isEmpty()) {
                    waitForKick(idleDelayMs)
                    continue
                }
                val headersDone = syncFilterHeadersPhase(chainFrom, tipTo, tip.hashInternalHex)
                if (!headersDone) {
                    if (isStopped()) break
                    continue
                }
                syncFiltersPhase(filterFrom, tipTo)
                refreshHaveCached()
                emitProgress(filterFrom, tipTo, true)
                if (ctx.db.filters.completeInRange(filterFrom, tipTo)) {
                    diagnosticLog(
                        "run complete id=$runId range=$filterFrom-$tipTo cached=${haveCached.load()} remaining=0 elapsedMs=${max(
                            0,
                            now() - runStartedAt,
                        )}",
                    )
                    pool.closeAll()
                    break
                }
                waitForKick(50)
            } catch (err: Throwable) {
                diagnosticLog(
                    "run failure id=$runId elapsedMs=${max(0, now() - runStartedAt)} error=${formatError(err)}",
                )
                waitForKick(idleDelayMs)
            }
        }
    }

    requestRun = { _ ->
        if (!isStopped()) {
            runRequests.trySend(Unit)
            kick()
        }
    }

    return object : Module {
        override val name = "filters-download"

        override suspend fun start() {
            if (!isStopped()) return
            ctx.bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(module = "filters-download", status = ModuleStatus.STARTING),
            )
            diagnosticLog(
                "module start concurrency=$concurrency filterBatchSize=$filterBatchSize persistBatchSize=$persistBatchSize headerBatchSize=$headerBatchSize connectTimeoutMs=$connectTimeoutMs syncTimeoutMs=$syncTimeoutMs",
            )
            stopped.store(false)
            unsubHeaders =
                ctx.bus.on(Event.HeadersProgress) {
                    kick()
                    requestRun("headers")
                }
            unsubIdle = ctx.bus.on(Event.SyncIdle) { quiet = true }
            unsubCatchup =
                ctx.bus.on(Event.SyncCatchup) {
                    quiet = false
                    requestRun("peers")
                }
            unsubPeers =
                ctx.bus.on(Event.PeersUpdated) {
                    kick()
                    if (quiet) return@on
                    requestRun("peers")
                }
            val job = SupervisorJob()
            parentJob = job
            val scope = CoroutineScope(job + Dispatchers.Default)
            moduleScope = scope
            while (runRequests.tryReceive().isSuccess) {}
            val launched =
                scope.launch {
                    while (isActive) {
                        runRequests.receive()
                        if (isStopped()) break
                        runDownload()
                    }
                }
            loopJob = launched
            detachLoop(ctx, "filters-download", launched)
            requestRun("start")
            ctx.bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(module = "filters-download", status = ModuleStatus.RUNNING),
            )
        }

        override fun stop() {
            if (isStopped()) return
            stopped.store(true)
            unsubHeaders?.invoke()
            unsubHeaders = null
            unsubIdle?.invoke()
            unsubIdle = null
            unsubCatchup?.invoke()
            unsubCatchup = null
            unsubPeers?.invoke()
            unsubPeers = null
            kick()
            runRequests.trySend(Unit)
            runBlocking {
                pool.closeAll()
                loopJob?.join()
                parentJob?.cancel()
            }
            loopJob = null
            parentJob = null
            moduleScope = null
            diagnosticLog("module stopped")
            ctx.bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(module = "filters-download", status = ModuleStatus.STOPPED),
            )
        }
    }
}
