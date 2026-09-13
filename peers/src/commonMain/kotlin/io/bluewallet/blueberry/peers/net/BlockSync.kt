package io.bluewallet.blueberry.peers.net

import io.bluewallet.bip324.BlockPayload
import io.bluewallet.bip324.ByteDuplex
import io.bluewallet.bip324.InventoryPayload
import io.bluewallet.bip324.InventoryVector
import io.bluewallet.bip324.Message
import io.bluewallet.bip324.Networks
import io.bluewallet.bip324.Protocol
import io.bluewallet.bip324.ProtocolOptions
import io.bluewallet.bip324.Role
import io.bluewallet.bip324.VersionHandshakeOptions
import io.bluewallet.bip324.answerPing
import io.bluewallet.bip324.completeVersionHandshake
import io.bluewallet.bip324.equalBytes
import io.bluewallet.blueberry.peers.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/** Inventory type for a full block without witness data (Bitcoin Core MSG_BLOCK). */
const val MSG_BLOCK: UInt = 2u

/** BIP144: full block including witness data (MSG_BLOCK | MSG_WITNESS_FLAG). */
const val MSG_WITNESS_BLOCK: UInt = 1_073_741_826u

sealed class BlockBatchResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : BlockBatchResult<T>()

    data class Err(
        val error: String,
    ) : BlockBatchResult<Nothing>()
}

class BlockSyncOptions(
    val connectTimeoutMs: Long? = null,
    val syncTimeoutMs: Long? = null,
    val connect: TcpConnect,
    val runSession: (suspend (ByteDuplex, Int) -> BlockSessionApi)? = null,
)

interface BlockSessionApi {
    val services: ULong

    suspend fun getBlock(hashInternal: ByteArray): BlockPayload

    suspend fun close()
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

private fun wrapSessionClose(
    session: BlockSessionApi,
    duplex: ByteDuplex,
    protocol: Protocol?,
): BlockSessionApi =
    object : BlockSessionApi by session {
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

private fun createBlockSessionApi(
    protocol: Protocol,
    services: ULong,
    syncTimeoutMs: Long,
    duplex: ByteDuplex,
): BlockSessionApi {
    suspend fun closeQuietly() {
        try {
            protocol.close()
        } catch (_: Throwable) {
            try {
                duplex.close()
            } catch (_: Throwable) {
            }
        }
    }

    suspend fun <T> withSyncTimeout(
        label: String,
        work: suspend () -> T,
    ): T =
        try {
            withTimeout(syncTimeoutMs) { work() }
        } catch (e: TimeoutCancellationException) {
            closeQuietly()
            throw Exception("$label timed out after ${syncTimeoutMs}ms")
        }

    return object : BlockSessionApi {
        override val services = services

        override suspend fun getBlock(hashInternal: ByteArray): BlockPayload =
            withSyncTimeout("getdata/block") {
                protocol.writeMessage(
                    Message.GetData(
                        InventoryPayload(
                            listOf(InventoryVector(MSG_WITNESS_BLOCK, hashInternal)),
                        ),
                    ),
                )
                while (true) {
                    val message = protocol.readMessage()
                    when (message) {
                        is Message.Block -> return@withSyncTimeout message.payload
                        is Message.NotFound -> {
                            val containsRequested =
                                message.payload.inventory.any { item ->
                                    (item.type == MSG_WITNESS_BLOCK || item.type == MSG_BLOCK) &&
                                        equalBytes(item.hash, hashInternal)
                                }
                            if (!containsRequested) {
                                throw Exception("notfound did not contain requested block")
                            }
                            throw Exception("notfound block")
                        }
                        else -> answerPing(protocol, message)
                    }
                }
                error("unreachable")
            }

        override suspend fun close() {
            closeQuietly()
        }
    }
}

suspend fun openBlockSession(
    host: String,
    port: Int,
    options: BlockSyncOptions,
): BlockBatchResult<BlockSessionApi> {
    val connectTimeoutMs = options.connectTimeoutMs ?: Config.blockConnectTimeoutMs
    val syncTimeoutMs = options.syncTimeoutMs ?: Config.blockSyncTimeoutMs
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
                BlockBatchResult.Ok(wrapSessionClose(session, connected, null))
            } else {
                val (protocol, services) = handshake(connected, port)
                opened = true
                BlockBatchResult.Ok(createBlockSessionApi(protocol, services, syncTimeoutMs, connected))
            }
        }
    } catch (e: TimeoutCancellationException) {
        BlockBatchResult.Err("block connect/handshake timed out after ${connectTimeoutMs}ms")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        BlockBatchResult.Err(e.message ?: e.toString())
    } finally {
        if (!opened) {
            try {
                withContext(NonCancellable) { duplex?.close() }
            } catch (_: Throwable) {
            }
        }
    }
}
