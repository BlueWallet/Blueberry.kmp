package io.bluewallet.blueberry.broadcast

import io.bluewallet.bip324.Message
import io.bluewallet.bip324.Transaction
import io.bluewallet.bip324.WireMessageType
import io.bluewallet.bip324.pairedByteDuplexes
import io.bluewallet.blueberry.bus.BroadcastDonePayload
import io.bluewallet.blueberry.bus.BroadcastRequestPayload
import io.bluewallet.blueberry.bus.BroadcastStopPayload
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.createMessageBus
import io.bluewallet.blueberry.peers.modules.ModuleContext
import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A stop only fails a job when no node was given the transaction. */
@OptIn(ExperimentalAtomicApi::class)
class BroadcastStopTest {
    @Test
    fun stop_while_waiting_for_alive_peers() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            val bus = createMessageBus()
            var dialed = 0
            val done =
                async {
                    val deferred = CompletableDeferred<BroadcastDonePayload>()
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
            bus.emit(Event.BroadcastRequest, BroadcastRequestPayload("4", sampleTxHex()))
            delay(25)
            mod.stop()
            val result = done.await() as BroadcastDonePayload.Error
            assertEquals(BROADCAST_STOPPED, result.error)
            assertEquals(0, dialed)
            db.close()
        }

    @Test
    fun stop_before_any_node_has_it_fails_the_job() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            val bus = createMessageBus()
            val done = CompletableDeferred<BroadcastDonePayload>()
            bus.on(Event.BroadcastDone) { done.complete(it) }
            val mod =
                createBroadcastModule(
                    ModuleContext(bus, db),
                    BroadcastModuleOptions(peerWaitPollMs = 10, connect = { _, _, _ -> error("should not dial") }),
                )
            mod.start()
            bus.emit(Event.BroadcastRequest, BroadcastRequestPayload("early", sampleTxHex()))
            delay(25)
            assertTrue(bus.emit(Event.BroadcastStop, BroadcastStopPayload("early")))
            val result = withTimeout(5_000) { done.await() } as BroadcastDonePayload.Error
            assertEquals("early", result.id)
            assertEquals(BROADCAST_CANCELLED, result.error)
            mod.stop()
            db.close()
        }

    @Test
    fun stop_after_a_hand_off_is_ignored() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            upsertAlive(db, "1.1.1.1")
            val bus = createMessageBus()
            val done = CompletableDeferred<BroadcastDonePayload>()
            bus.on(Event.BroadcastDone) { done.complete(it) }
            val handed = CompletableDeferred<Unit>()
            bus.on(Event.BroadcastHandedOff) { handed.complete(Unit) }
            val mod =
                createBroadcastModule(
                    ModuleContext(bus, db),
                    BroadcastModuleOptions(
                        peerWaitPollMs = 10,
                        ackTimeoutMs = 60_000,
                        connect = { _, _, _ ->
                            val (client, server) = pairedByteDuplexes()
                            CoroutineScope(Job() + Dispatchers.Default).launch {
                                acceptTx(server) {}
                                suspendCancellableCoroutine<Unit> { }
                            }
                            client
                        },
                    ),
                )
            mod.start()
            bus.emit(Event.BroadcastRequest, BroadcastRequestPayload("late", sampleTxHex()))
            withTimeout(5_000) { handed.await() }
            bus.emit(Event.BroadcastStop, BroadcastStopPayload("late"))
            delay(50)
            assertTrue(!done.isCompleted)
            mod.stop()
            val result = withTimeout(5_000) { done.await() }
            assertTrue(result is BroadcastDonePayload.Ok)
            db.close()
        }

    @Test
    fun no_v2_peer_within_the_wait_fails_the_job() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            val bus = createMessageBus()
            val done = CompletableDeferred<BroadcastDonePayload>()
            bus.on(Event.BroadcastDone) { done.complete(it) }
            val mod =
                createBroadcastModule(
                    ModuleContext(bus, db),
                    BroadcastModuleOptions(
                        peerWaitPollMs = 10,
                        peerWaitTimeoutMs = 30,
                        connect = { _, _, _ -> error("should not dial") },
                    ),
                )
            mod.start()
            bus.emit(Event.BroadcastRequest, BroadcastRequestPayload("lonely", sampleTxHex()))
            val result = withTimeout(5_000) { done.await() } as BroadcastDonePayload.Error
            assertEquals("lonely", result.id)
            assertEquals(BROADCAST_NO_PEERS, result.error)
            mod.stop()
            db.close()
        }

    @Test
    fun second_request_runs_beside_the_first() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            val bus = createMessageBus()
            val done = AtomicReference<List<BroadcastDonePayload>>(emptyList())
            bus.on(Event.BroadcastDone) { payload ->
                while (true) {
                    val cur = done.load()
                    if (done.compareAndSet(cur, cur + payload)) break
                }
            }
            val mod =
                createBroadcastModule(
                    ModuleContext(bus, db),
                    BroadcastModuleOptions(
                        peerWaitPollMs = 20,
                        connect = { _, _, _ -> error("should not dial") },
                    ),
                )
            mod.start()
            bus.emit(Event.BroadcastRequest, BroadcastRequestPayload("a", sampleTxHex()))
            bus.emit(Event.BroadcastRequest, BroadcastRequestPayload("b", sampleTxHex()))
            delay(40)
            assertEquals(0, done.load().size)
            mod.stop()
            assertEquals(setOf("a", "b"), done.load().map { it.id }.toSet())
            db.close()
        }

    @Test
    fun stop_after_a_peer_accepts_finishes_as_sent() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            upsertAlive(db, "1.1.1.1")
            upsertAlive(db, "2.2.2.2")
            val bus = createMessageBus()
            val received = mutableListOf<Transaction>()
            val accepting = connectAccepting(received)
            var calls = 0
            val done =
                async {
                    val deferred = CompletableDeferred<BroadcastDonePayload>()
                    bus.on(Event.BroadcastDone) { deferred.complete(it) }
                    deferred.await()
                }
            val mod =
                createBroadcastModule(
                    ModuleContext(bus, db),
                    BroadcastModuleOptions(
                        peerWaitPollMs = 15,
                        connect = { host, port, job ->
                            calls += 1
                            if (calls == 1) {
                                accepting(host, port, job)
                            } else {
                                suspendCancellableCoroutine { }
                            }
                        },
                    ),
                )
            mod.start()
            bus.emit(Event.BroadcastRequest, BroadcastRequestPayload("handed", sampleTxHex()))
            withTimeout(5_000) {
                while (received.isEmpty()) delay(15)
            }
            mod.stop()
            val result = withTimeout(5_000) { done.await() }
            assertTrue(result is BroadcastDonePayload.Ok)
            assertEquals("handed", result.id)
            db.close()
        }

    @Test
    fun stop_after_the_transaction_is_written_finishes_as_sent() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            upsertAlive(db, "1.1.1.1")
            val bus = createMessageBus()
            val wrote = CompletableDeferred<Unit>()
            val done =
                async {
                    val deferred = CompletableDeferred<BroadcastDonePayload>()
                    bus.on(Event.BroadcastDone) { deferred.complete(it) }
                    deferred.await()
                }
            val mod =
                createBroadcastModule(
                    ModuleContext(bus, db),
                    BroadcastModuleOptions(
                        peerWaitPollMs = 15,
                        ackTimeoutMs = 60_000,
                        connect = { _, _, _ ->
                            val (client, server) = pairedByteDuplexes()
                            CoroutineScope(Job() + Dispatchers.Default).launch {
                                try {
                                    acceptTx(server) {}
                                    wrote.complete(Unit)
                                    suspendCancellableCoroutine<Unit> { }
                                } finally {
                                    try {
                                        server.close()
                                    } catch (_: Throwable) {
                                    }
                                }
                            }
                            client
                        },
                    ),
                )
            val handedOff = mutableListOf<String>()
            bus.on(Event.BroadcastHandedOff) { handedOff += it.id }
            mod.start()
            bus.emit(Event.BroadcastRequest, BroadcastRequestPayload("written", sampleTxHex()))
            withTimeout(5_000) { wrote.await() }
            withTimeout(5_000) {
                while (handedOff.isEmpty()) delay(15)
            }
            mod.stop()
            val result = withTimeout(5_000) { done.await() }
            assertTrue(result is BroadcastDonePayload.Ok)
            assertEquals("written", result.id)
            assertFalse(result.acknowledged)
            assertEquals(listOf("written"), handedOff)
            db.close()
        }

    @Test
    fun a_node_that_goes_quiet_after_the_tx_is_handed_not_acknowledged() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            upsertAlive(db, "1.1.1.1")
            val bus = createMessageBus()
            val done =
                async {
                    val deferred = CompletableDeferred<BroadcastDonePayload>()
                    bus.on(Event.BroadcastDone) { deferred.complete(it) }
                    deferred.await()
                }
            val mod =
                createBroadcastModule(
                    ModuleContext(bus, db),
                    BroadcastModuleOptions(
                        maxAttempts = 1,
                        peerWaitPollMs = 15,
                        ackTimeoutMs = 50,
                        connect = { _, _, _ ->
                            val (client, server) = pairedByteDuplexes()
                            CoroutineScope(Job() + Dispatchers.Default).launch {
                                acceptTx(server) {}
                                suspendCancellableCoroutine<Unit> { }
                            }
                            client
                        },
                    ),
                )
            mod.start()
            bus.emit(Event.BroadcastRequest, BroadcastRequestPayload("quiet", sampleTxHex()))
            val result = withTimeout(5_000) { done.await() }
            assertTrue(result is BroadcastDonePayload.Ok)
            assertFalse(result.acknowledged)
            mod.stop()
            db.close()
        }

    @Test
    fun three_silent_nodes_end_the_job_without_dialing_more() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            listOf("1.1.1.1", "2.2.2.2", "3.3.3.3", "4.4.4.4", "5.5.5.5", "6.6.6.6").forEach { upsertAlive(db, it) }
            val bus = createMessageBus()
            val done =
                async {
                    val deferred = CompletableDeferred<BroadcastDonePayload>()
                    bus.on(Event.BroadcastDone) { deferred.complete(it) }
                    deferred.await()
                }
            var dials = 0
            val mod =
                createBroadcastModule(
                    ModuleContext(bus, db),
                    BroadcastModuleOptions(
                        peerWaitPollMs = 15,
                        ackTimeoutMs = 50,
                        connect = { _, _, _ ->
                            dials += 1
                            val (client, server) = pairedByteDuplexes()
                            CoroutineScope(Job() + Dispatchers.Default).launch {
                                acceptTx(server) {}
                                suspendCancellableCoroutine<Unit> { }
                            }
                            client
                        },
                    ),
                )
            mod.start()
            bus.emit(Event.BroadcastRequest, BroadcastRequestPayload("silent", sampleTxHex()))
            val result = withTimeout(5_000) { done.await() }
            assertTrue(result is BroadcastDonePayload.Ok)
            assertFalse(result.acknowledged)
            assertEquals(3, dials)
            mod.stop()
            db.close()
        }

    @Test
    fun a_job_every_node_refused_fails_as_refused() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            upsertAlive(db, "1.1.1.1")
            val bus = createMessageBus()
            val done =
                async {
                    val deferred = CompletableDeferred<BroadcastDonePayload>()
                    bus.on(Event.BroadcastDone) { deferred.complete(it) }
                    deferred.await()
                }
            val mod =
                createBroadcastModule(
                    ModuleContext(bus, db),
                    BroadcastModuleOptions(
                        maxAttempts = 1,
                        peerWaitPollMs = 15,
                        connect = { _, _, _ ->
                            val (client, server) = pairedByteDuplexes()
                            CoroutineScope(Job() + Dispatchers.Default).launch {
                                acceptTx(server) { protocol ->
                                    protocol.writeMessage(
                                        Message.Opaque(WireMessageType.Long("reject"), txRejectPayload(sampleTxHex())),
                                    )
                                }
                            }
                            client
                        },
                    ),
                )
            mod.start()
            bus.emit(Event.BroadcastRequest, BroadcastRequestPayload("refused", sampleTxHex()))
            val result = withTimeout(5_000) { done.await() }
            assertTrue(result is BroadcastDonePayload.Error)
            assertEquals("refused", result.id)
            assertTrue(result.refused)
            assertEquals(BROADCAST_REFUSED, result.error)
            mod.stop()
            db.close()
        }
}
