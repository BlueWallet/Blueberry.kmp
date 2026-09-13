package io.bluewallet.blueberry.peers.net

import io.bluewallet.bip157.BIP157_SHORT_IDS
import io.bluewallet.bip157.FILTER_TYPE_BASIC
import io.bluewallet.bip157.GetCFCheckpt
import io.bluewallet.bip157.GetCFHeaders
import io.bluewallet.bip157.GetCFilters
import io.bluewallet.bip157.OutboundMessage
import io.bluewallet.bip157.decodeCFCheckpt
import io.bluewallet.bip157.decodeCFHeaders
import io.bluewallet.bip157.decodeCFilter
import io.bluewallet.bip157.encodeOutbound
import io.bluewallet.bip324.ByteDuplex
import io.bluewallet.bip324.Message
import io.bluewallet.bip324.Networks
import io.bluewallet.bip324.Protocol
import io.bluewallet.bip324.ProtocolOptions
import io.bluewallet.bip324.Role
import io.bluewallet.bip324.VersionHandshakeOptions
import io.bluewallet.bip324.WireMessageType
import io.bluewallet.bip324.answerPing
import io.bluewallet.bip324.completeVersionHandshake
import io.bluewallet.blueberry.peers.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile
import kotlin.coroutines.coroutineContext

sealed class FilterBatchResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : FilterBatchResult<T>()

    data class Err(
        val error: String,
    ) : FilterBatchResult<Nothing>()
}

data class CFHeadersResult(
    val filterType: Int,
    val stopHash: ByteArray,
    val previousFilterHeader: ByteArray,
    val filterHashes: List<ByteArray>,
)

data class CFilterItem(
    val blockHash: ByteArray,
    val filterBytes: ByteArray,
)

interface FilterSessionApi {
    val services: ULong

    suspend fun getCFCheckpt(stopHash: ByteArray): List<ByteArray>

    suspend fun getCFHeaders(
        startHeight: Int,
        stopHash: ByteArray,
    ): CFHeadersResult

    suspend fun getCFilters(
        startHeight: Int,
        stopHash: ByteArray,
        expectCount: Int,
        onFilter: (suspend (CFilterItem) -> Unit)? = null,
    ): List<CFilterItem>

    suspend fun close()
}

class FilterSyncOptions(
    val connectTimeoutMs: Long? = null,
    val syncTimeoutMs: Long? = null,
    val connect: TcpConnect,
    val runSession: (suspend (ByteDuplex, Int) -> FilterSessionApi)? = null,
)

class InactivityTimeout internal constructor(
    private val ms: Long,
    private val label: String,
    private val scope: CoroutineScope,
) {
    @Volatile var expired: Boolean = false
        private set

    @Volatile var error: Throwable? = null
        private set

    internal val expiredDeferred = CompletableDeferred<Throwable>()
    private var timer: Job? = null

    fun refresh() {
        if (expiredDeferred.isCompleted) return
        timer?.cancel()
        timer =
            scope.launch {
                delay(ms)
                val err = Exception("$label inactive for ${ms}ms")
                if (expiredDeferred.complete(err)) {
                    error = err
                    expired = true
                }
            }
    }

    fun clear() {
        timer?.cancel()
        timer = null
    }
}

fun createInactivityTimeout(
    ms: Long,
    label: String,
): InactivityTimeout {
    val timeout = InactivityTimeout(ms, label, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    timeout.refresh()
    return timeout
}

suspend fun <T> runWithInactivityTimeout(
    ms: Long,
    label: String,
    work: suspend (activity: () -> Unit) -> T,
): T {
    val timeout = createInactivityTimeout(ms, label)
    try {
        return coroutineScope {
            val workJob = async { work { timeout.refresh() } }
            val abortJob =
                async {
                    throw timeout.expiredDeferred.await()
                }
            try {
                select {
                    workJob.onAwait { it }
                    abortJob.onAwait { error("unreachable") }
                }
            } finally {
                workJob.cancel()
                abortJob.cancel()
            }
        }
    } finally {
        timeout.clear()
    }
}

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

private suspend fun handshake(
    duplex: ByteDuplex,
    port: Int,
): Pair<Protocol, ULong> {
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
    return protocol to result.services
}

private suspend fun sendBip157(
    protocol: Protocol,
    msg: OutboundMessage,
) {
    val encoded = encodeOutbound(msg)
    protocol.writeMessage(Message.Opaque(WireMessageType.Short(encoded.shortId), encoded.payload))
}

private suspend fun waitForBip157Payload(
    protocol: Protocol,
    shortId: Int,
): ByteArray {
    while (true) {
        val message = protocol.readMessage()
        val type = (message as? Message.Opaque)?.type
        if (type is WireMessageType.Short && type.id == shortId) {
            return message.payload
        }
        answerPing(protocol, message)
    }
}

private fun wrapSessionClose(
    session: FilterSessionApi,
    duplex: ByteDuplex,
    protocol: Protocol?,
): FilterSessionApi =
    object : FilterSessionApi by session {
        override suspend fun close() {
            session.close()
            if (protocol != null) {
                try {
                    protocol.close()
                } catch (_: Throwable) {
                    duplex.close()
                }
            } else {
                try {
                    duplex.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

private fun createFilterSessionApi(
    protocol: Protocol,
    services: ULong,
    syncTimeoutMs: Long,
    duplex: ByteDuplex,
): FilterSessionApi {
    suspend fun <T> withSyncTimeout(
        label: String,
        work: suspend () -> T,
    ): T =
        try {
            withTimeout(syncTimeoutMs) { work() }
        } catch (e: TimeoutCancellationException) {
            throw Exception("$label timed out after ${syncTimeoutMs}ms")
        }

    return object : FilterSessionApi {
        override val services = services

        override suspend fun getCFCheckpt(stopHash: ByteArray): List<ByteArray> =
            withSyncTimeout("cfcheckpt") {
                sendBip157(
                    protocol,
                    OutboundMessage.GetCFCheckpt(GetCFCheckpt(FILTER_TYPE_BASIC, stopHash)),
                )
                decodeCFCheckpt(waitForBip157Payload(protocol, BIP157_SHORT_IDS.cfcheckpt)).filterHeaders
            }

        override suspend fun getCFHeaders(
            startHeight: Int,
            stopHash: ByteArray,
        ): CFHeadersResult =
            withSyncTimeout("cfheaders") {
                sendBip157(
                    protocol,
                    OutboundMessage.GetCFHeaders(
                        GetCFHeaders(FILTER_TYPE_BASIC, startHeight, stopHash),
                    ),
                )
                val decoded = decodeCFHeaders(waitForBip157Payload(protocol, BIP157_SHORT_IDS.cfheaders))
                CFHeadersResult(
                    filterType = decoded.filterType,
                    stopHash = decoded.stopHash,
                    previousFilterHeader = decoded.previousFilterHeader,
                    filterHashes = decoded.filterHashes,
                )
            }

        override suspend fun getCFilters(
            startHeight: Int,
            stopHash: ByteArray,
            expectCount: Int,
            onFilter: (suspend (CFilterItem) -> Unit)?,
        ): List<CFilterItem> =
            runWithInactivityTimeout(syncTimeoutMs, "cfilters") { activity ->
                sendBip157(
                    protocol,
                    OutboundMessage.GetCFilters(
                        GetCFilters(FILTER_TYPE_BASIC, startHeight, stopHash),
                    ),
                )
                val filters = ArrayList<CFilterItem>(expectCount)
                while (filters.size < expectCount) {
                    val decoded = decodeCFilter(waitForBip157Payload(protocol, BIP157_SHORT_IDS.cfilter))
                    activity()
                    val item = CFilterItem(decoded.blockHash, decoded.filterBytes)
                    filters.add(item)
                    if (onFilter != null) onFilter(item)
                }
                filters
            }

        override suspend fun close() {
            try {
                protocol.close()
            } catch (_: Throwable) {
                duplex.close()
            }
        }
    }
}

suspend fun openFilterSession(
    host: String,
    port: Int,
    options: FilterSyncOptions,
): FilterBatchResult<FilterSessionApi> {
    val connectTimeoutMs = options.connectTimeoutMs ?: Config.peerProbeTimeoutMs
    val syncTimeoutMs = options.syncTimeoutMs ?: Config.filterSyncTimeoutMs
    var duplex: ByteDuplex? = null
    var opened = false
    return try {
        withTimeout(connectTimeoutMs) {
            val connected = connectOrAbort(options.connect, host, port)
            duplex = connected
            val runSession = options.runSession
            if (runSession != null) {
                val session = runSession(connected, port)
                opened = true
                FilterBatchResult.Ok(wrapSessionClose(session, connected, null))
            } else {
                val (protocol, services) = handshake(connected, port)
                opened = true
                FilterBatchResult.Ok(createFilterSessionApi(protocol, services, syncTimeoutMs, connected))
            }
        }
    } catch (e: TimeoutCancellationException) {
        FilterBatchResult.Err("filter connect/handshake timed out after ${connectTimeoutMs}ms")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        FilterBatchResult.Err(e.message ?: e.toString())
    } finally {
        if (!opened) {
            try {
                withContext(NonCancellable) { duplex?.close() }
            } catch (_: Throwable) {
            }
        }
    }
}
