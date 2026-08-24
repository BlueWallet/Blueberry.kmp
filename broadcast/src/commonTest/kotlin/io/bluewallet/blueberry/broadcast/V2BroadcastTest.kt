package io.bluewallet.blueberry.broadcast

import io.bluewallet.bip324.ByteDuplex
import io.bluewallet.bip324.InventoryPayload
import io.bluewallet.bip324.InventoryVector
import io.bluewallet.bip324.Message
import io.bluewallet.bip324.Networks
import io.bluewallet.bip324.OutPoint
import io.bluewallet.bip324.Protocol
import io.bluewallet.bip324.ProtocolOptions
import io.bluewallet.bip324.Role
import io.bluewallet.bip324.Transaction
import io.bluewallet.bip324.TxInput
import io.bluewallet.bip324.TxOutput
import io.bluewallet.bip324.WireMessageType
import io.bluewallet.bip324.bytesToHex
import io.bluewallet.bip324.encodeTransaction
import io.bluewallet.bip324.equalBytes
import io.bluewallet.bip324.pairedByteDuplexes
import io.bluewallet.bip324.transactionId
import io.bluewallet.blueberry.peers.net.APP_NAME
import io.bluewallet.blueberry.peers.net.APP_VERSION
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val MSG_TX = 1u

private fun sampleTx(): Transaction = Transaction(
    version = 1u,
    inputs = listOf(
        TxInput(
            previousOutput = OutPoint(ByteArray(32), 0u),
            scriptSig = ByteArray(0),
            sequence = 0xffffffffu,
        ),
    ),
    outputs = listOf(TxOutput(value = 1000, scriptPubKey = byteArrayOf(0x51))),
    lockTime = 0u,
)

private fun txHex(): String = bytesToHex(encodeTransaction(sampleTx()))

private suspend fun handshakeThenTx(server: ByteDuplex): Pair<Protocol, Transaction> {
    val protocol = Protocol.connect(
        server,
        ProtocolOptions(role = Role.Responder, network = Networks.mainnet),
    )
    var gotVersion = false
    var gotVerack = false
    while (!gotVersion || !gotVerack) {
        when (val msg = protocol.readMessage()) {
            is Message.Version -> {
                gotVersion = true
                protocol.writeMessage(Message.Version(msg.payload))
                protocol.writeMessage(Message.Verack)
            }
            is Message.Verack -> gotVerack = true
            else -> {}
        }
    }
    while (true) {
        val msg = protocol.readMessage()
        if (msg is Message.Tx) return protocol to msg.payload
    }
}

class V2BroadcastTest {
    @Test
    fun succeeds_on_inv_for_txid() = runBlocking {
        val (client, server) = pairedByteDuplexes()
        val peer = async {
            val (protocol, tx) = handshakeThenTx(server)
            protocol.writeMessage(
                Message.Inv(
                    InventoryPayload(listOf(InventoryVector(MSG_TX, transactionId(tx)))),
                ),
            )
            tx
        }
        broadcastTxV2(client, txHex(), BroadcastTxV2Options(port = 8333, name = APP_NAME, version = APP_VERSION))
        val got = peer.await()
        assertTrue(equalBytes(transactionId(got), transactionId(sampleTx())))
        client.close()
        server.close()
    }

    @Test
    fun succeeds_on_ack_timeout_when_peer_stays_silent() = runBlocking {
        val (client, server) = pairedByteDuplexes()
        val peer = async { handshakeThenTx(server).second }
        broadcastTxV2(
            client,
            txHex(),
            BroadcastTxV2Options(port = 8333, ackTimeoutMs = 80, name = APP_NAME, version = APP_VERSION),
        )
        assertTrue(equalBytes(transactionId(peer.await()), transactionId(sampleTx())))
        client.close()
        server.close()
    }

    @Test
    fun succeeds_when_peer_closes_after_tx() = runBlocking {
        val (client, server) = pairedByteDuplexes()
        val peer = async {
            val (protocol, tx) = handshakeThenTx(server)
            protocol.close()
            tx
        }
        broadcastTxV2(client, txHex(), BroadcastTxV2Options(port = 8333, name = APP_NAME, version = APP_VERSION))
        assertTrue(equalBytes(transactionId(peer.await()), transactionId(sampleTx())))
        client.close()
        server.close()
    }

    @Test
    fun fails_when_peer_sends_reject() = runBlocking {
        val (client, server) = pairedByteDuplexes()
        val peer = async {
            val (protocol, tx) = handshakeThenTx(server)
            protocol.writeMessage(Message.Opaque(WireMessageType.Long("reject"), byteArrayOf(0)))
            tx
        }
        val error = assertFailsWith<IllegalStateException> {
            broadcastTxV2(client, txHex(), BroadcastTxV2Options(port = 8333, name = APP_NAME, version = APP_VERSION))
        }
        assertTrue(error.message.orEmpty().contains("reject", ignoreCase = true))
        peer.await()
        client.close()
        server.close()
    }

    @Test
    fun rejects_invalid_hex_before_handshake() = runBlocking {
        val (client, server) = pairedByteDuplexes()
        val error = assertFailsWith<IllegalArgumentException> {
            broadcastTxV2(client, "not-hex", BroadcastTxV2Options(port = 8333))
        }
        assertEquals("invalid transaction hex", error.message)
        client.close()
        server.close()
    }

    @Test
    fun handshake_timeout_when_peer_never_speaks() = runBlocking {
        val (client, server) = pairedByteDuplexes()
        val error = assertFailsWith<IllegalStateException> {
            broadcastTxV2(
                client,
                txHex(),
                BroadcastTxV2Options(port = 8333, handshakeTimeoutMs = 40, ackTimeoutMs = 40),
            )
        }
        assertTrue(error.message.orEmpty().contains("handshake timeout"))
        client.close()
        server.close()
    }
}
