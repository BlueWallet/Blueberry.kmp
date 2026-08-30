package io.bluewallet.blueberry.blocks.modules

import io.bluewallet.bip324.BlockPayload
import io.bluewallet.bip324.bytesToHex
import io.bluewallet.bip324.decodeBlock
import io.bluewallet.bip324.encodeBlock
import io.bluewallet.bip324.encodeBlockHeader
import io.bluewallet.bip324.hexToBytes
import io.bluewallet.bip324.sha256d
import io.bluewallet.blueberry.blocks.waitFor
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.bus.FiltersMatchPayload
import io.bluewallet.blueberry.bus.PeersUpdatedPayload
import io.bluewallet.blueberry.bus.SyncIdlePayload
import io.bluewallet.blueberry.bus.createMessageBus
import io.bluewallet.blueberry.peers.modules.ModuleContext
import io.bluewallet.blueberry.peers.net.BlockBatchResult
import io.bluewallet.blueberry.peers.net.BlockSessionApi
import io.bluewallet.blueberry.peers.net.BlockSyncOptions
import io.bluewallet.blueberry.peers.net.DnsResolver
import io.bluewallet.blueberry.peers.net.PlatformNet
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.MatchedBlock
import io.bluewallet.blueberry.storage.PeerWrite
import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val NODE_NETWORK = 1uL

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

private fun stubPlatformNet(): PlatformNet = PlatformNet(
    connect = { _, _ -> error("stub PlatformNet.connect unused") },
    dns = object : DnsResolver {
        override suspend fun resolve4(host: String) = emptyList<String>()
        override suspend fun resolve6(host: String) = emptyList<String>()
    },
)

private fun internalHashHex(payload: BlockPayload): String =
    bytesToHex(sha256d(encodeBlockHeader(payload.header)))

private fun makeVariantBlock(nonceDelta: UInt): BlockPayload {
    val genesis = decodeBlock(hexToBytes(GENESIS_BLOCK_HEX))
    return BlockPayload(
        header = io.bluewallet.bip324.BlockHeader(
            version = genesis.header.version,
            previousBlockHash = genesis.header.previousBlockHash,
            merkleRoot = genesis.header.merkleRoot,
            timestamp = genesis.header.timestamp,
            bits = genesis.header.bits,
            nonce = genesis.header.nonce + nonceDelta,
        ),
        transactions = genesis.transactions,
    )
}

private fun seedPeer(db: Database, host: String) {
    db.peers.upsert(
        PeerWrite(
            host = host,
            port = 8333,
            services = NODE_NETWORK,
            alive = true,
            usedForBlocks = false,
            lastProbedAt = null,
        ),
    )
}

private fun makeOpenSession(
    blocksByInternalHex: Map<String, BlockPayload>,
    onOpen: ((String) -> Unit)? = null,
    mismatchFor: Set<String> = emptySet(),
    beforeGetBlock: (suspend () -> Unit)? = null,
): suspend (String, Int, BlockSyncOptions) -> BlockBatchResult<BlockSessionApi> = { host, _, _ ->
    onOpen?.invoke(host)
    val session = object : BlockSessionApi {
        override val services = NODE_NETWORK
        override suspend fun getBlock(hashInternal: ByteArray): BlockPayload {
            if (beforeGetBlock != null) beforeGetBlock()
            val key = bytesToHex(hashInternal)
            if (key in mismatchFor) {
                return decodeBlock(hexToBytes(GENESIS_BLOCK_HEX))
            }
            return blocksByInternalHex[key] ?: error("no fixture for $key")
        }
        override suspend fun close() {}
    }
    BlockBatchResult.Ok(session)
}

class BlocksDownloadTest {
    @Test
    fun start_emits_progress_from_db_downloads_persists_marks_peer_used() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val block = makeVariantBlock(0u)
        val internalHex = internalHashHex(block)

        db.matchedBlocks.insert(MatchedBlock(0, internalHex))
        seedPeer(db, "1.1.1.1")

        val events = mutableListOf<Pair<Int, Int>>()
        bus.on(Event.BlocksProgress) { events.add(it.downloaded to it.matched) }

        val logs = mutableListOf<String>()
        val mod = createBlocksDownloadModule(
            ModuleContext(bus, db),
            BlocksDownloadOptions(
                net = stubPlatformNet(),
                openSession = makeOpenSession(mapOf(internalHex to block)),
                concurrency = 2,
                log = { logs.add(it) },
            ),
        )
        mod.start()
        assertEquals(0 to 1, events[0])

        waitFor { db.blocks.count() == 1 }
        val stored = db.blocks.get(0)!!
        assertEquals(0, stored.height)
        assertEquals(internalHex, stored.blockHashInternalHex)
        assertContentEquals(encodeBlock(block), stored.block)
        assertTrue(db.peers.list()[0].usedForBlocks)
        assertTrue(events.any { it == 1 to 1 })
        assertTrue(
            logs.any {
                it == "module start concurrency=2 connectTimeoutMs=15000 syncTimeoutMs=30000"
            },
        )
        assertTrue(logs.contains("block start attempt=1 peer=1.1.1.1:8333"))
        assertTrue(
            logs.any {
                Regex("^block success attempt=1 peer=1\\.1\\.1\\.1:8333 bytes=\\d+ elapsedMs=\\d+$")
                    .matches(it)
            },
        )
        assertTrue(logs.none { it.contains("height=") })

        mod.stop()
        assertTrue(logs.contains("module stopped"))
        db.close()
    }

    @Test
    fun successful_peer_is_never_reused_for_another_block() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val b0 = makeVariantBlock(0u)
        val b1 = makeVariantBlock(1u)
        val h0 = internalHashHex(b0)
        val h1 = internalHashHex(b1)

        db.matchedBlocks.insert(MatchedBlock(0, h0))
        db.matchedBlocks.insert(MatchedBlock(1, h1))
        seedPeer(db, "1.1.1.1")
        seedPeer(db, "2.2.2.2")

        val opened = mutableListOf<String>()
        val mod = createBlocksDownloadModule(
            ModuleContext(bus, db),
            BlocksDownloadOptions(
                net = stubPlatformNet(),
                openSession = makeOpenSession(
                    mapOf(h0 to b0, h1 to b1),
                    onOpen = { opened.add(it) },
                ),
                concurrency = 1,
            ),
        )
        mod.start()
        waitFor { db.blocks.count() == 2 }

        assertEquals(2, opened.size)
        assertEquals(2, opened.toSet().size)
        assertTrue(db.peers.list().all { it.usedForBlocks })

        mod.stop()
        db.close()
    }

    @Test
    fun instant_successes_do_not_take_the_stalled_peer_wait_path() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val blocks = List(5) { makeVariantBlock(it.toUInt()) }
        val fixtures = blocks.associateBy { internalHashHex(it) }
        for (i in blocks.indices) {
            db.matchedBlocks.insert(MatchedBlock(i, internalHashHex(blocks[i])))
            seedPeer(db, "${i + 1}.${i + 1}.${i + 1}.${i + 1}")
        }

        val logs = mutableListOf<String>()
        val started = nowMillis()
        val mod = createBlocksDownloadModule(
            ModuleContext(bus, db),
            BlocksDownloadOptions(
                net = stubPlatformNet(),
                openSession = makeOpenSession(fixtures),
                concurrency = 1,
                log = { logs.add(it) },
            ),
        )
        mod.start()
        waitFor(1500) { db.blocks.count() == 5 }
        assertTrue(nowMillis() - started < 1500)
        assertTrue(logs.none { it.startsWith("queue stalled") })

        mod.stop()
        db.close()
    }

    @Test
    fun does_not_reuse_a_used_peer_when_it_is_the_only_NODE_NETWORK_peer() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val b0 = makeVariantBlock(0u)
        val b1 = makeVariantBlock(1u)
        val h0 = internalHashHex(b0)
        val h1 = internalHashHex(b1)

        db.matchedBlocks.insert(MatchedBlock(0, h0))
        db.matchedBlocks.insert(MatchedBlock(1, h1))
        seedPeer(db, "1.1.1.1")

        val opened = mutableListOf<String>()
        val mod = createBlocksDownloadModule(
            ModuleContext(bus, db),
            BlocksDownloadOptions(
                net = stubPlatformNet(),
                openSession = makeOpenSession(
                    mapOf(h0 to b0, h1 to b1),
                    onOpen = { opened.add(it) },
                ),
                concurrency = 1,
                idleDelayMs = 50,
            ),
        )
        mod.start()
        waitFor { db.blocks.count() == 1 }
        delay(80)
        assertEquals(listOf("1.1.1.1"), opened)
        assertEquals(1, db.blocks.count())
        assertTrue(db.peers.list()[0].usedForBlocks)

        mod.stop()
        db.close()
    }

    @Test
    fun idle_resumes_on_filters_match() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val block = makeVariantBlock(0u)
        val internalHex = internalHashHex(block)
        seedPeer(db, "1.1.1.1")

        val mod = createBlocksDownloadModule(
            ModuleContext(bus, db),
            BlocksDownloadOptions(
                net = stubPlatformNet(),
                openSession = makeOpenSession(mapOf(internalHex to block)),
                concurrency = 1,
            ),
        )
        mod.start()
        delay(30)
        assertEquals(0, db.blocks.count())

        db.matchedBlocks.insert(MatchedBlock(0, internalHex))
        bus.emit(Event.FiltersMatch, FiltersMatchPayload(0, internalHex))
        waitFor { db.blocks.count() == 1 }

        mod.stop()
        db.close()
    }

    @Test
    fun idle_picks_up_a_new_match_even_if_the_kick_is_lost() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val b0 = makeVariantBlock(0u)
        val b1 = makeVariantBlock(1u)
        val h0 = internalHashHex(b0)
        val h1 = internalHashHex(b1)

        db.matchedBlocks.insert(MatchedBlock(0, h0))
        seedPeer(db, "1.1.1.1")
        seedPeer(db, "2.2.2.2")

        val mod = createBlocksDownloadModule(
            ModuleContext(bus, db),
            BlocksDownloadOptions(
                net = stubPlatformNet(),
                openSession = makeOpenSession(mapOf(h0 to b0, h1 to b1)),
                concurrency = 1,
            ),
        )
        mod.start()
        waitFor { db.blocks.count() == 1 }

        db.matchedBlocks.insert(MatchedBlock(1, h1))
        waitFor(5000) { db.blocks.count() == 2 }

        mod.stop()
        db.close()
    }

    @Test
    fun in_flight_download_does_not_block_a_later_match() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val b0 = makeVariantBlock(0u)
        val b1 = makeVariantBlock(1u)
        val h0 = internalHashHex(b0)
        val h1 = internalHashHex(b1)

        db.matchedBlocks.insert(MatchedBlock(0, h0))
        seedPeer(db, "1.1.1.1")
        seedPeer(db, "2.2.2.2")

        val held = CompletableDeferred<Unit>()
        var inFlight = 0

        val mod = createBlocksDownloadModule(
            ModuleContext(bus, db),
            BlocksDownloadOptions(
                net = stubPlatformNet(),
                openSession = makeOpenSession(
                    mapOf(h0 to b0, h1 to b1),
                    beforeGetBlock = {
                        inFlight++
                        held.await()
                    },
                ),
                concurrency = 1,
            ),
        )
        mod.start()
        waitFor { inFlight == 1 }
        assertEquals(0, db.blocks.count())

        db.matchedBlocks.insert(MatchedBlock(1, h1))
        bus.emit(Event.FiltersMatch, FiltersMatchPayload(1, h1))
        assertEquals(0, db.blocks.count())

        held.complete(Unit)
        waitFor { db.blocks.count() == 2 }

        mod.stop()
        db.close()
    }

    @Test
    fun failed_downloads_retry_under_concurrency_until_complete() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val blocks = List(8) { makeVariantBlock(it.toUInt()) }
        val fixtures = blocks.associateBy { internalHashHex(it) }
        for (i in blocks.indices) {
            db.matchedBlocks.insert(MatchedBlock(i, internalHashHex(blocks[i])))
            seedPeer(db, "${i + 1}.${i + 1}.${i + 1}.${i + 1}")
        }

        val attempts = mutableMapOf<String, Int>()
        val base = makeOpenSession(fixtures)
        val mod = createBlocksDownloadModule(
            ModuleContext(bus, db),
            BlocksDownloadOptions(
                net = stubPlatformNet(),
                openSession = { host, port, opts ->
                    when (val opened = base(host, port, opts)) {
                        is BlockBatchResult.Err -> opened
                        is BlockBatchResult.Ok -> BlockBatchResult.Ok(
                            object : BlockSessionApi by opened.value {
                                override suspend fun getBlock(hashInternal: ByteArray): BlockPayload {
                                    val key = bytesToHex(hashInternal)
                                    val n = (attempts[key] ?: 0) + 1
                                    attempts[key] = n
                                    if (n == 1) error("transient")
                                    return opened.value.getBlock(hashInternal)
                                }
                            },
                        )
                    }
                },
                concurrency = 8,
            ),
        )
        mod.start()
        waitFor(10_000) { db.blocks.count() == 8 }
        assertEquals(emptyList(), db.matchedBlocks.listNeedingDownload(10))
        mod.stop()
        db.close()
    }

    @Test
    fun keeps_polling_while_pending_remain_no_bus_kick_required() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val block = makeVariantBlock(0u)
        val internalHex = internalHashHex(block)
        db.matchedBlocks.insert(MatchedBlock(0, internalHex))

        var allow = false
        val stalled = CompletableDeferred<Unit>()
        val failedOnce = CompletableDeferred<Unit>()
        val firstFailure = Regex(
            "^session open failure attempt=1 peer=1\\.1\\.1\\.1:8333 elapsedMs=\\d+ cooldownMs=3000 error=not yet$",
        )
        val success = makeOpenSession(mapOf(internalHex to block))
        val mod = createBlocksDownloadModule(
            ModuleContext(bus, db),
            BlocksDownloadOptions(
                net = stubPlatformNet(),
                openSession = { host, port, opts ->
                    if (!allow) return@BlocksDownloadOptions BlockBatchResult.Err("not yet")
                    success(host, port, opts)
                },
                concurrency = 1,
                log = {
                    if (it == "queue stalled pending=1 inFlight=0 leasedPeers=0 coolingPeers=0") {
                        stalled.complete(Unit)
                    }
                    if (firstFailure.matches(it)) failedOnce.complete(Unit)
                },
            ),
        )
        mod.start()
        waitFor { stalled.isCompleted }
        seedPeer(db, "1.1.1.1")
        waitFor(2000) { failedOnce.isCompleted }
        allow = true
        waitFor(5000) { db.blocks.count() == 1 }
        mod.stop()
        db.close()
    }

    @Test
    fun discards_in_flight_block_after_reorg_replaces_the_match_hash() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val orphan = makeVariantBlock(1u)
        val orphanHex = internalHashHex(orphan)
        val replacement = makeVariantBlock(2u)
        val replacementHex = internalHashHex(replacement)

        db.matchedBlocks.insert(MatchedBlock(10, orphanHex))
        seedPeer(db, "1.1.1.1")
        seedPeer(db, "2.2.2.2")

        val held = CompletableDeferred<Unit>()
        val mod = createBlocksDownloadModule(
            ModuleContext(bus, db),
            BlocksDownloadOptions(
                net = stubPlatformNet(),
                openSession = makeOpenSession(
                    mapOf(orphanHex to orphan),
                    beforeGetBlock = { held.await() },
                ),
                concurrency = 1,
            ),
        )
        mod.start()
        delay(40)

        db.rewindAfter(9)
        db.matchedBlocks.insert(MatchedBlock(10, replacementHex))
        held.complete(Unit)
        delay(80)

        assertFalse(db.blocks.has(10))
        assertEquals(0, db.blocks.count())
        assertEquals(replacementHex, db.matchedBlocks.get(10)?.blockHashInternalHex)

        mod.stop()
        db.close()
    }

    @Test
    fun hash_mismatch_nothing_persisted_peer_not_marked_used() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val want = makeVariantBlock(1u)
        val internalHex = internalHashHex(want)

        db.matchedBlocks.insert(MatchedBlock(1, internalHex))
        seedPeer(db, "1.1.1.1")

        var attempts = 0
        val logs = mutableListOf<String>()
        val mod = createBlocksDownloadModule(
            ModuleContext(bus, db),
            BlocksDownloadOptions(
                net = stubPlatformNet(),
                openSession = makeOpenSession(
                    mapOf(internalHex to want),
                    mismatchFor = setOf(internalHex),
                    beforeGetBlock = { attempts++ },
                ),
                concurrency = 1,
                log = { logs.add(it) },
            ),
        )
        mod.start()
        waitFor { attempts >= 1 }
        waitFor {
            logs.any {
                Regex(
                    "^block failure attempt=1 peer=1\\.1\\.1\\.1:8333 phase=validate elapsedMs=\\d+ cooldownMs=3000 error=",
                ).containsMatchIn(it)
            }
        }

        assertEquals(0, db.blocks.count())
        assertFalse(db.peers.list()[0].usedForBlocks)

        mod.stop()
        db.close()
    }

    @Test
    fun openSession_throw_does_not_kill_the_download_loop() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val block = makeVariantBlock(0u)
        val internalHex = internalHashHex(block)
        db.matchedBlocks.insert(MatchedBlock(0, internalHex))
        seedPeer(db, "1.1.1.1")
        seedPeer(db, "2.2.2.2")

        val logs = mutableListOf<String>()
        val success = makeOpenSession(mapOf(internalHex to block))
        val mod = createBlocksDownloadModule(
            ModuleContext(bus, db),
            BlocksDownloadOptions(
                net = stubPlatformNet(),
                openSession = { host, port, opts ->
                    if (host == "1.1.1.1") error("connect exploded")
                    success(host, port, opts)
                },
                concurrency = 1,
                log = { logs.add(it) },
            ),
        )
        mod.start()
        waitFor { db.blocks.count() == 1 }
        assertTrue(
            logs.any {
                Regex(
                    "^block failure attempt=\\d+ peer=1\\.1\\.1\\.1:8333 phase=session elapsedMs=\\d+ cooldownMs=3000 error=connect exploded$",
                ).matches(it)
            },
        )

        mod.stop()
        db.close()
    }

    @Test
    fun honors_idleDelayMs_when_a_match_appears_without_a_kick() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val block = makeVariantBlock(0u)
        val internalHex = internalHashHex(block)
        seedPeer(db, "1.1.1.1")

        val mod = createBlocksDownloadModule(
            ModuleContext(bus, db),
            BlocksDownloadOptions(
                net = stubPlatformNet(),
                openSession = makeOpenSession(mapOf(internalHex to block)),
                concurrency = 1,
                idleDelayMs = 40,
            ),
        )
        mod.start()
        delay(20)
        assertEquals(0, db.blocks.count())

        db.matchedBlocks.insert(MatchedBlock(0, internalHex))
        waitFor(800) { db.blocks.count() == 1 }

        mod.stop()
        db.close()
    }

    @Test
    fun while_sync_idle_peers_updated_does_not_open_sessions() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val b0 = makeVariantBlock(0u)
        val h0 = internalHashHex(b0)
        db.matchedBlocks.insert(MatchedBlock(0, h0))
        seedPeer(db, "1.1.1.1")

        var opens = 0
        val success = makeOpenSession(mapOf(h0 to b0))
        val mod = createBlocksDownloadModule(
            ModuleContext(bus, db),
            BlocksDownloadOptions(
                net = stubPlatformNet(),
                openSession = { host, port, opts ->
                    opens++
                    success(host, port, opts)
                },
                concurrency = 1,
            ),
        )
        mod.start()
        waitFor { db.blocks.count() == 1 }
        val opensAfter = opens
        bus.emit(Event.SyncIdle, SyncIdlePayload(1))
        delay(20)
        bus.emit(Event.PeersUpdated, PeersUpdatedPayload(2))
        delay(50)
        assertEquals(opensAfter, opens)
        mod.stop()
        db.close()
    }

    @Test
    fun stop_cancels_an_in_flight_block_request_before_joining() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val block = decodeBlock(hexToBytes(GENESIS_BLOCK_HEX))
        val internalHex = internalHashHex(block)
        val requestStarted = CompletableDeferred<Unit>()
        db.matchedBlocks.insert(MatchedBlock(0, internalHex))
        seedPeer(db, "1.1.1.1")

        val mod = createBlocksDownloadModule(
            ModuleContext(bus, db),
            BlocksDownloadOptions(
                net = stubPlatformNet(),
                openSession = makeOpenSession(
                    mapOf(internalHex to block),
                    beforeGetBlock = {
                        requestStarted.complete(Unit)
                        delay(750)
                    },
                ),
                concurrency = 1,
            ),
        )
        mod.start()
        requestStarted.await()

        val startedAt = nowMillis()
        mod.stop()
        val stopElapsedMs = nowMillis() - startedAt

        assertTrue(stopElapsedMs < 300, "stop took ${stopElapsedMs}ms")
        db.close()
    }
}
