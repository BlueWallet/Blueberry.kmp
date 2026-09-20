@file:OptIn(ExperimentalAtomicApi::class)

package io.bluewallet.blueberry.broadcast

import io.bluewallet.bip324.ByteDuplex
import io.bluewallet.bip324.bytesToHex
import io.bluewallet.bip324.transactionId
import io.bluewallet.blueberry.bus.BroadcastCancelPayload
import io.bluewallet.blueberry.bus.BroadcastDonePayload
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
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicBoolean
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
    var unsubCancel: (() -> Unit)? = null
    var activeId: String? = null
    var activeJob: Job? = null
    var scope: CoroutineScope? = null

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
        successes: MutableSet<String> = linkedSetOf(),
        exclude: Set<String> = emptySet(),
        failed: MutableSet<String> = linkedSetOf(),
    ): Pair<String?, List<String>> {
        val failures = mutableListOf<String>()

        fun accepted(): String? = if (successes.isNotEmpty()) successes.joinToString(", ") else null
        for (attempt in 1..maxAttempts) {
            if (successes.size >= BROADCAST_SUCCESS_PEERS) {
                break
            }
            job.ensureActive()
            val peers = alivePeers(ALIVE_PEER_PICK_LIMIT)
            val tried = successes + failed + exclude
            val peer =
                pickAlive(peers, options.random, tried)
                    ?: if (successes.isEmpty()) pickAlive(peers, options.random, successes + exclude) else null
            if (peer == null) {
                log("broadcast", "attempt $attempt/$maxAttempts no-unused-alive-peer ok=${successes.size}")
                return accepted() to failures
            }
            val key = peerKey(peer)
            log("broadcast", "attempt $attempt/$maxAttempts peer=$key ok=${successes.size}/$BROADCAST_SUCCESS_PEERS")
            emitProgress(BroadcastPhase.ATTEMPT, key, null, null, null)
            try {
                attemptOne(dial, peer, txHex, job)
                successes += key
            } catch (err: CancellationException) {
                throw err
            } catch (err: Throwable) {
                val detail = formatError(err)
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
        successes: MutableSet<String>,
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
                            successes,
                            excludePeers,
                            cycleFailed,
                        )
                    failures += fails
                    val unusedAlive =
                        alivePeers(ALIVE_PEER_PICK_LIMIT).any { candidate ->
                            val key = peerKey(candidate)
                            key !in successes && key !in cycleFailed && key !in excludePeers
                        }
                    if (successes.size >= BROADCAST_SUCCESS_PEERS ||
                        (successes.isNotEmpty() && !unusedAlive)
                    ) {
                        accepted ?: successes.joinToString(", ")
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
        try {
            log("broadcast", "start id=$id txid=${displayTxid(txHex)}")
            emit(BroadcastPhase.WAITING_PEERS, null, null, null, null)
            waitForAlivePeers(job)
            val successes = linkedSetOf<String>()
            val failures = mutableListOf<String>()
            val successPeer =
                if (injectedConnect != null) {
                    val (peer, fails) =
                        runPeerAttempts(injectedConnect, txHex, job, emit, successes, excludePeers)
                    failures += fails
                    peer
                } else {
                    try {
                        val (peer, fails) = runTorAttempts(txHex, job, emit, successes, excludePeers)
                        failures += fails
                        peer
                    } catch (err: CancellationException) {
                        throw err
                    } catch (err: Throwable) {
                        if (successes.isNotEmpty()) {
                            successes.joinToString(", ")
                        } else {
                            if (failures.isEmpty()) failures += formatError(err)
                            null
                        }
                    }
                }
            emitOutcome(id, successPeer, failures, attemptsUsed[0])
        } catch (err: CancellationException) {
            val message = formatError(err).ifBlank { "cancelled" }
            emit(BroadcastPhase.ERROR, null, message, null, null)
            ctx.bus.emit(Event.BroadcastDone, BroadcastDonePayload.Error(id, message))
        } catch (err: Throwable) {
            val message = formatError(err)
            logError("broadcast", "aborted/error", err)
            emit(BroadcastPhase.ERROR, null, message, null, null)
            ctx.bus.emit(Event.BroadcastDone, BroadcastDonePayload.Error(id, message))
        } finally {
            if (activeId == id) {
                activeId = null
                activeJob = null
            }
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
                    if (activeId != null) {
                        log("broadcast", "reject id=${payload.id} already-in-progress activeId=$activeId")
                        ctx.bus.emit(
                            Event.BroadcastDone,
                            BroadcastDonePayload.Error(payload.id, "broadcast already in progress"),
                        )
                        return@on
                    }
                    activeId = payload.id
                    val job =
                        checkNotNull(scope).launch {
                            runBroadcast(payload.id, payload.txHex, payload.excludePeers)
                        }
                    activeJob = job
                }
            unsubCancel =
                ctx.bus.on(Event.BroadcastCancel) { payload: BroadcastCancelPayload ->
                    if (activeId == payload.id) {
                        activeJob?.cancel(CancellationException("cancelled"))
                    }
                }
        }

        override fun stop() {
            stopped.store(true)
            unsubRequest?.invoke()
            unsubCancel?.invoke()
            unsubRequest = null
            unsubCancel = null
            activeJob?.cancel(CancellationException("cancelled"))
            runBlocking {
                try {
                    activeJob?.join()
                } catch (_: Throwable) {
                }
                try {
                    injectedDispose?.invoke()
                } catch (_: Throwable) {
                }
            }
            activeId = null
            activeJob = null
            ctx.bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(module = "broadcast", status = ModuleStatus.STOPPED),
            )
        }
    }
}
