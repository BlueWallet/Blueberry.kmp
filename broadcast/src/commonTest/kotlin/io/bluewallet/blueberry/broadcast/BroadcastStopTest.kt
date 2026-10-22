package io.bluewallet.blueberry.broadcast

import io.bluewallet.bip324.Message
import io.bluewallet.bip324.Transaction
import io.bluewallet.bip324.WireMessageType
import io.bluewallet.bip324.pairedByteDuplexes
import io.bluewallet.blueberry.bus.BroadcastDonePayload
import io.bluewallet.blueberry.bus.BroadcastRequestPayload
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A stop only fails a job when no node was given the transaction. */
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
    fun second_request_runs_beside_the_first() =
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
            bus.emit(Event.BroadcastRequest, BroadcastRequestPayload("a", sampleTxHex()))
            bus.emit(Event.BroadcastRequest, BroadcastRequestPayload("b", sampleTxHex()))
            delay(40)
            assertEquals(0, done.size)
            mod.stop()
            assertEquals(setOf("a", "b"), done.map { it.id }.toSet())
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
                                    acceptTx(server)
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
            assertEquals(listOf("written"), handedOff)
            db.close()
        }

    @Test
    fun a_rejected_hand_off_still_fails() =
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
                                    protocol.writeMessage(Message.Opaque(WireMessageType.Long("reject"), byteArrayOf(0)))
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
            assertTrue(result.error.contains("rejected"))
            mod.stop()
            db.close()
        }
}
