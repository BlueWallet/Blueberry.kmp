package io.bluewallet.blueberry.broadcast

import io.bluewallet.bip324.ByteDuplex
import io.bluewallet.bip324.Message
import io.bluewallet.bip324.Networks
import io.bluewallet.bip324.OutPoint
import io.bluewallet.bip324.Protocol
import io.bluewallet.bip324.ProtocolOptions
import io.bluewallet.bip324.Role
import io.bluewallet.bip324.Transaction
import io.bluewallet.bip324.TxInput
import io.bluewallet.bip324.TxOutput
import io.bluewallet.bip324.bytesToHex
import io.bluewallet.bip324.encodeTransaction
import io.bluewallet.bip324.equalBytes
import io.bluewallet.bip324.pairedByteDuplexes
import io.bluewallet.bip324.transactionId
import io.bluewallet.blueberry.bus.BroadcastCancelPayload
import io.bluewallet.blueberry.bus.BroadcastDonePayload
import io.bluewallet.blueberry.bus.BroadcastRequestPayload
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.createMessageBus
import io.bluewallet.blueberry.peers.modules.ModuleContext
import io.bluewallet.blueberry.storage.PeerWrite
import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val NODE_NETWORK = 1uL

private fun sampleTx(): Transaction =
    Transaction(
        version = 1u,
        inputs =
            listOf(
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

private fun upsertAlive(
    db: io.bluewallet.blueberry.storage.Database,
    host: String,
) {
    db.peers.upsert(
        PeerWrite(
            host = host,
            port = 8333,
            services = 9uL,
            alive = true,
            usedForBlocks = false,
            lastProbedAt = null,
        ),
    )
}

private suspend fun acceptTx(server: ByteDuplex): Transaction {
    val protocol =
        Protocol.connect(
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
        if (msg is Message.Tx) return msg.payload
    }
}

private fun connectAccepting(received: MutableList<Transaction>): suspend (String, Int, Job) -> ByteDuplex =
    { _, _, _ ->
        val (client, server) = pairedByteDuplexes()
        CoroutineScope(Job() + Dispatchers.Default).launch {
            try {
                received += acceptTx(server)
            } finally {
                try {
                    server.close()
                } catch (_: Throwable) {
                }
            }
        }
        client
    }

class BroadcastModuleTest {
    @Test
    fun waits_for_alive_peer_delivers_tx_and_reports_ok() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            val bus = createMessageBus()
            val received = mutableListOf<Transaction>()
            val done =
                async {
                    val deferred = kotlinx.coroutines.CompletableDeferred<BroadcastDonePayload>()
                    bus.on(Event.BroadcastDone) { deferred.complete(it) }
                    deferred.await()
                }
            val mod =
                createBroadcastModule(
                    ModuleContext(bus, db),
                    BroadcastModuleOptions(
                        peerWaitPollMs = 15,
                        ackTimeoutMs = 150,
                        connect = connectAccepting(received),
                    ),
                )
            mod.start()
            bus.emit(Event.BroadcastRequest, BroadcastRequestPayload("1", txHex()))
            delay(30)
            upsertAlive(db, "9.9.9.9")
            val result = done.await() as BroadcastDonePayload.Ok
            assertEquals("9.9.9.9:8333", result.peer)
            assertEquals(1, received.size)
            assertTrue(equalBytes(transactionId(received[0]), transactionId(sampleTx())))
            mod.stop()
            db.close()
        }

    @Test
    fun retries_other_alive_peers_after_dial_failure() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            upsertAlive(db, "1.1.1.1")
            upsertAlive(db, "2.2.2.2")
            val bus = createMessageBus()
            val received = mutableListOf<Transaction>()
            val dialed = mutableListOf<String>()
            var pick = 0
            val done =
                async {
                    val deferred = kotlinx.coroutines.CompletableDeferred<BroadcastDonePayload>()
                    bus.on(Event.BroadcastDone) { deferred.complete(it) }
                    deferred.await()
                }
            val accept = connectAccepting(received)
            val mod =
                createBroadcastModule(
                    ModuleContext(bus, db),
                    BroadcastModuleOptions(
                        ackTimeoutMs = 150,
                        random = { if (pick++ == 0) 0.0 else 0.99 },
                        connect = { host, port, job ->
                            dialed += host
                            if (host == "1.1.1.1") throw IllegalStateException("dial failed")
                            accept(host, port, job)
                        },
                    ),
                )
            mod.start()
            bus.emit(Event.BroadcastRequest, BroadcastRequestPayload("2", txHex()))
            val result = done.await()
            assertTrue(result is BroadcastDonePayload.Ok)
            assertEquals(listOf("1.1.1.1", "2.2.2.2"), dialed)
            assertEquals(1, received.size)
            mod.stop()
            db.close()
        }

    @Test
    fun dial_timeout_retries_the_next_peer() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            upsertAlive(db, "1.1.1.1")
            upsertAlive(db, "2.2.2.2")
            val bus = createMessageBus()
            val received = mutableListOf<Transaction>()
            val dialed = mutableListOf<String>()
            var pick = 0
            val done =
                async {
                    val deferred = kotlinx.coroutines.CompletableDeferred<BroadcastDonePayload>()
                    bus.on(Event.BroadcastDone) { deferred.complete(it) }
                    deferred.await()
                }
            val accept = connectAccepting(received)
            val mod =
                createBroadcastModule(
                    ModuleContext(bus, db),
                    BroadcastModuleOptions(
                        ackTimeoutMs = 150,
                        dialTimeoutMs = 80,
                        random = { if (pick++ == 0) 0.0 else 0.99 },
                        connect = { host, port, job ->
                            dialed += host
                            if (host == "1.1.1.1") {
                                delay(10_000)
                                error("hang should have timed out")
                            }
                            accept(host, port, job)
                        },
                    ),
                )
            mod.start()
            bus.emit(Event.BroadcastRequest, BroadcastRequestPayload("timeout", txHex()))
            val result = done.await()
            assertTrue(result is BroadcastDonePayload.Ok, result.toString())
            assertEquals(listOf("1.1.1.1", "2.2.2.2"), dialed)
            assertEquals(1, received.size)
            mod.stop()
            db.close()
        }

    @Test
    fun reports_failure_after_exhausting_attempts() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            upsertAlive(db, "1.1.1.1")
            val bus = createMessageBus()
            val done =
                async {
                    val deferred = kotlinx.coroutines.CompletableDeferred<BroadcastDonePayload>()
                    bus.on(Event.BroadcastDone) { deferred.complete(it) }
                    deferred.await()
                }
            val mod =
                createBroadcastModule(
                    ModuleContext(bus, db),
                    BroadcastModuleOptions(
                        maxAttempts = 2,
                        connect = { _, _, _ -> throw IllegalStateException("dial failed") },
                    ),
                )
            mod.start()
            bus.emit(Event.BroadcastRequest, BroadcastRequestPayload("3", txHex()))
            val result = done.await() as BroadcastDonePayload.Error
            assertTrue(result.error.contains("failed after 2 attempts"))
            assertTrue(result.error.contains("1.1.1.1:8333"))
            assertTrue(result.error.contains("dial failed"))
            mod.stop()
            db.close()
        }

    @Test
    fun cancel_while_waiting_for_alive_peers() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            val bus = createMessageBus()
            var dialed = 0
            val done =
                async {
                    val deferred = kotlinx.coroutines.CompletableDeferred<BroadcastDonePayload>()
                    bus.on(Event.BroadcastDone) { deferred.complete(it) }
                    deferred.await()
                }
            val mod =
                createBroadcastModule(
                    ModuleContext(bus, db),
                    BroadcastModuleOptions(
                        peerWaitPollMs = 15,
                        connect = { _, _, _ ->
                            dialed += 1
                            error("should not dial")
                        },
                    ),
                )
            mod.start()
            bus.emit(Event.BroadcastRequest, BroadcastRequestPayload("4", txHex()))
            delay(25)
            bus.emit(Event.BroadcastCancel, BroadcastCancelPayload("4"))
            val result = done.await() as BroadcastDonePayload.Error
            assertTrue(result.error.contains("cancel", ignoreCase = true))
            assertEquals(0, dialed)
            mod.stop()
            db.close()
        }

    @Test
    fun second_request_is_rejected_while_in_progress() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            val bus = createMessageBus()
            val done = mutableListOf<BroadcastDonePayload>()
            bus.on(Event.BroadcastDone) { done += it }
            val mod =
                createBroadcastModule(
                    ModuleContext(bus, db),
                    BroadcastModuleOptions(
                        peerWaitPollMs = 20,
                        connect = { _, _, _ -> error("should not dial") },
                    ),
                )
            mod.start()
            bus.emit(Event.BroadcastRequest, BroadcastRequestPayload("a", txHex()))
            bus.emit(Event.BroadcastRequest, BroadcastRequestPayload("b", txHex()))
            delay(30)
            assertEquals(1, done.size)
            assertEquals("b", done[0].id)
            assertTrue((done[0] as BroadcastDonePayload.Error).error.contains("already in progress"))
            bus.emit(Event.BroadcastCancel, BroadcastCancelPayload("a"))
            delay(30)
            mod.stop()
            db.close()
        }
}
