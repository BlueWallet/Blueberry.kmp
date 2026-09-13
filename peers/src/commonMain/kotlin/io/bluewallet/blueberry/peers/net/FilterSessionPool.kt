package io.bluewallet.blueberry.peers.net

import io.bluewallet.blueberry.peers.Config
import io.bluewallet.blueberry.peers.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

data class FilterPoolPeer(
    val host: String,
    val port: Int,
)

class FilterSessionPoolOptions(
    val connect: TcpConnect,
    val openSession: (suspend (String, Int, FilterSyncOptions) -> FilterBatchResult<FilterSessionApi>)? = null,
    val max: Int? = null,
    val connectTimeoutMs: Long? = null,
    val syncTimeoutMs: Long? = null,
    val coolMs: Long? = null,
    val now: (() -> Long)? = null,
    val onOpenCount: ((Int) -> Unit)? = null,
    val onDiagnostic: ((String) -> Unit)? = null,
)

interface FilterSessionPool {
    suspend fun setPeers(peers: List<FilterPoolPeer>)

    suspend fun <T> withSession(fn: suspend (FilterSessionApi, FilterPoolPeer) -> T): T?

    suspend fun coolDelayMs(): Long

    suspend fun closeAll()
}

private fun peerKey(
    host: String,
    port: Int,
) = "$host:$port"

private class Endpoint(
    var peer: FilterPoolPeer,
    var session: FilterSessionApi? = null,
    var busy: Boolean = false,
    var coolUntil: Long = 0,
    var orphaned: Boolean = false,
)

@OptIn(ExperimentalAtomicApi::class)
fun createFilterSessionPool(options: FilterSessionPoolOptions): FilterSessionPool {
    val connect = options.connect
    val openSession =
        options.openSession ?: { host, port, opts ->
            openFilterSession(host, port, opts)
        }
    val max = maxOf(1, options.max ?: Config.filterConcurrency)
    val connectTimeoutMs = options.connectTimeoutMs ?: Config.peerProbeTimeoutMs
    val syncTimeoutMs = options.syncTimeoutMs ?: Config.filterSyncTimeoutMs
    val coolMs = options.coolMs ?: 30_000L
    val now = options.now ?: { currentTimeMillis() }
    val onOpenCount = options.onOpenCount
    val onDiagnostic = options.onDiagnostic

    val endpoints = linkedMapOf<String, Endpoint>()
    var peerOrder = mutableListOf<String>()
    var cursor = 0
    var lastOpenCount = -1
    val generation = AtomicInt(0)
    val lock = Mutex()

    suspend fun closeQuietly(session: FilterSessionApi?) {
        if (session == null) return
        try {
            session.close()
        } catch (_: Throwable) {
        }
    }

    fun openCount(): Int {
        var n = 0
        for (ep in endpoints.values) {
            if (ep.session != null || ep.busy) n++
        }
        return n
    }

    fun notifyOpenCount() {
        if (onOpenCount == null) return
        val n = openCount()
        if (n == lastOpenCount) return
        lastOpenCount = n
        onOpenCount(n)
    }

    fun setPeersImpl(peers: List<FilterPoolPeer>): List<FilterSessionApi> {
        val seen = linkedSetOf<String>()
        val nextOrder = mutableListOf<String>()
        val closing = mutableListOf<FilterSessionApi>()
        for (peer in peers) {
            val key = peerKey(peer.host, peer.port)
            if (!seen.add(key)) continue
            nextOrder.add(key)
            val existing = endpoints[key]
            if (existing == null) {
                endpoints[key] = Endpoint(peer = peer)
            } else {
                existing.peer = peer
                existing.orphaned = false
            }
        }
        peerOrder = nextOrder
        val stale = endpoints.keys.filter { it !in seen }
        for (key in stale) {
            val ep = endpoints[key] ?: continue
            if (ep.busy) {
                ep.orphaned = true
                continue
            }
            val session = ep.session
            endpoints.remove(key)
            if (session != null) closing.add(session)
        }
        if (cursor >= peerOrder.size) cursor = 0
        notifyOpenCount()
        return closing
    }

    fun pickIdle(): Endpoint? {
        if (peerOrder.isEmpty()) return null
        val t = now()
        for (i in peerOrder.indices) {
            val key = peerOrder[(cursor + i) % peerOrder.size]
            val ep = endpoints[key] ?: continue
            if (ep.busy || ep.orphaned || t < ep.coolUntil) continue
            if (ep.session != null) {
                cursor = (cursor + i + 1) % peerOrder.size
                return ep
            }
        }
        return null
    }

    fun pickToOpen(): Endpoint? {
        if (peerOrder.isEmpty()) return null
        val t = now()
        var open = 0
        for (ep in endpoints.values) {
            if (!ep.orphaned && (ep.session != null || ep.busy)) open++
        }
        if (open >= max) return null
        for (i in peerOrder.indices) {
            val key = peerOrder[(cursor + i) % peerOrder.size]
            val ep = endpoints[key] ?: continue
            if (ep.busy || ep.session != null || ep.orphaned || t < ep.coolUntil) continue
            cursor = (cursor + i + 1) % peerOrder.size
            return ep
        }
        return null
    }

    suspend fun ensureSession(ep: Endpoint): FilterSessionApi? {
        ep.session?.let { return it }
        val startedAt = now()
        val started = generation.load()
        val result =
            try {
                openSession(
                    ep.peer.host,
                    ep.peer.port,
                    FilterSyncOptions(
                        connectTimeoutMs = connectTimeoutMs,
                        syncTimeoutMs = syncTimeoutMs,
                        connect = connect,
                    ),
                )
            } catch (err: Throwable) {
                onDiagnostic?.invoke(
                    "session open failure peer=${ep.peer.host}:${ep.peer.port} elapsedMs=${maxOf(
                        0,
                        now() - startedAt,
                    )} cooldownMs=$coolMs error=${err.message ?: err.toString()}",
                )
                throw err
            }
        var stale: FilterSessionApi? = null
        val live =
            lock.withLock {
                when (result) {
                    is FilterBatchResult.Err -> {
                        ep.coolUntil = now() + coolMs
                        onDiagnostic?.invoke(
                            "session open failure peer=${ep.peer.host}:${ep.peer.port} elapsedMs=${maxOf(
                                0,
                                now() - startedAt,
                            )} cooldownMs=$coolMs error=${result.error}",
                        )
                        null
                    }
                    is FilterBatchResult.Ok -> {
                        if (generation.load() != started) {
                            stale = result.value
                            null
                        } else {
                            ep.session = result.value
                            onDiagnostic?.invoke(
                                "session open success peer=${ep.peer.host}:${ep.peer.port} elapsedMs=${maxOf(
                                    0,
                                    now() - startedAt,
                                )} services=${result.value.services}",
                            )
                            result.value
                        }
                    }
                }
            }
        closeQuietly(stale)
        return live
    }

    fun retireLocked(ep: Endpoint): FilterSessionApi? {
        val session = ep.session
        ep.session = null
        ep.busy = false
        ep.coolUntil = now() + coolMs
        return session
    }

    fun finishLeaseLocked(ep: Endpoint): FilterSessionApi? {
        ep.busy = false
        if (!ep.orphaned) return null
        val key = peerKey(ep.peer.host, ep.peer.port)
        val session = ep.session
        ep.session = null
        endpoints.remove(key)
        return session
    }

    return object : FilterSessionPool {
        override suspend fun setPeers(peers: List<FilterPoolPeer>) {
            val closing = lock.withLock { setPeersImpl(peers) }
            if (closing.isEmpty()) return
            CoroutineScope(Dispatchers.Default).launch {
                for (session in closing) closeQuietly(session)
            }
        }

        override suspend fun <T> withSession(fn: suspend (FilterSessionApi, FilterPoolPeer) -> T): T? {
            val ep =
                lock.withLock {
                    val picked = pickIdle() ?: pickToOpen() ?: return@withLock null
                    picked.busy = true
                    notifyOpenCount()
                    picked
                } ?: return null
            return try {
                val session = ensureSession(ep)
                if (session == null) {
                    closeQuietly(lock.withLock { finishLeaseLocked(ep) })
                    return null
                }
                val value = fn(session, ep.peer)
                closeQuietly(lock.withLock { finishLeaseLocked(ep) })
                value
            } catch (err: Throwable) {
                val closing =
                    lock.withLock {
                        val session = retireLocked(ep)
                        if (ep.orphaned) {
                            endpoints.remove(peerKey(ep.peer.host, ep.peer.port))
                        }
                        session
                    }
                closeQuietly(closing)
                throw err
            } finally {
                lock.withLock { notifyOpenCount() }
            }
        }

        override suspend fun coolDelayMs(): Long =
            lock.withLock {
                val t = now()
                var minWait = 0L
                for (key in peerOrder) {
                    val ep = endpoints[key] ?: continue
                    if (ep.busy || ep.orphaned || ep.session != null) continue
                    val wait = ep.coolUntil - t
                    if (wait <= 0) return@withLock 0
                    if (minWait == 0L || wait < minWait) minWait = wait
                }
                minWait
            }

        override suspend fun closeAll() {
            val closing =
                lock.withLock {
                    generation.incrementAndFetch()
                    val sessions = mutableListOf<FilterSessionApi>()
                    for (ep in endpoints.values) {
                        val session = ep.session
                        ep.session = null
                        ep.busy = false
                        ep.coolUntil = 0
                        ep.orphaned = false
                        if (session != null) sessions.add(session)
                    }
                    endpoints.clear()
                    peerOrder = mutableListOf()
                    notifyOpenCount()
                    sessions
                }
            for (session in closing) closeQuietly(session)
        }
    }
}
