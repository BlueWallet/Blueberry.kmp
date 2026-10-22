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
 * Peers that acknowledged the transaction, and peers that were given the raw transaction.
 * Only an explicit reject takes a peer back off the handed list.
 */
private class PeerLedger(
    private val onFirstHandOff: () -> Unit = {},
) {
    val accepted = linkedSetOf<String>()
    val handed = linkedSetOf<String>()

    fun hand(key: String) {
        val first = handed.isEmpty() && accepted.isEmpty()
        handed += key
        if (first) onFirstHandOff()
    }

    fun summary(): String? = (accepted + handed).takeIf { it.isNotEmpty() }?.joinToString(", ")
}

private const val PEER_REJECTED = "peer rejected transaction"

/** The error a job reports when the app stops before any node was given the transaction. */
const val BROADCAST_STOPPED = "The app stopped before any node got it."

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
    val jobs = AtomicReference<Map<String, Job>>(emptyMap())
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
        var loggedWait = false
        while (alivePeers(1).isEmpty()) {
            job.ensureActive()
            if (!loggedWait) {
                log("broadcast", "waiting-peers")
                loggedWait = true
            }
            delay(options.peerWaitPollMs)
        }
        return alivePeers(ALIVE_PEER_PICK_LIMIT).size
    }

    suspend fun attemptOne(
        dial: suspend (String, Int, Job) -> ByteDuplex,
        peer: Peer,
        txHex: String,
        job: Job,
        ledger: PeerLedger,
    ) {
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
        try {
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
            val tried = ledger.accepted + failed + exclude
            val peer =
                pickAlive(peers, options.random, tried)
                    ?: if (ledger.accepted.isEmpty()) pickAlive(peers, options.random, ledger.accepted + exclude) else null
            if (peer == null) {
                log("broadcast", "attempt $attempt/$maxAttempts no-unused-alive-peer ok=${ledger.accepted.size}")
                return accepted() to failures
            }
            val key = peerKey(peer)
            log("broadcast", "attempt $attempt/$maxAttempts peer=$key ok=${ledger.accepted.size}/$BROADCAST_SUCCESS_PEERS")
            emitProgress(BroadcastPhase.ATTEMPT, key, null, null, null)
            try {
                attemptOne(dial, peer, txHex, job, ledger)
                ledger.accepted += key
            } catch (err: CancellationException) {
                throw err
            } catch (err: Throwable) {
                val detail = formatError(err)
                if (detail == PEER_REJECTED) ledger.handed -= key
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
                            key !in ledger.accepted && key !in cycleFailed && key !in excludePeers
                        }
                    if (ledger.accepted.size >= BROADCAST_SUCCESS_PEERS ||
                        (ledger.accepted.isNotEmpty() && !unusedAlive)
                    ) {
                        accepted ?: ledger.accepted.joinToString(", ")
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

    fun emitOutcome(
        id: String,
        successPeer: String?,
        failures: List<String>,
        attemptsUsed: Int,
    ) {
        if (successPeer != null) {
            log("broadcast", "done ok peer=$successPeer")
            ctx.bus.emit(Event.BroadcastDone, BroadcastDonePayload.Ok(id, successPeer))
            return
        }
        val summary =
            if (failures.isNotEmpty()) {
                failures.takeLast(3).joinToString(" | ")
            } else {
                "no alive peers"
            }
        val error =
            if (attemptsUsed > 0) {
                "broadcast failed after $attemptsUsed attempts: $summary"
            } else {
                "broadcast failed: $summary"
            }
        log("broadcast", error)
        ctx.bus.emit(Event.BroadcastDone, BroadcastDonePayload.Error(id, error))
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
        val ledger = PeerLedger { ctx.bus.emit(Event.BroadcastHandedOff, BroadcastHandedOffPayload(id)) }
        val failures = mutableListOf<String>()
        try {
            log("broadcast", "start id=$id txid=${displayTxid(txHex)}")
            emit(BroadcastPhase.WAITING_PEERS, null, null, null, null)
            waitForAlivePeers(job)
            val successPeer =
                if (injectedConnect != null) {
                    val (peer, fails) =
                        runPeerAttempts(injectedConnect, txHex, job, emit, ledger, excludePeers)
                    failures += fails
                    peer
                } else {
                    try {
                        val (peer, fails) = runTorAttempts(txHex, job, emit, ledger, excludePeers)
                        failures += fails
                        peer
                    } catch (err: CancellationException) {
                        throw err
                    } catch (err: Throwable) {
                        if (failures.isEmpty()) failures += formatError(err)
                        null
                    }
                }
            emitOutcome(id, successPeer ?: ledger.summary(), failures, attemptsUsed[0])
        } catch (err: CancellationException) {
            val peers = ledger.summary()
            if (peers != null) {
                emitOutcome(id, peers, failures, attemptsUsed[0])
            } else {
                val message = formatError(err).ifBlank { BROADCAST_STOPPED }
                emit(BroadcastPhase.ERROR, null, message, null, null)
                ctx.bus.emit(Event.BroadcastDone, BroadcastDonePayload.Error(id, message))
            }
        } catch (err: Throwable) {
            val message = formatError(err)
            logError("broadcast", "aborted/error", err)
            emit(BroadcastPhase.ERROR, null, message, null, null)
            ctx.bus.emit(Event.BroadcastDone, BroadcastDonePayload.Error(id, message))
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
                    if (stopped.load()) return@on
                    if (jobs.load()[payload.id]?.isActive == true) {
                        log("broadcast", "reject id=${payload.id} already-in-progress")
                        return@on
                    }
                    val job =
                        checkNotNull(scope).launch {
                            runBroadcast(payload.id, payload.txHex, payload.excludePeers)
                        }
                    putJob(payload.id, job)
                    job.invokeOnCompletion { takeJob(payload.id, job) }
                }
        }

        override fun stop() {
            stopped.store(true)
            unsubRequest?.invoke()
            unsubRequest = null
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
