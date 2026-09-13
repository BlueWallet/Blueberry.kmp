package io.bluewallet.blueberry.peers.net

import io.bluewallet.bip324.AddrV2Payload
import io.bluewallet.bip324.ByteDuplex
import io.bluewallet.bip324.Message
import io.bluewallet.bip324.NetworkAddress
import io.bluewallet.bip324.NetworkAddressV2
import io.bluewallet.bip324.Networks
import io.bluewallet.bip324.Protocol
import io.bluewallet.bip324.ProtocolOptions
import io.bluewallet.bip324.Role
import io.bluewallet.bip324.VersionPayload
import io.bluewallet.bip324.answerPing
import io.bluewallet.bip324.pairedByteDuplexes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeerProbeTest {
    @Test
    fun maps_connect_failure_to_err() =
        runBlocking {
            val result =
                probePeer(
                    "1.2.3.4",
                    8333,
                    ProbeOptions(
                        timeoutMs = 1000,
                        connect = { _, _ -> error("ECONNREFUSED") },
                        handshakeAndGetAddr = { _, _ -> HandshakeResult(emptyList(), 0uL) },
                    ),
                )
            assertTrue(result is ProbeResult.Err)
            assertTrue((result as ProbeResult.Err).error.contains("ECONNREFUSED"))
        }

    @Test
    fun timeout_aborts_slow_connect_and_closes_duplex() =
        runBlocking {
            var closed = false
            val result =
                probePeer(
                    "1.2.3.4",
                    8333,
                    ProbeOptions(
                        timeoutMs = 20,
                        connect = { _, _ ->
                            withContext(NonCancellable) { delay(200) }
                            val inner = stubDuplex()
                            object : ByteDuplex {
                                override suspend fun read(n: Int) = inner.read(n)

                                override suspend fun write(bytes: ByteArray) = inner.write(bytes)

                                override suspend fun close() {
                                    closed = true
                                    inner.close()
                                }
                            }
                        },
                        handshakeAndGetAddr = { _, _ -> HandshakeResult(emptyList(), 0uL) },
                    ),
                )
            assertTrue(result is ProbeResult.Err)
            assertTrue(
                (result as ProbeResult.Err).error.contains("timed out") ||
                    result.error.contains("aborted"),
            )
            delay(250)
            assertTrue(closed)
        }

    @Test
    fun succeeds_after_verack_without_waiting_for_getaddr() =
        runBlocking {
            coroutineScope {
                val (clientSide, serverSide) = pairedByteDuplexes()
                val server =
                    async {
                        val protocol =
                            Protocol.connect(
                                serverSide,
                                ProtocolOptions(role = Role.Responder, network = Networks.mainnet),
                            )
                        val version = protocol.readMessage()
                        check(version is Message.Version)
                        protocol.writeMessage(
                            Message.Version(
                                VersionPayload(
                                    version = 70_016,
                                    services = 1033uL,
                                    timestamp = 0,
                                    receiver = NetworkAddress(0uL, ByteArray(16), 8333),
                                    sender = NetworkAddress(0uL, ByteArray(16), 0),
                                    nonce = 1uL,
                                    userAgent = "/test/",
                                    startHeight = 0,
                                    relay = false,
                                ),
                            ),
                        )
                        protocol.writeMessage(Message.Verack)
                        while (true) {
                            val msg = protocol.readMessage()
                            if (msg.command == "verack") break
                        }
                        delay(50)
                        protocol.close()
                    }
                val result =
                    probePeer(
                        "127.0.0.1",
                        8333,
                        ProbeOptions(
                            timeoutMs = 2_000,
                            connect = { _, _ -> clientSide },
                        ),
                    )
                assertTrue(result is ProbeResult.Ok)
                val ok = result as ProbeResult.Ok
                assertEquals(emptyList(), ok.peers)
                assertEquals(1033uL, ok.services)
                server.await()
            }
        }

    @Test
    fun wantAddr_collects_addrv2_after_handshake_and_skips_onion() =
        runBlocking {
            coroutineScope {
                val (clientSide, serverSide) = pairedByteDuplexes()
                val server =
                    async {
                        serveHandshake(serverSide) { protocol ->
                            while (true) {
                                val msg = protocol.readMessage()
                                if (msg.command == "getaddr") break
                                answerPing(protocol, msg)
                            }
                            protocol.writeMessage(
                                Message.AddrV2(
                                    AddrV2Payload(
                                        listOf(
                                            NetworkAddressV2(1u, 0uL, 4, ByteArray(32), 8333),
                                            NetworkAddressV2(1u, 1033uL, 1, byteArrayOf(1, 2, 3, 4), 8333),
                                            NetworkAddressV2(1u, 64uL, 1, byteArrayOf(5, 6, 7, 8), 8333),
                                        ),
                                    ),
                                ),
                            )
                            protocol.close()
                        }
                    }
                val result =
                    probePeer(
                        "127.0.0.1",
                        8333,
                        ProbeOptions(
                            timeoutMs = 2_000,
                            addrTimeoutMs = 2_000,
                            wantAddr = true,
                            connect = { _, _ -> clientSide },
                        ),
                    )
                assertTrue(result is ProbeResult.Ok)
                val ok = result as ProbeResult.Ok
                assertEquals(1033uL, ok.services)
                assertEquals(
                    listOf(
                        PeerCandidate("1.2.3.4", 8333, 1033uL),
                        PeerCandidate("5.6.7.8", 8333, 64uL),
                    ),
                    ok.peers,
                )
                server.await()
            }
        }

    @Test
    fun wantAddr_caps_a_stream_of_one_address_messages() =
        runBlocking {
            coroutineScope {
                val (clientSide, serverSide) = pairedByteDuplexes()
                val server =
                    async {
                        serveHandshake(serverSide) { protocol ->
                            while (true) {
                                val msg = protocol.readMessage()
                                if (msg.command == "getaddr") break
                            }
                            try {
                                for (i in 0 until 1_005) {
                                    protocol.writeMessage(
                                        Message.AddrV2(
                                            AddrV2Payload(
                                                listOf(
                                                    NetworkAddressV2(
                                                        time = 1u,
                                                        services = 1uL,
                                                        networkId = 1,
                                                        address =
                                                            byteArrayOf(
                                                                10,
                                                                ((i shr 16) and 0xff).toByte(),
                                                                ((i shr 8) and 0xff).toByte(),
                                                                (i and 0xff).toByte(),
                                                            ),
                                                        port = 8333,
                                                    ),
                                                ),
                                            ),
                                        ),
                                    )
                                }
                            } catch (_: Throwable) {
                            }
                            protocol.close()
                        }
                    }
                val result =
                    probePeer(
                        "127.0.0.1",
                        8333,
                        ProbeOptions(
                            timeoutMs = 2_000,
                            addrTimeoutMs = 2_000,
                            wantAddr = true,
                            connect = { _, _ -> clientSide },
                        ),
                    )
                assertTrue(result is ProbeResult.Ok)
                assertEquals(1_000, (result as ProbeResult.Ok).peers.size)
                server.await()
            }
        }

    @Test
    fun wantAddr_timeout_after_handshake_still_returns_ok_with_empty_peers() =
        runBlocking {
            coroutineScope {
                val (clientSide, serverSide) = pairedByteDuplexes()
                val server =
                    async {
                        serveHandshake(serverSide) { protocol ->
                            delay(200)
                            protocol.close()
                        }
                    }
                val result =
                    probePeer(
                        "127.0.0.1",
                        8333,
                        ProbeOptions(
                            timeoutMs = 2_000,
                            addrTimeoutMs = 40,
                            wantAddr = true,
                            connect = { _, _ -> clientSide },
                        ),
                    )
                assertTrue(result is ProbeResult.Ok)
                val ok = result as ProbeResult.Ok
                assertEquals(emptyList(), ok.peers)
                assertEquals(1033uL, ok.services)
                server.await()
            }
        }

    @Test
    fun wantAddr_timeout_also_bounds_a_blocked_pong_write() =
        runBlocking {
            coroutineScope {
                val (clientSide, serverSide) = pairedByteDuplexes()
                var blockWrites = false
                val client =
                    object : ByteDuplex {
                        override suspend fun read(n: Int) = clientSide.read(n)

                        override suspend fun write(bytes: ByteArray) {
                            if (blockWrites) {
                                delay(60_000)
                                return
                            }
                            clientSide.write(bytes)
                        }

                        override suspend fun close() = clientSide.close()
                    }
                val server =
                    async {
                        serveHandshake(serverSide) { protocol ->
                            while (true) {
                                val msg = protocol.readMessage()
                                if (msg.command == "getaddr") break
                            }
                            blockWrites = true
                            protocol.writeMessage(Message.Ping(ByteArray(8)))
                            delay(100)
                            protocol.close()
                        }
                    }
                val result =
                    withTimeout(500) {
                        probePeer(
                            "127.0.0.1",
                            8333,
                            ProbeOptions(
                                timeoutMs = 2_000,
                                addrTimeoutMs = 40,
                                wantAddr = true,
                                connect = { _, _ -> client },
                            ),
                        )
                    }
                assertTrue(result is ProbeResult.Ok)
                assertEquals(emptyList(), (result as ProbeResult.Ok).peers)
                server.await()
            }
        }

    @Test
    fun parent_cancel_during_addr_wait_is_not_a_failed_handshake() =
        runBlocking {
            coroutineScope {
                val (clientSide, serverSide) = pairedByteDuplexes()
                val enteredAddr = CompletableDeferred<Unit>()
                val server =
                    async {
                        serveHandshake(serverSide) { protocol ->
                            while (true) {
                                val msg = protocol.readMessage()
                                if (msg.command == "getaddr") {
                                    enteredAddr.complete(Unit)
                                    break
                                }
                            }
                            delay(10_000)
                            protocol.close()
                        }
                    }
                var result: ProbeResult? = null
                val job =
                    launch {
                        result =
                            probePeer(
                                "127.0.0.1",
                                8333,
                                ProbeOptions(
                                    timeoutMs = 2_000,
                                    addrTimeoutMs = 5_000,
                                    wantAddr = true,
                                    connect = { _, _ -> clientSide },
                                ),
                            )
                    }
                enteredAddr.await()
                job.cancel()
                job.join()
                assertNull(result)
                server.cancel()
            }
        }
}

private suspend fun serveHandshake(
    serverSide: ByteDuplex,
    afterVerack: suspend (Protocol) -> Unit,
) {
    val protocol =
        Protocol.connect(
            serverSide,
            ProtocolOptions(role = Role.Responder, network = Networks.mainnet),
        )
    val version = protocol.readMessage()
    check(version is Message.Version)
    protocol.writeMessage(
        Message.Version(
            VersionPayload(
                version = 70_016,
                services = 1033uL,
                timestamp = 0,
                receiver = NetworkAddress(0uL, ByteArray(16), 8333),
                sender = NetworkAddress(0uL, ByteArray(16), 0),
                nonce = 1uL,
                userAgent = "/test/",
                startHeight = 0,
                relay = false,
            ),
        ),
    )
    protocol.writeMessage(Message.Verack)
    while (true) {
        val msg = protocol.readMessage()
        if (msg.command == "verack") break
        answerPing(protocol, msg)
    }
    afterVerack(protocol)
}
