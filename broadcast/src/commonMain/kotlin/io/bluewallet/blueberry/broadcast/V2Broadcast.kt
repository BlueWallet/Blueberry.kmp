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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private const val MSG_TX = 1u
private const val MSG_WTX = 5u
private val MSG_WITNESS_FLAG = 1u shl 30

data class BroadcastTxV2Options(
    val port: Int,
    val name: String = APP_NAME,
    val version: String = APP_VERSION,
    val handshakeTimeoutMs: Long = 15_000,
    val ackTimeoutMs: Long = 15_000,
)

internal class HandedOff(
    val mark: () -> Unit,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<HandedOff>
}

fun decodeBroadcastTx(txHex: String) =
    try {
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
): Boolean =
    inventory.any { item ->
        if (item.type == MSG_WTX) return@any equalBytes(item.hash, wtxidInternal)
        val base = item.type and MSG_WITNESS_FLAG.inv()
        base == MSG_TX && equalBytes(item.hash, txidInternal)
    }

/** BIP61 `duplicate`: the peer already holds the transaction. */
private const val REJECT_DUPLICATE = 0x12
private const val COMPACT_SIZE_MAX_INLINE = 0xfd
private const val HASH_BYTES = 32

internal enum class RejectVerdict { Refused, AlreadyHave, Unrelated }

private class WireReader(
    private val bytes: ByteArray,
) {
    private var at = 0

    fun byte(): Int? =
        bytes
            .getOrNull(at)
            ?.toInt()
            ?.and(0xff)
            ?.also { at++ }

    fun take(n: Int): ByteArray? = if (n in 0..bytes.size - at) bytes.copyOfRange(at, at + n).also { at += n } else null

    fun varStr(): String? = byte()?.takeIf { it < COMPACT_SIZE_MAX_INLINE }?.let { take(it) }?.decodeToString()
}

/** Reads a `reject` payload: message, code, reason, then the txid it refers to. */
internal fun rejectVerdict(
    payload: ByteArray,
    txidInternal: ByteArray,
): RejectVerdict {
    val reader = WireReader(payload)
    val message = reader.varStr()
    val code = reader.byte()
    reader.varStr()
    val hash = reader.take(HASH_BYTES)
    val aboutTx = message == "tx" && code != null && (hash == null || equalBytes(hash, txidInternal))
    return when {
        !aboutTx -> RejectVerdict.Unrelated
        code == REJECT_DUPLICATE -> RejectVerdict.AlreadyHave
        else -> RejectVerdict.Refused
    }
}

private fun rejectPayload(msg: Message): ByteArray? = (msg as? Message.Opaque)?.takeIf { (it.type as? WireMessageType.Long)?.command == "reject" }?.payload

private fun describeV2Command(msg: Message): String {
    if (msg !is Message.Opaque) return msg.command
    return when (val type = msg.type) {
        is WireMessageType.Long -> "opaque:${type.command}"
        is WireMessageType.Short -> "opaque"
    }
}

/**
 * BIP-324 session: version/verack, then send `tx`. Returns true when the peer showed it holds the
 * transaction (inv/getdata for it, or a duplicate reject). Returns false on ack timeout or a peer close:
 * nodes no longer send `reject`, so silence cannot tell a kept transaction from a dropped one.
 */
suspend fun broadcastTxV2(
    duplex: ByteDuplex,
    txHex: String,
    options: BroadcastTxV2Options,
): Boolean {
    val wireTx = decodeBroadcastTx(txHex)
    val txidInternal = transactionId(wireTx)
    val wtxidInternal = sha256d(encodeTransaction(wireTx))

    var protocol: Protocol? = null
    var ellswiftDone = false
    try {
        log("broadcast", "v2 ellswift start")
        protocol =
            withTimeout(options.handshakeTimeoutMs) {
                val connected =
                    Protocol.connect(
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
        currentCoroutineContext()[HandedOff]?.mark?.invoke()
        return awaitTxAck(session, txidInternal, wtxidInternal, options.ackTimeoutMs)
    } finally {
        try {
            session.close()
        } catch (_: Throwable) {
        }
    }
}

private const val PEER_REJECTED_TX = "peer rejected transaction"

/** Reads until the peer shows it holds the tx. False when it goes quiet or hangs up first; throws on a reject. */
private suspend fun awaitTxAck(
    session: Protocol,
    txid: ByteArray,
    wtxid: ByteArray,
    timeoutMs: Long,
): Boolean =
    try {
        withTimeout(timeoutMs) {
            var acked = false
            while (!acked) {
                yield()
                val msg = session.readMessage()
                log("broadcast", "v2 recv command=${describeV2Command(msg)}")
                acked = acknowledgesTx(msg, txid, wtxid)
                if (!acked) answerPing(session, msg)
            }
            true
        }
    } catch (err: TimeoutCancellationException) {
        log("broadcast", "v2 ack timeout (handed, not acknowledged)")
        false
    } catch (err: Throwable) {
        val rejected = err.message == PEER_REJECTED_TX
        if (rejected || !isSessionGone(err)) {
            if (!rejected) logError("broadcast", "v2 session error", err)
            throw err
        }
        log("broadcast", "v2 peer-closed after tx (handed, not acknowledged)")
        false
    }

private fun acknowledgesTx(
    msg: Message,
    txid: ByteArray,
    wtxid: ByteArray,
): Boolean {
    val inventory =
        when (msg) {
            is Message.Inv -> msg.payload.inventory
            is Message.GetData -> msg.payload.inventory
            else -> null
        }
    return when (rejectPayload(msg)?.let { rejectVerdict(it, txid) }) {
        RejectVerdict.Refused -> {
            logError("broadcast", "v2 reject")
            throw IllegalStateException(PEER_REJECTED_TX)
        }
        RejectVerdict.AlreadyHave -> {
            log("broadcast", "v2 reject duplicate (accepted)")
            true
        }
        else -> {
            val acked = inventory != null && inventoryMentionsTx(inventory, txid, wtxid)
            if (acked) log("broadcast", "v2 ack ${msg.command}")
            acked
        }
    }
}
