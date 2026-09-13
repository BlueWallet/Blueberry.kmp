package io.bluewallet.blueberry.peers.net

import io.bluewallet.bip324.Message
import io.bluewallet.bip324.NetworkAddress
import io.bluewallet.bip324.Networks
import io.bluewallet.bip324.Protocol
import io.bluewallet.bip324.ProtocolOptions
import io.bluewallet.bip324.Role
import io.bluewallet.bip324.VersionPayload
import io.bluewallet.bip324.decodeBlock
import io.bluewallet.bip324.hexToBytes
import io.bluewallet.bip324.pairedByteDuplexes
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val GENESIS_BLOCK_HEX =
    "01000000" +
        "0000000000000000000000000000000000000000000000000000000000000000" +
        "3ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a" +
        "29ab5f49ffff001d1dac2b7c" +
        "01" +
        "01000000" +
        "01" +
        "0000000000000000000000000000000000000000000000000000000000000000" +
        "ffffffff" +
        "4d" +
        "04ffff001d0104455468652054696d65732030332f4a616e2f32303039204368616e63656c6c6f72206f6e206272696e6b206f66207365636f6e64206261696c6f757420666f722062616e6b73" +
        "ffffffff" +
        "01" +
        "00f2052a01000000" +
        "43" +
        "4104678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5fac" +
        "00000000"

private suspend fun answerVersionVerack(
    protocol: Protocol,
    port: Int,
) {
    val msg = protocol.readMessage()
    check(msg is Message.Version) { "expected version" }
    protocol.writeMessage(
        Message.Version(
            VersionPayload(
                version = 70_016,
                services = 1033uL,
                timestamp = 0,
                receiver = NetworkAddress(0uL, ByteArray(16), port),
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
        if (protocol.readMessage() is Message.Verack) return
    }
}

class BlockSyncTest {
    @Test
    fun maps_connect_failure_to_err() =
        runBlocking {
            val result =
                openBlockSession(
                    "1.2.3.4",
                    8333,
                    BlockSyncOptions(
                        connectTimeoutMs = 100,
                        syncTimeoutMs = 100,
                        connect = { _, _ -> error("ECONNREFUSED") },
                    ),
                )
            assertIs<BlockBatchResult.Err>(result)
            Unit
        }

    @Test
    fun getBlock_getdata_uses_MSG_WITNESS_BLOCK() =
        runBlocking {
            val (clientSide, serverSide) = pairedByteDuplexes()
            val hash = ByteArray(32) { 0xab.toByte() }
            val genesis = decodeBlock(hexToBytes(GENESIS_BLOCK_HEX))

            coroutineScope {
                val server =
                    async {
                        val protocol =
                            Protocol.connect(
                                serverSide,
                                ProtocolOptions(role = Role.Responder, network = Networks.mainnet),
                            )
                        answerVersionVerack(protocol, 8333)
                        val msg = protocol.readMessage()
                        assertIs<Message.GetData>(msg)
                        assertEquals(1, msg.payload.inventory.size)
                        assertEquals(MSG_WITNESS_BLOCK, msg.payload.inventory[0].type)
                        assertTrue(
                            msg.payload.inventory[0]
                                .hash
                                .contentEquals(hash),
                        )
                        assertNotEquals(MSG_BLOCK, msg.payload.inventory[0].type)
                        protocol.writeMessage(Message.Block(genesis))
                        protocol.close()
                    }

                val opened =
                    openBlockSession(
                        "127.0.0.1",
                        8333,
                        BlockSyncOptions(
                            connectTimeoutMs = 2_000,
                            syncTimeoutMs = 2_000,
                            connect = { _, _ -> clientSide },
                        ),
                    )
                val ok = assertIs<BlockBatchResult.Ok<BlockSessionApi>>(opened)
                val block = ok.value.getBlock(hash)
                assertEquals(1, block.transactions.size)
                ok.value.close()
                server.await()
            }
        }
}
