package io.bluewallet.blueberry.broadcast

import io.bluewallet.bip324.ByteDuplex
import io.bluewallet.bip324.InventoryVector
import io.bluewallet.bip324.Message
import io.bluewallet.bip324.Networks
import io.bluewallet.bip324.Protocol
import io.bluewallet.bip324.ProtocolClosedError
import io.bluewallet.bip324.ProtocolOptions
import io.bluewallet.bip324.Role
import io.bluewallet.bip324.VersionHandshakeOptions
import io.bluewallet.bip324.WireMessageType
import io.bluewallet.bip324.answerPing
import io.bluewallet.bip324.completeVersionHandshake
import io.bluewallet.bip324.decodeTransaction
import io.bluewallet.bip324.encodeTransaction
import io.bluewallet.bip324.equalBytes
import io.bluewallet.bip324.hexToBytes
import io.bluewallet.bip324.sha256d
import io.bluewallet.bip324.transactionId
import io.bluewallet.blueberry.peers.log
import io.bluewallet.blueberry.peers.logError
import io.bluewallet.blueberry.peers.net.APP_NAME
import io.bluewallet.blueberry.peers.net.APP_VERSION
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

private val MSG_TX = 1u
private val MSG_WTX = 5u
private val MSG_WITNESS_FLAG = 1u shl 30

data class BroadcastTxV2Options(
    val port: Int,
    val name: String = APP_NAME,
    val version: String = APP_VERSION,
    val handshakeTimeoutMs: Long = 15_000,
    val ackTimeoutMs: Long = 15_000,
)

fun decodeBroadcastTx(txHex: String) = try {
    decodeTransaction(hexToBytes(txHex))
} catch (_: Throwable) {
    throw IllegalArgumentException("invalid transaction hex")
}

private fun isSessionGone(err: Throwable): Boolean {
    if (err is ProtocolClosedError) return true
    val message = err.message.orEmpty()
    return message.contains("unexpected EOF") || message.contains("closed duplex")
}

private fun inventoryMentionsTx(
    inventory: List<InventoryVector>,
    txidInternal: ByteArray,
    wtxidInternal: ByteArray,
): Boolean = inventory.any { item ->
    if (item.type == MSG_WTX) return@any equalBytes(item.hash, wtxidInternal)
    val base = item.type and MSG_WITNESS_FLAG.inv()
    base == MSG_TX && equalBytes(item.hash, txidInternal)
}

private fun describeV2Command(msg: Message): String {
    if (msg !is Message.Opaque) return msg.command
    return when (val type = msg.type) {
        is WireMessageType.Long -> "opaque:${type.command}"
        is WireMessageType.Short -> "opaque"
    }
}

/**
 * BIP-324 session: version/verack, send `tx`, succeed on inv/getdata for the
 * txid, or ack timeout / peer close without `reject`.
 */
suspend fun broadcastTxV2(
    duplex: ByteDuplex,
    txHex: String,
    options: BroadcastTxV2Options,
) {
    val wireTx = decodeBroadcastTx(txHex)
    val txidInternal = transactionId(wireTx)
    val wtxidInternal = sha256d(encodeTransaction(wireTx))

    var protocol: Protocol? = null
    var ellswiftDone = false
    try {
        log("broadcast", "v2 ellswift start")
        protocol = withTimeout(options.handshakeTimeoutMs) {
            val connected = Protocol.connect(
                duplex,
                ProtocolOptions(role = Role.Initiator, network = Networks.mainnet),
            )
            ellswiftDone = true
            log("broadcast", "v2 version-handshake start")
            completeVersionHandshake(
                connected,
                VersionHandshakeOptions(port = options.port, name = options.name, version = options.version),
            )
            connected
        }
    } catch (err: TimeoutCancellationException) {
        logError("broadcast", "v2 handshake fail phase=timeout", err)
        throw IllegalStateException("handshake timeout")
    } catch (err: Throwable) {
        logError(
            "broadcast",
            "v2 handshake fail phase=${if (ellswiftDone) "version-handshake" else "ellswift"}",
            err,
        )
        throw err
    }

    val session = protocol
    try {
        log("broadcast", "v2 send-tx")
        session.writeMessage(Message.Tx(wireTx))
        try {
            withTimeout(options.ackTimeoutMs) {
                while (true) {
                    yield()
                    val msg = session.readMessage()
                    log("broadcast", "v2 recv command=${describeV2Command(msg)}")
                    if (msg is Message.Opaque &&
                        msg.type is WireMessageType.Long &&
                        (msg.type as WireMessageType.Long).command == "reject"
                    ) {
                        logError("broadcast", "v2 reject")
                        throw IllegalStateException("peer rejected transaction")
                    }
                    if ((msg is Message.Inv || msg is Message.GetData)) {
                        val inventory = when (msg) {
                            is Message.Inv -> msg.payload.inventory
                            is Message.GetData -> msg.payload.inventory
                        }
                        if (inventoryMentionsTx(inventory, txidInternal, wtxidInternal)) {
                            log("broadcast", "v2 ack ${msg.command}")
                            return@withTimeout
                        }
                    }
                    answerPing(session, msg)
                }
            }
        } catch (err: TimeoutCancellationException) {
            log("broadcast", "v2 ack timeout (accepted)")
        } catch (err: Throwable) {
            if (err.message == "peer rejected transaction") throw err
            if (isSessionGone(err)) {
                log("broadcast", "v2 peer-closed after tx (accepted)")
            } else {
                logError("broadcast", "v2 session error", err)
                throw err
            }
        }
    } finally {
        try {
            session.close()
        } catch (_: Throwable) {
        }
    }
}
