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
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

private const val NODE_NETWORK = 1uL
private const val NODE_P2P_V2 = 2048uL

private fun upsertAlive(
    db: io.bluewallet.blueberry.storage.Database,
    host: String,
    services: ULong = NODE_NETWORK or NODE_P2P_V2 or 8uL,
) {
    db.peers.upsert(
        PeerWrite(
            host = host,
            port = 8333,
            services = services,
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
            upsertAlive(db, "7.7.7.7")
            upsertAlive(db, "8.8.8.8")
            upsertAlive(db, "9.9.9.9")
            val result = done.await() as BroadcastDonePayload.Ok
            assertEquals(3, received.size)
            assertTrue(result.peer.contains("7.7.7.7:8333"))
            assertTrue(result.peer.contains("8.8.8.8:8333"))
            assertTrue(result.peer.contains("9.9.9.9:8333"))
            assertTrue(equalBytes(transactionId(received[0]), transactionId(sampleTx())))
            mod.stop()
            db.close()
        }

    @Test
    fun stops_after_three_successful_peers() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            listOf("1.1.1.1", "2.2.2.2", "3.3.3.3", "4.4.4.4", "5.5.5.5", "6.6.6.6").forEach {
                upsertAlive(db, it)
            }
            val bus = createMessageBus()
            val received = mutableListOf<Transaction>()
            val dialed = mutableListOf<String>()
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
                        connect = { host, port, job ->
                            dialed += host
                            accept(host, port, job)
                        },
                    ),
                )
            mod.start()
            bus.emit(Event.BroadcastRequest, BroadcastRequestPayload("stop3", txHex()))
            val result = done.await()
            assertTrue(result is BroadcastDonePayload.Ok, result.toString())
            assertEquals(3, received.size)
            assertEquals(3, dialed.size)
            mod.stop()
            db.close()
        }

    @Test
    fun accepts_v2_peers_that_do_not_advertise_node_network() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            upsertAlive(db, "1.1.1.1", NODE_P2P_V2)
            upsertAlive(db, "2.2.2.2", NODE_P2P_V2)
            upsertAlive(db, "3.3.3.3", NODE_P2P_V2)
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
                        ackTimeoutMs = 150,
                        connect = connectAccepting(received),
                    ),
                )
            mod.start()
            bus.emit(Event.BroadcastRequest, BroadcastRequestPayload("pruned", txHex()))
            val result = withTimeout(2_000) { done.await() }
            assertTrue(result is BroadcastDonePayload.Ok, result.toString())
            assertEquals(3, received.size)
            mod.stop()
            db.close()
        }

    @Test
    fun skips_excluded_peers_on_retry() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            listOf("1.1.1.1", "2.2.2.2", "3.3.3.3", "4.4.4.4", "5.5.5.5", "6.6.6.6").forEach {
                upsertAlive(db, it)
            }
            val bus = createMessageBus()
            val received = mutableListOf<Transaction>()
            val dialed = mutableListOf<String>()
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
                        random = { 0.0 },
                        connect = { host, port, job ->
                            dialed += host
                            accept(host, port, job)
                        },
                    ),
                )
            mod.start()
            bus.emit(
                Event.BroadcastRequest,
                BroadcastRequestPayload(
                    "retry",
                    txHex(),
                    excludePeers = setOf("1.1.1.1:8333", "2.2.2.2:8333", "3.3.3.3:8333"),
                ),
            )
            val result = done.await()
            assertTrue(result is BroadcastDonePayload.Ok, result.toString())
            assertEquals(setOf("4.4.4.4", "5.5.5.5", "6.6.6.6"), dialed.toSet())
            assertEquals(3, received.size)
            mod.stop()
            db.close()
        }

    @Test
    fun skips_alive_peers_that_do_not_advertise_p2p_v2() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            upsertAlive(db, "1.1.1.1", NODE_NETWORK or 8uL)
            upsertAlive(db, "2.2.2.2", NODE_NETWORK or 8uL)
            upsertAlive(db, "3.3.3.3", NODE_NETWORK or 8uL)
            upsertAlive(db, "5.5.5.5")
            upsertAlive(db, "6.6.6.6")
            upsertAlive(db, "7.7.7.7")
            val bus = createMessageBus()
            val received = mutableListOf<Transaction>()
            val dialed = mutableListOf<String>()
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
                        random = { 0.0 },
                        connect = { host, port, job ->
                            dialed += host
                            accept(host, port, job)
                        },
                    ),
                )
            mod.start()
            bus.emit(Event.BroadcastRequest, BroadcastRequestPayload("v2", txHex()))
            val result = done.await()
            assertTrue(result is BroadcastDonePayload.Ok, result.toString())
            assertEquals(setOf("5.5.5.5", "6.6.6.6", "7.7.7.7"), dialed.toSet())
            assertEquals(3, received.size)
            mod.stop()
            db.close()
        }

    @Test
    fun retries_other_alive_peers_after_dial_failure() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            upsertAlive(db, "1.1.1.1")
            upsertAlive(db, "2.2.2.2")
            upsertAlive(db, "3.3.3.3")
            upsertAlive(db, "4.4.4.4")
            val bus = createMessageBus()
            val received = mutableListOf<Transaction>()
            val dialed = mutableListOf<String>()
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
                        random = { 0.0 },
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
            assertEquals("1.1.1.1", dialed.first())
            assertEquals(3, received.size)
            assertTrue((result as BroadcastDonePayload.Ok).peer.contains("2.2.2.2:8333"))
            mod.stop()
            db.close()
        }

    @Test
    fun dial_timeout_retries_the_next_peer() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            upsertAlive(db, "1.1.1.1")
            upsertAlive(db, "2.2.2.2")
            upsertAlive(db, "3.3.3.3")
            upsertAlive(db, "4.4.4.4")
            val bus = createMessageBus()
            val received = mutableListOf<Transaction>()
            val dialed = mutableListOf<String>()
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
                        random = { 0.0 },
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
            assertEquals("1.1.1.1", dialed.first())
            assertEquals(3, received.size)
            mod.stop()
            db.close()
        }

    @Test
    fun does_not_rebuild_tor_dialer_when_only_successful_peers_remain() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            upsertAlive(db, "1.1.1.1")
            val bus = createMessageBus()
            val received = mutableListOf<Transaction>()
            var created = 0
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
                        createDialer = {
                            created += 1
                            object : TorByteDuplexDialer {
                                override suspend fun dial(
                                    host: String,
                                    port: Int,
                                    job: Job,
                                    onProgress: io.bluewallet.echalote.FetchProgressListener?,
                                ) = accept(host, port, job)

                                override suspend fun dispose() = Unit
                            }
                        },
                    ),
                )
            mod.start()
            bus.emit(Event.BroadcastRequest, BroadcastRequestPayload("once", txHex()))
            val result = done.await()
            assertTrue(result is BroadcastDonePayload.Ok, result.toString())
            assertEquals(1, received.size)
            assertEquals(1, created)
            mod.stop()
            db.close()
        }

    @Test
    fun reports_ok_when_at_least_one_peer_accepted_even_if_short_of_three() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            upsertAlive(db, "1.1.1.1")
            upsertAlive(db, "2.2.2.2")
            upsertAlive(db, "3.3.3.3")
            upsertAlive(db, "4.4.4.4")
            val bus = createMessageBus()
            val received = mutableListOf<Transaction>()
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
                        maxAttempts = 4,
                        random = { 0.0 },
                        connect = { host, port, job ->
                            if (host == "1.1.1.1") accept(host, port, job) else error("unexpected EOF: wanted 64 bytes, got 0")
                        },
                    ),
                )
            mod.start()
            bus.emit(Event.BroadcastRequest, BroadcastRequestPayload("partial", txHex()))
            val result = done.await()
            assertTrue(result is BroadcastDonePayload.Ok, result.toString())
            assertEquals(1, received.size)
            assertEquals("1.1.1.1:8333", (result as BroadcastDonePayload.Ok).peer)
            mod.stop()
            db.close()
        }

    @Test
    fun stops_after_unique_peers_once_one_accepts() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            upsertAlive(db, "1.1.1.1")
            upsertAlive(db, "2.2.2.2")
            upsertAlive(db, "3.3.3.3")
            upsertAlive(db, "4.4.4.4")
            val bus = createMessageBus()
            val received = mutableListOf<Transaction>()
            val dialed = mutableListOf<String>()
            var created = 0
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
                        maxAttempts = 20,
                        dialerAttempts = 3,
                        random = { 0.0 },
                        createDialer = {
                            created += 1
                            object : TorByteDuplexDialer {
                                override suspend fun dial(
                                    host: String,
                                    port: Int,
                                    job: Job,
                                    onProgress: io.bluewallet.echalote.FetchProgressListener?,
                                ): ByteDuplex {
                                    dialed += host
                                    if (host == "1.1.1.1") return accept(host, port, job)
                                    error("unexpected EOF: wanted 64 bytes, got 0")
                                }

                                override suspend fun dispose() = Unit
                            }
                        },
                    ),
                )
            mod.start()
            bus.emit(Event.BroadcastRequest, BroadcastRequestPayload("once-ok", txHex()))
            val result = withTimeout(15_000) { done.await() }
            assertTrue(result is BroadcastDonePayload.Ok, result.toString())
            assertEquals(1, received.size)
            assertEquals(1, created)
            assertEquals(listOf("1.1.1.1", "2.2.2.2", "3.3.3.3", "4.4.4.4"), dialed)
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
