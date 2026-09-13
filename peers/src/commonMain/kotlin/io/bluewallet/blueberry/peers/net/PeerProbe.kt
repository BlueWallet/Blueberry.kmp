package io.bluewallet.blueberry.peers.net

import io.bluewallet.bip324.ByteDuplex
import io.bluewallet.bip324.Message
import io.bluewallet.bip324.Networks
import io.bluewallet.bip324.Protocol
import io.bluewallet.bip324.ProtocolOptions
import io.bluewallet.bip324.Role
import io.bluewallet.bip324.VersionHandshakeOptions
import io.bluewallet.bip324.answerPing
import io.bluewallet.bip324.completeVersionHandshake
import io.bluewallet.blueberry.peers.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

private const val MAX_CRAWL_ADDRS = 1_000

sealed class ProbeResult {
    data class Ok(
        val peers: List<PeerCandidate>,
        val services: ULong,
    ) : ProbeResult()

    data class Err(
        val error: String,
    ) : ProbeResult()
}

data class HandshakeResult(
    val peers: List<PeerCandidate>,
    val services: ULong,
)

class ProbeOptions(
    val timeoutMs: Long? = null,
    val addrTimeoutMs: Long? = null,
    val wantAddr: Boolean = false,
    val connect: TcpConnect,
    val handshakeAndGetAddr: (suspend (ByteDuplex, Int) -> HandshakeResult)? = null,
)

private class OpenHandshake(
    val services: ULong,
    val protocol: Protocol,
)

private class ParsedAddrs(
    val peers: List<PeerCandidate>,
    val rawCount: Int,
)

/** Version/verack only. Address collection is a later, optional phase. */
private suspend fun defaultHandshake(
    duplex: ByteDuplex,
    port: Int,
): OpenHandshake {
    val protocol =
        Protocol.connect(
            duplex,
            ProtocolOptions(role = Role.Initiator, network = Networks.mainnet),
        )
    val result =
        completeVersionHandshake(
            protocol,
            VersionHandshakeOptions(port = port, name = APP_NAME, version = APP_VERSION),
        )
    return OpenHandshake(result.services, protocol)
}

private fun peersFromAddrMessage(
    message: Message,
    limit: Int,
): ParsedAddrs? =
    when (message) {
        is Message.AddrV2 -> {
            val rows = message.payload.addresses
            ParsedAddrs(
                rawCount = rows.size,
                peers = rows.take(limit).mapNotNull(::addrV2ToCandidate),
            )
        }
        is Message.Addr -> {
            val rows = message.payload.addresses
            ParsedAddrs(
                rawCount = rows.size,
                peers = rows.take(limit).mapNotNull(::legacyAddrToCandidate),
            )
        }
        else -> null
    }

/** getaddr + addr/addrv2. Timeouts and errors return whatever was collected. */
private suspend fun collectAddrAfterHandshake(
    protocol: Protocol,
    addrTimeoutMs: Long,
): List<PeerCandidate> {
    val collected = mutableListOf<PeerCandidate>()
    var seen = 0
    try {
        withTimeout(addrTimeoutMs) {
            protocol.writeMessage(Message.GetAddr)
            while (true) {
                val message = protocol.readMessage()
                val parsed = peersFromAddrMessage(message, MAX_CRAWL_ADDRS - seen)
                if (parsed != null) {
                    seen += minOf(parsed.rawCount, MAX_CRAWL_ADDRS - seen)
                    collected.addAll(parsed.peers)
                    if (parsed.rawCount >= 2 || seen >= MAX_CRAWL_ADDRS) break
                } else {
                    answerPing(protocol, message)
                }
            }
        }
    } catch (_: TimeoutCancellationException) {
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
    }
    return collected
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun connectOrAbort(
    connect: TcpConnect,
    host: String,
    port: Int,
): ByteDuplex {
    val pending = CompletableDeferred<ByteDuplex>()
    val connectJob =
        CoroutineScope(coroutineContext).launch {
            try {
                pending.complete(connect(host, port))
            } catch (e: CancellationException) {
                pending.cancel(e)
                throw e
            } catch (e: Throwable) {
                pending.completeExceptionally(e)
            }
        }
    try {
        return pending.await()
    } catch (e: CancellationException) {
        connectJob.invokeOnCompletion {
            val d = runCatching { pending.getCompleted() }.getOrNull() ?: return@invokeOnCompletion
            CoroutineScope(Dispatchers.Default).launch { runCatching { d.close() } }
        }
        throw e
    }
}

suspend fun probePeer(
    host: String,
    port: Int,
    options: ProbeOptions,
): ProbeResult {
    val timeoutMs = options.timeoutMs ?: Config.peerProbeTimeoutMs
    val addrTimeoutMs = options.addrTimeoutMs ?: Config.peerAddrTimeoutMs
    val wantAddr = options.wantAddr
    var duplex: ByteDuplex? = null
    return try {
        data class HandshakePhase(
            val peers: List<PeerCandidate>,
            val services: ULong,
            val protocol: Protocol?,
        )
        val phase =
            withTimeout(timeoutMs) {
                val connected = connectOrAbort(options.connect, host, port)
                duplex = connected
                val injected = options.handshakeAndGetAddr
                if (injected != null) {
                    val hs = injected(connected, port)
                    HandshakePhase(hs.peers, hs.services, null)
                } else {
                    val open = defaultHandshake(connected, port)
                    HandshakePhase(emptyList(), open.services, open.protocol)
                }
            }
        val peers =
            if (phase.protocol != null && wantAddr) {
                collectAddrAfterHandshake(phase.protocol, addrTimeoutMs)
            } else {
                phase.peers
            }
        ProbeResult.Ok(peers, phase.services)
    } catch (e: TimeoutCancellationException) {
        ProbeResult.Err("probe timed out after ${timeoutMs}ms")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        ProbeResult.Err(e.message ?: e.toString())
    } finally {
        try {
            withContext(NonCancellable) { duplex?.close() }
        } catch (_: Throwable) {
        }
    }
}
