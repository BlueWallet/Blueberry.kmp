@file:OptIn(ExperimentalAtomicApi::class)

package io.bluewallet.blueberry.broadcast

import io.bluewallet.bip324.ByteDuplex
import io.bluewallet.bip324.bytesToHex
import io.bluewallet.bip324.transactionId
import io.bluewallet.blueberry.bus.BroadcastDonePayload
import io.bluewallet.blueberry.bus.BroadcastHandedOffPayload
import io.bluewallet.blueberry.bus.BroadcastPhase
import io.bluewallet.blueberry.bus.BroadcastProgressPayload
import io.bluewallet.blueberry.bus.BroadcastRequestPayload
import io.bluewallet.blueberry.bus.BroadcastStopPayload
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.ModuleStatus
import io.bluewallet.blueberry.bus.ModuleStatusPayload
import io.bluewallet.blueberry.headers.internalHexToDisplayHex
import io.bluewallet.blueberry.peers.log
import io.bluewallet.blueberry.peers.logError
import io.bluewallet.blueberry.peers.modules.Module
import io.bluewallet.blueberry.peers.modules.ModuleContext
import io.bluewallet.blueberry.peers.net.APP_NAME
import io.bluewallet.blueberry.peers.net.APP_VERSION
import io.bluewallet.blueberry.storage.Peer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.floor

/** Bitcoin Core `NODE_P2P_V2` — peer advertised BIP-324. */
private const val NODE_P2P_V2 = 2048uL
private const val ALIVE_PEER_PICK_LIMIT = 512
private const val MAX_ATTEMPTS_DEFAULT = 20
private const val DIALER_ATTEMPTS_DEFAULT = 3
internal const val BROADCAST_SUCCESS_PEERS = 3

data class BroadcastModuleOptions(
    val connect: (suspend (host: String, port: Int, job: Job) -> ByteDuplex)? = null,
    val createDialer: (() -> TorByteDuplexDialer)? = null,
    val disposeConnect: (suspend () -> Unit)? = null,
    val maxAttempts: Int = MAX_ATTEMPTS_DEFAULT,
    val dialerAttempts: Int = DIALER_ATTEMPTS_DEFAULT,
    val peerWaitPollMs: Long = 500,
    val peerWaitTimeoutMs: Long = 120_000,
    val dialTimeoutMs: Long = 45_000,
    val handshakeTimeoutMs: Long = 15_000,
    val ackTimeoutMs: Long = 15_000,
    val random: () -> Double = { kotlin.random.Random.nextDouble() },
)

private fun peerKey(p: Peer): String = "${p.host}:${p.port}"

private fun pickAlive(
    peers: List<Peer>,
    random: () -> Double,
    exclude: Set<String> = emptySet(),
): Peer? {
    val pool = peers.filter { peerKey(it) !in exclude }
    if (pool.isEmpty()) return null
    return pool[floor(random() * pool.size).toInt().coerceIn(0, pool.lastIndex)]
}

private fun formatError(err: Throwable): String = err.message?.takeIf { it.isNotBlank() } ?: err.toString()

/**
 * Peers that acknowledged the transaction, peers that were given the raw transaction, and handed peers that
 * then sent an explicit `reject`. A handed peer that neither acked nor refused may still hold and relay it.
 */
private class PeerLedger(
    private val onFirstHandOff: () -> Unit = {},
) {
    val accepted = linkedSetOf<String>()
    val handed = linkedSetOf<String>()
    val refused = linkedSetOf<String>()

    fun hand(key: String) {
        val first = handed.isEmpty() && accepted.isEmpty()
        handed += key
        if (first) onFirstHandOff()
    }

    /** Peers that may hold the transaction: acked, or handed without a refusal. */
    fun holders(): Set<String> = accepted + (handed - refused)

    /** Every node that was given the transaction refused it, so none of them holds it. */
    fun allRefused(): Boolean = holders().isEmpty() && refused.isNotEmpty()
}

private const val PEER_REJECTED = "peer rejected transaction"

/** The error a job reports when the app stops before any node was given the transaction. */
const val BROADCAST_STOPPED = "The app stopped before any node got it."

/** The error a job reports when the user stops it before any node was given the transaction. */
const val BROADCAST_CANCELLED = "Stopped before any node got it."

/** The error a job reports when no BIP324 node shows up within the wait. */
const val BROADCAST_NO_PEERS = "No Bitcoin nodes found. Check the connection and retry."

/** The error a job reports when every node that got the transaction refused it. */
const val BROADCAST_REFUSED = "Every node that got it refused it."

private fun failedSummary(
    failures: List<String>,
    attemptsUsed: Int,
): String {
    val summary = if (failures.isNotEmpty()) failures.takeLast(3).joinToString(" | ") else "no alive peers"
    val error = if (attemptsUsed > 0) "broadcast failed after $attemptsUsed attempts: $summary" else "broadcast failed: $summary"
    log("broadcast", error)
    return error
}

private fun displayTxid(txHex: String): String = internalHexToDisplayHex(bytesToHex(transactionId(decodeBroadcastTx(txHex))))

fun createBroadcastModule(
    ctx: ModuleContext,
    options: BroadcastModuleOptions = BroadcastModuleOptions(),
): Module {
    val maxAttempts = options.maxAttempts
    val dialerAttempts = options.dialerAttempts
    val injectedConnect = options.connect
    val injectedDialer = options.createDialer
    val injectedDispose = options.disposeConnect

    val stopped = AtomicBoolean(true)
    var unsubRequest: (() -> Unit)? = null
    var unsubStop: (() -> Unit)? = null
    val jobs = AtomicReference<Map<String, Job>>(emptyMap())
    val handedIds = AtomicReference<Set<String>>(emptySet())

    fun updateHanded(transform: (Set<String>) -> Set<String>) {
        while (true) {
            val cur = handedIds.load()
            if (handedIds.compareAndSet(cur, transform(cur))) return
        }
    }
    var scope: CoroutineScope? = null

    fun putJob(
        id: String,
        job: Job,
    ) {
        while (true) {
            val cur = jobs.load()
            if (jobs.compareAndSet(cur, cur + (id to job))) return
        }
    }

    fun takeJob(
        id: String,
        job: Job,
    ) {
        while (true) {
            val cur = jobs.load()
            if (cur[id] !== job) return
            if (jobs.compareAndSet(cur, cur - id)) return
        }
    }

    fun alivePeers(limit: Int): List<Peer> = ctx.db.peers.listAliveWithServices(NODE_P2P_V2, limit)

    suspend fun waitForAlivePeers(job: Job): Int {
        var waitedMs = 0L
        while (alivePeers(1).isEmpty()) {
            job.ensureActive()
            if (waitedMs == 0L) log("broadcast", "waiting-peers")
            check(waitedMs < options.peerWaitTimeoutMs) { BROADCAST_NO_PEERS }
            delay(options.peerWaitPollMs)
            waitedMs += options.peerWaitPollMs
        }
        return alivePeers(ALIVE_PEER_PICK_LIMIT).size
    }

    suspend fun attemptOne(
        dial: suspend (String, Int, Job) -> ByteDuplex,
        peer: Peer,
        txHex: String,
        job: Job,
        ledger: PeerLedger,
    ): Boolean {
        job.ensureActive()
        val key = peerKey(peer)
        log("broadcast", "dial start peer=$key timeoutMs=${options.dialTimeoutMs}")
        val duplex =
            try {
                withTimeout(options.dialTimeoutMs) {
                    dial(peer.host, peer.port, currentCoroutineContext()[Job]!!)
                }
            } catch (err: TimeoutCancellationException) {
                logError("broadcast", "dial fail peer=$key", err)
                throw IllegalStateException("dial timeout")
            } catch (err: Throwable) {
                logError("broadcast", "dial fail peer=$key", err)
                throw err
            }
        return try {
            withContext(HandedOff { ledger.hand(key) }) {
                broadcastTxV2(
                    duplex,
                    txHex,
                    BroadcastTxV2Options(
                        port = peer.port,
                        name = APP_NAME,
                        version = APP_VERSION,
                        handshakeTimeoutMs = options.handshakeTimeoutMs,
                        ackTimeoutMs = options.ackTimeoutMs,
                    ),
                )
            }
        } finally {
            try {
                duplex.close()
            } catch (err: Throwable) {
                logError("broadcast", "duplex close fail peer=$key", err)
            }
        }
    }

    suspend fun runPeerAttempts(
        dial: suspend (String, Int, Job) -> ByteDuplex,
        txHex: String,
        job: Job,
        emitProgress: (BroadcastPhase, String?, String?, Int?, String?) -> Unit,
        ledger: PeerLedger = PeerLedger(),
        exclude: Set<String> = emptySet(),
        failed: MutableSet<String> = linkedSetOf(),
    ): Pair<String?, List<String>> {
        val failures = mutableListOf<String>()

        fun accepted(): String? = if (ledger.accepted.isNotEmpty()) ledger.accepted.joinToString(", ") else null
        for (attempt in 1..maxAttempts) {
            if (ledger.accepted.size >= BROADCAST_SUCCESS_PEERS) {
                break
            }
            job.ensureActive()
            val peers = alivePeers(ALIVE_PEER_PICK_LIMIT)
            val given = ledger.accepted + ledger.handed
            val peer =
                pickAlive(peers, options.random, given + failed + exclude)
                    ?: if (ledger.accepted.isEmpty()) pickAlive(peers, options.random, given + exclude) else null
            if (peer == null) {
                log("broadcast", "attempt $attempt/$maxAttempts no-unused-alive-peer ok=${ledger.accepted.size}")
                return accepted() to failures
            }
            val key = peerKey(peer)
            log("broadcast", "attempt $attempt/$maxAttempts peer=$key ok=${ledger.accepted.size}/$BROADCAST_SUCCESS_PEERS")
            emitProgress(BroadcastPhase.ATTEMPT, key, null, null, null)
            try {
                if (attemptOne(dial, peer, txHex, job, ledger)) {
                    ledger.accepted += key
                } else {
                    log("broadcast", "attempt peer=$key handed, not acknowledged")
                }
            } catch (err: CancellationException) {
                throw err
            } catch (err: Throwable) {
                val detail = formatError(err)
                if (detail == PEER_REJECTED && key in ledger.handed) ledger.refused += key
                failures += "$key: $detail"
                failed += key
                emitProgress(BroadcastPhase.FAILED_ATTEMPT, key, detail, null, null)
            }
        }
        return accepted() to failures
    }

    suspend fun runTorAttempts(
        txHex: String,
        job: Job,
        emitProgress: (BroadcastPhase, String?, String?, Int?, String?) -> Unit,
        ledger: PeerLedger,
        excludePeers: Set<String>,
    ): Pair<String?, List<String>> {
        val failures = mutableListOf<String>()
        val peer =
            withTorDialRetries(
                injectedDialer ?: { createTorByteDuplexDialer() },
                { dialer ->
                    val cycleFailed = linkedSetOf<String>()
                    val (accepted, fails) =
                        runPeerAttempts(
                            { host, port, j ->
                                dialer.dial(host, port, j) { percent, stage ->
                                    emitProgress(BroadcastPhase.ATTEMPT, "$host:$port", null, percent, stage)
                                }
                            },
                            txHex,
                            job,
                            emitProgress,
                            ledger,
                            excludePeers,
                            cycleFailed,
                        )
                    failures += fails
                    val unusedAlive =
                        alivePeers(ALIVE_PEER_PICK_LIMIT).any { candidate ->
                            val key = peerKey(candidate)
                            key !in ledger.accepted && key !in ledger.handed && key !in cycleFailed && key !in excludePeers
                        }
                    if (ledger.accepted.size >= BROADCAST_SUCCESS_PEERS ||
                        (ledger.holders().isNotEmpty() && !unusedAlive)
                    ) {
                        accepted ?: ledger.holders().joinToString(", ")
                    } else {
                        throw IllegalStateException(
                            fails.takeLast(3).joinToString(" | ").ifEmpty { "no alive peers" },
                        )
                    }
                },
                TorDialRetryOptions(attempts = dialerAttempts),
            )
        return peer to failures
    }

    fun emitProgress(
        id: String,
        attemptBudget: Int,
        attemptsUsed: IntArray,
        phase: BroadcastPhase,
        peer: String? = null,
        detail: String? = null,
        percent: Int? = null,
        stage: String? = null,
    ) {
        if (phase == BroadcastPhase.ATTEMPT && percent == null) attemptsUsed[0] += 1
        ctx.bus.emit(
            Event.BroadcastProgress,
            BroadcastProgressPayload(
                id = id,
                phase = phase,
                attempt =
                    if (phase == BroadcastPhase.ATTEMPT || phase == BroadcastPhase.FAILED_ATTEMPT) {
                        attemptsUsed[0]
                    } else {
                        null
                    },
                maxAttempts = attemptBudget,
                peer = peer,
                detail = detail,
                percent = percent,
                stage = stage,
            ),
        )
    }

    /**
     * Sent while any node may hold the transaction. Failed when none was given it, or every node that was
     * explicitly refused it. [abortMessage] is the reason a job ended early without a hand-off.
     */
    fun emitOutcome(
        id: String,
        ledger: PeerLedger,
        failures: List<String>,
        attemptsUsed: Int,
        abortMessage: String? = null,
    ) {
        val holders = ledger.holders()
        val done =
            when {
                holders.isNotEmpty() -> {
                    log("broadcast", "done ok peer=$holders acked=${ledger.accepted.size}")
                    BroadcastDonePayload.Ok(id, holders.joinToString(", "), acknowledged = ledger.accepted.isNotEmpty())
                }
                ledger.allRefused() -> {
                    log("broadcast", "done refused by=${ledger.refused}")
                    BroadcastDonePayload.Error(id, BROADCAST_REFUSED, refused = true)
                }
                abortMessage != null -> {
                    emitProgress(id, 0, intArrayOf(attemptsUsed), BroadcastPhase.ERROR, detail = abortMessage)
                    BroadcastDonePayload.Error(id, abortMessage)
                }
                else -> BroadcastDonePayload.Error(id, failedSummary(failures, attemptsUsed))
            }
        ctx.bus.emit(Event.BroadcastDone, done)
    }

    suspend fun runBroadcast(
        id: String,
        txHex: String,
        excludePeers: Set<String> = emptySet(),
    ) {
        val job = kotlinx.coroutines.currentCoroutineContext()[Job]!!
        val attemptBudget = if (injectedConnect != null) maxAttempts else maxAttempts * dialerAttempts
        val attemptsUsed = intArrayOf(0)
        val emit: (BroadcastPhase, String?, String?, Int?, String?) -> Unit =
            { phase, peer, detail, percent, stage ->
                emitProgress(id, attemptBudget, attemptsUsed, phase, peer, detail, percent, stage)
            }
        val ledger =
            PeerLedger {
                updateHanded { it + id }
                ctx.bus.emit(Event.BroadcastHandedOff, BroadcastHandedOffPayload(id))
            }
        val failures = mutableListOf<String>()
        try {
            log("broadcast", "start id=$id txid=${displayTxid(txHex)}")
            emit(BroadcastPhase.WAITING_PEERS, null, null, null, null)
            waitForAlivePeers(job)
            if (injectedConnect != null) {
                failures += runPeerAttempts(injectedConnect, txHex, job, emit, ledger, excludePeers).second
            } else {
                try {
                    failures += runTorAttempts(txHex, job, emit, ledger, excludePeers).second
                } catch (err: CancellationException) {
                    throw err
                } catch (err: Throwable) {
                    if (failures.isEmpty()) failures += formatError(err)
                }
            }
            emitOutcome(id, ledger, failures, attemptsUsed[0])
        } catch (err: CancellationException) {
            emitOutcome(id, ledger, failures, attemptsUsed[0], formatError(err).ifBlank { BROADCAST_STOPPED })
        } catch (err: Throwable) {
            logError("broadcast", "aborted/error", err)
            emitOutcome(id, ledger, failures, attemptsUsed[0], formatError(err))
        }
    }

    return object : Module {
        override val name: String = "broadcast"

        override suspend fun start() {
            stopped.store(false)
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            ctx.bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(module = "broadcast", status = ModuleStatus.RUNNING),
            )
            unsubRequest =
                ctx.bus.on(Event.BroadcastRequest) { payload: BroadcastRequestPayload ->
                    if (stopped.load()) {
                        ctx.bus.emit(Event.BroadcastDone, BroadcastDonePayload.Error(payload.id, BROADCAST_STOPPED))
                        return@on
                    }
                    if (jobs.load()[payload.id]?.isActive == true) {
                        log("broadcast", "reject id=${payload.id} already-in-progress")
                        return@on
                    }
                    val job =
                        checkNotNull(scope).launch {
                            runBroadcast(payload.id, payload.txHex, payload.excludePeers)
                        }
                    updateHanded { it - payload.id }
                    putJob(payload.id, job)
                    job.invokeOnCompletion { takeJob(payload.id, job) }
                }
            unsubStop =
                ctx.bus.on(Event.BroadcastStop) { payload: BroadcastStopPayload ->
                    val job = jobs.load()[payload.id]
                    if (job == null || payload.id in handedIds.load()) {
                        log("broadcast", "stop ignored id=${payload.id}")
                        return@on
                    }
                    log("broadcast", "stop id=${payload.id}")
                    job.cancel(CancellationException(BROADCAST_CANCELLED))
                }
        }

        override fun stop() {
            stopped.store(true)
            unsubRequest?.invoke()
            unsubRequest = null
            unsubStop?.invoke()
            unsubStop = null
            val running = jobs.load().values.toList()
            running.forEach { it.cancel(CancellationException(BROADCAST_STOPPED)) }
            runBlocking {
                running.forEach { job ->
                    try {
                        job.join()
                    } catch (_: Throwable) {
                    }
                }
                try {
                    injectedDispose?.invoke()
                } catch (_: Throwable) {
                }
            }
            jobs.store(emptyMap())
            ctx.bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(module = "broadcast", status = ModuleStatus.STOPPED),
            )
        }
    }
}
