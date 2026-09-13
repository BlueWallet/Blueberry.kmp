package io.bluewallet.blueberry.filters.modules

import io.bluewallet.bip157.NODE_COMPACT_FILTERS
import io.bluewallet.bip157.bytesToHex
import io.bluewallet.bip157.equalBytes
import io.bluewallet.bip157.filterHash
import io.bluewallet.bip157.filterHeader
import io.bluewallet.bip157.hexToBytes
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.HeadersProgressPayload
import io.bluewallet.blueberry.bus.PeersUpdatedPayload
import io.bluewallet.blueberry.bus.SyncIdlePayload
import io.bluewallet.blueberry.bus.createMessageBus
import io.bluewallet.blueberry.filters.waitFor
import io.bluewallet.blueberry.peers.modules.ModuleContext
import io.bluewallet.blueberry.peers.net.CFHeadersResult
import io.bluewallet.blueberry.peers.net.CFilterItem
import io.bluewallet.blueberry.peers.net.DnsResolver
import io.bluewallet.blueberry.peers.net.FilterBatchResult
import io.bluewallet.blueberry.peers.net.FilterSessionApi
import io.bluewallet.blueberry.peers.net.FilterSyncOptions
import io.bluewallet.blueberry.peers.net.PlatformNet
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.FilterHeaderRecord
import io.bluewallet.blueberry.storage.FilterRecord
import io.bluewallet.blueberry.storage.FiltersRepository
import io.bluewallet.blueberry.storage.HeaderWrite
import io.bluewallet.blueberry.storage.PeerWrite
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.markWalletBirthdayPending
import io.bluewallet.blueberry.wallet.maybeFreezeWalletBirthday
import io.bluewallet.headers.BlockHeader
import io.bluewallet.headers.encodeBlockHeader
import io.bluewallet.headers.headerHashInternal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val CF = NODE_COMPACT_FILTERS.toULong()

private fun stubPlatformNet(): PlatformNet =
    PlatformNet(
        connect = { _, _ -> error("stub PlatformNet.connect unused") },
        dns =
            object : DnsResolver {
                override suspend fun resolve4(host: String) = emptyList<String>()

                override suspend fun resolve6(host: String) = emptyList<String>()
            },
    )

private data class FilterFixture(
    val from: Int,
    val to: Int,
    val headers: List<HeaderWrite>,
    val bootstrapPrev: ByteArray,
    val filterBytesByHeight: Map<Int, ByteArray>,
    val filterHashesByHeight: Map<Int, ByteArray>,
    val filterHeaderByHeight: Map<Int, ByteArray>,
)

private fun fakeRecord(
    height: Int,
    previousHash: ByteArray,
    marker: Int = height,
): HeaderWrite {
    val merkleRoot = ByteArray(32)
    merkleRoot[0] = (marker and 0xff).toByte()
    merkleRoot[1] = ((marker shr 8) and 0xff).toByte()
    merkleRoot[2] = ((marker shr 16) and 0xff).toByte()
    merkleRoot[3] = ((marker shr 24) and 0xff).toByte()
    val header =
        BlockHeader(
            version = 1,
            previousBlockHash = previousHash.copyOf(),
            merkleRoot = merkleRoot,
            timestamp = 1_234_567L + height,
            bits = 0x207fffffL,
            nonce = height.toLong(),
        )
    return HeaderWrite(height, bytesToHex(headerHashInternal(header)), encodeBlockHeader(header))
}

private fun buildFilterChain(
    heights: List<Int>,
    bootstrapPrev: ByteArray,
): Triple<Map<Int, ByteArray>, Map<Int, ByteArray>, Map<Int, ByteArray>> {
    val bytes = mutableMapOf<Int, ByteArray>()
    val hashes = mutableMapOf<Int, ByteArray>()
    val headers = mutableMapOf<Int, ByteArray>()
    var prev = bootstrapPrev
    for (height in heights) {
        val fb = byteArrayOf((height and 0xff).toByte(), ((height shr 8) and 0xff).toByte(), 0xab.toByte())
        val fh = filterHash(fb)
        val header = filterHeader(fh, prev)
        bytes[height] = fb
        hashes[height] = fh
        headers[height] = header
        prev = header
    }
    return Triple(bytes, hashes, headers)
}

private fun buildGenesisFixture(): FilterFixture {
    val headers = mutableListOf<HeaderWrite>()
    var prevHash = ByteArray(32)
    for (h in 0..1000) {
        val rec = fakeRecord(h, prevHash)
        headers.add(rec)
        prevHash = hexToBytes(rec.hashInternalHex)
    }
    val bootstrapPrev = ByteArray(32)
    val (bytes, hashes, fheaders) = buildFilterChain(headers.map { it.height }, bootstrapPrev)
    return FilterFixture(0, 1000, headers, bootstrapPrev, bytes, hashes, fheaders)
}

private fun buildFilterFixture(): FilterFixture {
    val h998 = fakeRecord(998, ByteArray(32), 98)
    val h999 = fakeRecord(999, hexToBytes(h998.hashInternalHex), 99)
    val h1000 = fakeRecord(1000, hexToBytes(h999.hashInternalHex), 100)
    val headers = listOf(h998, h999, h1000)
    val bootstrapPrev = ByteArray(32) { 0x11 }
    val (bytes, hashes, fheaders) = buildFilterChain(listOf(998, 999, 1000), bootstrapPrev)
    return FilterFixture(998, 1000, headers, bootstrapPrev, bytes, hashes, fheaders)
}

private fun seedPeer(
    db: Database,
    host: String = "1.1.1.1",
    alive: Boolean = true,
) {
    db.peers.upsert(PeerWrite(host, 8333, CF, alive, false, null))
}

private open class ScriptedSession(
    private val fixture: FilterFixture,
    private val badHeight: Int? = null,
    onOpen: (() -> Unit)? = null,
    private val holdCfilt: CompletableDeferred<Unit>? = null,
    private val holdCfHeaders: CompletableDeferred<Unit>? = null,
    private val onClose: (() -> Unit)? = null,
) : FilterSessionApi {
    init {
        onOpen?.invoke()
    }

    override val services = CF

    override suspend fun getCFCheckpt(stopHash: ByteArray): List<ByteArray> {
        val tip = fixture.headers.last()
        val count = tip.height / 1000
        return (1..count).map { i ->
            fixture.filterHeaderByHeight[i * 1000]?.copyOf() ?: ByteArray(32)
        }
    }

    override suspend fun getCFHeaders(
        startHeight: Int,
        stopHash: ByteArray,
    ): CFHeadersResult {
        holdCfHeaders?.await()
        val stopHeight = fixture.headers.first { it.hashInternalHex == bytesToHex(stopHash) }.height
        val filterHashes = (startHeight..stopHeight).map { fixture.filterHashesByHeight.getValue(it).copyOf() }
        val previousFilterHeader =
            if (startHeight == fixture.from) {
                fixture.bootstrapPrev.copyOf()
            } else {
                fixture.filterHeaderByHeight.getValue(startHeight - 1).copyOf()
            }
        return CFHeadersResult(0, stopHash.copyOf(), previousFilterHeader, filterHashes)
    }

    override suspend fun getCFilters(
        startHeight: Int,
        stopHash: ByteArray,
        expectCount: Int,
        onFilter: (suspend (CFilterItem) -> Unit)?,
    ): List<CFilterItem> {
        holdCfilt?.await()
        val stopHeight = fixture.headers.first { it.hashInternalHex == bytesToHex(stopHash) }.height
        val out = mutableListOf<CFilterItem>()
        for (h in startHeight..stopHeight) {
            val row = fixture.headers.first { it.height == h }
            var fb = fixture.filterBytesByHeight.getValue(h)
            if (badHeight == h) fb = byteArrayOf(0xff.toByte())
            val item = CFilterItem(hexToBytes(row.hashInternalHex), fb)
            out.add(item)
            if (onFilter != null) onFilter(item)
        }
        check(out.size == expectCount)
        return out
    }

    override suspend fun close() {
        onClose?.invoke()
    }
}

private fun makeOpenSession(fixture: FilterFixture): suspend (String, Int, FilterSyncOptions) -> FilterBatchResult<FilterSessionApi> = { _, _, _ -> FilterBatchResult.Ok(ScriptedSession(fixture)) }

class FiltersDownloadTest {
    @Test
    fun busy_kick_does_not_double_start_dirty_bit_re_runs_after() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            db.headers.append(fixture.headers)
            seedPeer(db)
            val cfiltHeld = CompletableDeferred<Unit>()
            var sessionOpened = false
            var downloadRuns = 0
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        filterBatchSize = 10,
                        idleDelayMs = 50,
                        onDownloadRun = { downloadRuns++ },
                        openSession = { _, _, _ ->
                            FilterBatchResult.Ok(
                                ScriptedSession(fixture, onOpen = { sessionOpened = true }, holdCfilt = cfiltHeld),
                            )
                        },
                    ),
                )
            mod.start()
            waitFor { sessionOpened }
            bus.emit(Event.HeadersProgress, HeadersProgressPayload(1, 1, 1, 1))
            bus.emit(Event.HeadersProgress, HeadersProgressPayload(2, 2, 2, 2))
            assertEquals(1, downloadRuns)
            cfiltHeld.complete(Unit)
            waitFor { db.filters.countInRange(fixture.from, fixture.to) == 3 }
            waitFor { downloadRuns >= 2 }
            mod.stop()
            db.close()
        }

    @Test
    fun idle_kick_resumes_when_tip_grows() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            db.headers.append(fixture.headers.take(2))
            seedPeer(db)
            var downloadRuns = 0
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        filterBatchSize = 10,
                        idleDelayMs = 50,
                        onDownloadRun = { downloadRuns++ },
                        openSession = makeOpenSession(fixture),
                    ),
                )
            mod.start()
            delay(100)
            assertEquals(0, db.filters.countInRange(fixture.from, fixture.to))
            db.headers.append(listOf(fixture.headers[2]))
            bus.emit(Event.HeadersProgress, HeadersProgressPayload(3, 3, 3, 3))
            waitFor { db.filters.countInRange(fixture.from, fixture.to) == 3 }
            assertTrue(downloadRuns >= 1)
            mod.stop()
            db.close()
        }

    @Test
    fun emits_filters_progress_with_downloaded_and_total() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            db.headers.append(fixture.headers)
            seedPeer(db)
            val progress = mutableListOf<Pair<Int, Int>>()
            bus.on(Event.FiltersProgress) { progress.add(it.downloaded to it.total) }
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        filterBatchSize = 10,
                        openSession = makeOpenSession(fixture),
                    ),
                )
            mod.start()
            waitFor { db.filters.countInRange(fixture.from, fixture.to) == 3 }
            assertTrue(progress.any { it.first == 3 && it.second == 3 })
            mod.stop()
            db.close()
        }

    @Test
    fun rejects_bad_filter_bytes_and_tries_another_peer() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            db.headers.append(fixture.headers)
            seedPeer(db, "1.1.1.1")
            seedPeer(db, "2.2.2.2")
            val openedHosts = mutableListOf<String>()
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        filterBatchSize = 10,
                        coolMs = 60_000,
                        openSession = { host, _, _ ->
                            openedHosts.add(host)
                            FilterBatchResult.Ok(ScriptedSession(fixture, badHeight = if (host == "1.1.1.1") 999 else null))
                        },
                    ),
                )
            mod.start()
            waitFor { db.filters.countInRange(fixture.from, fixture.to) == 3 }
            assertTrue("1.1.1.1" in openedHosts)
            assertTrue("2.2.2.2" in openedHosts)
            mod.stop()
            db.close()
        }

    @Test
    fun rejects_duplicate_cfilter_messages_from_a_peer() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            db.headers.append(fixture.headers)
            seedPeer(db)
            val lines = mutableListOf<String>()
            var opens = 0
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        filterBatchSize = 10,
                        persistBatchSize = 1,
                        coolMs = 1,
                        log = { lines.add(it) },
                        openSession = { _, _, _ ->
                            opens++
                            val base = ScriptedSession(fixture)
                            if (opens > 1) {
                                FilterBatchResult.Ok(base)
                            } else {
                                FilterBatchResult.Ok(
                                    object : FilterSessionApi by base {
                                        override suspend fun getCFilters(
                                            startHeight: Int,
                                            stopHash: ByteArray,
                                            expectCount: Int,
                                            onFilter: (suspend (CFilterItem) -> Unit)?,
                                        ): List<CFilterItem> {
                                            val filters = base.getCFilters(startHeight, stopHash, expectCount)
                                            val duplicate = filters.first()
                                            repeat(expectCount) { if (onFilter != null) onFilter(duplicate) }
                                            return List(expectCount) { duplicate }
                                        }
                                    },
                                )
                            }
                        },
                    ),
                )
            mod.start()
            waitFor { db.filters.countInRange(fixture.from, fixture.to) == 3 }
            assertTrue(lines.any { it.contains("filter batch failure") && it.contains("duplicate cfilter height") })
            mod.stop()
            db.close()
        }

    @Test
    fun persists_bootstrap_prev_at_from_minus_1_when_from_gt_0() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            db.headers.append(fixture.headers)
            seedPeer(db)
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        filterBatchSize = 10,
                        openSession = makeOpenSession(fixture),
                    ),
                )
            mod.start()
            waitFor { db.filters.has(fixture.to) }
            assertTrue(equalBytes(db.filterHeaders.get(fixture.from - 1)!!.header, fixture.bootstrapPrev))
            mod.stop()
            db.close()
        }

    @Test
    fun discards_in_flight_cfheaders_after_reorg_replaces_stop_hash() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            val forkRecord = fakeRecord(1000, hexToBytes(fixture.headers[1].hashInternalHex), 120)
            db.headers.append(fixture.headers)
            maybeFreezeWalletBirthday(db, fixture.from)
            seedPeer(db)
            val held = CompletableDeferred<Unit>()
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        idleDelayMs = 20,
                        openSession = { _, _, _ ->
                            FilterBatchResult.Ok(ScriptedSession(fixture, holdCfHeaders = held))
                        },
                    ),
                )
            mod.start()
            delay(60)
            db.transaction {
                db.rewindAfter(999)
                db.headers.replaceAfter(999, listOf(forkRecord))
            }
            assertNull(db.filterHeaders.tip())
            held.complete(Unit)
            delay(120)
            assertNull(db.filterHeaders.get(1000))
            assertNull(db.filterHeaders.tip())
            mod.stop()
            db.close()
        }

    @Test
    fun discards_in_flight_cfilters_for_heights_replaced_by_a_reorg() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            val forkRecord = fakeRecord(1000, hexToBytes(fixture.headers[1].hashInternalHex), 121)
            db.headers.append(fixture.headers)
            seedPeer(db)
            val held = CompletableDeferred<Unit>()
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        idleDelayMs = 20,
                        openSession = { _, _, _ ->
                            FilterBatchResult.Ok(ScriptedSession(fixture, holdCfilt = held))
                        },
                    ),
                )
            mod.start()
            waitFor { db.filterHeaders.get(1000) != null }
            delay(40)
            db.transaction {
                db.rewindAfter(999)
                db.headers.replaceAfter(999, listOf(forkRecord))
            }
            assertNull(db.filters.maxHeight())
            held.complete(Unit)
            delay(120)
            assertNotNull(db.filters.get(998))
            assertNotNull(db.filters.get(999))
            assertNull(db.filters.get(1000))
            mod.stop()
            db.close()
        }

    @Test
    fun rejects_bootstrap_first_batch_without_in_range_checkpoint() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            db.headers.append(fixture.headers)
            seedPeer(db)
            val lines = mutableListOf<String>()
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        filterBatchSize = 1,
                        headerBatchSize = 1,
                        idleDelayMs = 20,
                        log = { lines.add(it) },
                        openSession = { _, _, _ ->
                            val base = ScriptedSession(fixture)
                            FilterBatchResult.Ok(
                                object : FilterSessionApi by base {
                                    override suspend fun getCFHeaders(
                                        startHeight: Int,
                                        stopHash: ByteArray,
                                    ): CFHeadersResult {
                                        val inner = ScriptedSession(fixture).getCFHeaders(startHeight, stopHash)
                                        return inner.copy(previousFilterHeader = ByteArray(32) { 0xee.toByte() })
                                    }
                                },
                            )
                        },
                    ),
                )
            mod.start()
            delay(200)
            assertNull(db.filterHeaders.get(fixture.from))
            assertEquals(0, db.filters.count())
            mod.stop()
            assertTrue(
                lines.any {
                    Regex(
                        "header batch failure range=998-1000 peer=1\\.1\\.1\\.1:8333 elapsedMs=\\d+ error=.*cfheaders verification failed",
                    ).containsMatchIn(it)
                },
            )
            db.close()
        }

    @Test
    fun retries_after_cfilter_eof_once_cool_elapses() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            db.headers.append(fixture.headers)
            seedPeer(db)
            var calls = 0
            val lines = mutableListOf<String>()
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        filterBatchSize = 10,
                        idleDelayMs = 20,
                        coolMs = 1,
                        log = { lines.add(it) },
                        openSession = { _, _, _ ->
                            calls++
                            if (calls == 1) {
                                val base = ScriptedSession(fixture)
                                FilterBatchResult.Ok(
                                    object : FilterSessionApi by base {
                                        override suspend fun getCFilters(
                                            startHeight: Int,
                                            stopHash: ByteArray,
                                            expectCount: Int,
                                            onFilter: (suspend (CFilterItem) -> Unit)?,
                                        ): List<CFilterItem> = error("unexpected EOF")
                                    },
                                )
                            } else {
                                FilterBatchResult.Ok(ScriptedSession(fixture))
                            }
                        },
                    ),
                )
            mod.start()
            waitFor { db.filters.countInRange(fixture.from, fixture.to) == 3 }
            assertTrue(calls >= 2)
            mod.stop()
            assertTrue(
                lines.any {
                    Regex(
                        "filter batch failure range=998-1000 peer=1\\.1\\.1\\.1:8333 received=0 saved=0 bytes=0 elapsedMs=\\d+ error=.*unexpected EOF",
                    ).containsMatchIn(it)
                },
            )
            assertTrue(lines.any { Regex("filter batch retry range=998-1000 failure=1/9 action=requeue").containsMatchIn(it) })
            db.close()
        }

    @Test
    fun persists_verified_filters_before_an_incomplete_batch_fails() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            db.headers.append(fixture.headers)
            seedPeer(db)
            var opens = 0
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        filterBatchSize = 10,
                        persistBatchSize = 3,
                        coolMs = 60_000,
                        openSession = { _, _, _ ->
                            opens++
                            if (opens > 1) {
                                FilterBatchResult.Err("still cooling")
                            } else {
                                val base = ScriptedSession(fixture)
                                FilterBatchResult.Ok(
                                    object : FilterSessionApi by base {
                                        override suspend fun getCFilters(
                                            startHeight: Int,
                                            stopHash: ByteArray,
                                            expectCount: Int,
                                            onFilter: (suspend (CFilterItem) -> Unit)?,
                                        ): List<CFilterItem> {
                                            val filters = base.getCFilters(startHeight, stopHash, expectCount)
                                            for (item in filters.take(2)) if (onFilter != null) onFilter(item)
                                            error("unexpected EOF")
                                        }
                                    },
                                )
                            }
                        },
                    ),
                )
            mod.start()
            waitFor { db.filters.countInRange(fixture.from, fixture.to) == 2 }
            assertTrue(db.filters.has(998))
            assertTrue(db.filters.has(999))
            assertFalse(db.filters.has(1000))
            mod.stop()
            db.close()
        }

    @Test
    fun logs_network_and_persistence_errors_when_final_partial_flush_fails() =
        runBlocking {
            val bus = createMessageBus()
            val inner = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            inner.headers.append(fixture.headers)
            seedPeer(inner)
            val realAppend = inner.filters::append
            var failNext = true
            val db =
                object : Database by inner {
                    override val filters =
                        object : FiltersRepository by inner.filters {
                            override fun append(rows: List<FilterRecord>) {
                                if (failNext) {
                                    failNext = false
                                    error("disk full")
                                }
                                realAppend(rows)
                            }
                        }
                }
            val sawCombinedError = CompletableDeferred<Unit>()
            var opens = 0
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        filterBatchSize = 10,
                        persistBatchSize = 3,
                        coolMs = 1,
                        log = {
                            if (it.contains("error=unexpected EOF") && it.contains("persistenceError=disk full")) {
                                sawCombinedError.complete(Unit)
                            }
                        },
                        openSession = { _, _, _ ->
                            opens++
                            val base = ScriptedSession(fixture)
                            if (opens > 1) {
                                FilterBatchResult.Ok(base)
                            } else {
                                FilterBatchResult.Ok(
                                    object : FilterSessionApi by base {
                                        override suspend fun getCFilters(
                                            startHeight: Int,
                                            stopHash: ByteArray,
                                            expectCount: Int,
                                            onFilter: (suspend (CFilterItem) -> Unit)?,
                                        ): List<CFilterItem> {
                                            val filters = base.getCFilters(startHeight, stopHash, expectCount)
                                            for (item in filters.take(2)) if (onFilter != null) onFilter(item)
                                            error("unexpected EOF")
                                        }
                                    },
                                )
                            }
                        },
                    ),
                )
            mod.start()
            sawCombinedError.await()
            mod.stop()
            inner.close()
        }

    @Test
    fun retries_only_the_unpersisted_tail_of_a_partial_batch() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            db.headers.append(fixture.headers)
            seedPeer(db)
            val requests = mutableListOf<Pair<Int, Int>>()
            var opens = 0
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        filterBatchSize = 10,
                        persistBatchSize = 2,
                        coolMs = 1,
                        openSession = { _, _, _ ->
                            opens++
                            val base = ScriptedSession(fixture)
                            if (opens == 1) {
                                FilterBatchResult.Ok(
                                    object : FilterSessionApi by base {
                                        override suspend fun getCFilters(
                                            startHeight: Int,
                                            stopHash: ByteArray,
                                            expectCount: Int,
                                            onFilter: (suspend (CFilterItem) -> Unit)?,
                                        ): List<CFilterItem> {
                                            requests.add(startHeight to expectCount)
                                            val filters = base.getCFilters(startHeight, stopHash, expectCount)
                                            for (item in filters.take(2)) if (onFilter != null) onFilter(item)
                                            error("unexpected EOF")
                                        }
                                    },
                                )
                            } else {
                                FilterBatchResult.Ok(
                                    object : FilterSessionApi by base {
                                        override suspend fun getCFilters(
                                            startHeight: Int,
                                            stopHash: ByteArray,
                                            expectCount: Int,
                                            onFilter: (suspend (CFilterItem) -> Unit)?,
                                        ): List<CFilterItem> {
                                            requests.add(startHeight to expectCount)
                                            return base.getCFilters(startHeight, stopHash, expectCount, onFilter)
                                        }
                                    },
                                )
                            }
                        },
                    ),
                )
            mod.start()
            waitFor { db.filters.countInRange(fixture.from, fixture.to) == 3 }
            assertEquals(listOf(998 to 3, 1000 to 1), requests.take(2))
            mod.stop()
            db.close()
        }

    @Test
    fun requests_first_bootstrap_batch_through_next_checkpoint() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            db.headers.append(fixture.headers)
            seedPeer(db)
            val requestedStops = mutableListOf<Int>()
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        filterBatchSize = 3,
                        headerBatchSize = 3,
                        openSession = { _, _, _ ->
                            val base = ScriptedSession(fixture)
                            FilterBatchResult.Ok(
                                object : FilterSessionApi by base {
                                    override suspend fun getCFHeaders(
                                        startHeight: Int,
                                        stopHash: ByteArray,
                                    ): CFHeadersResult {
                                        val stopHeight = fixture.headers.first { it.hashInternalHex == bytesToHex(stopHash) }.height
                                        requestedStops.add(stopHeight)
                                        return ScriptedSession(fixture).getCFHeaders(startHeight, stopHash)
                                    }
                                },
                            )
                        },
                    ),
                )
            mod.start()
            waitFor { db.filters.has(fixture.to) }
            assertEquals(1000, requestedStops.first())
            mod.stop()
            db.close()
        }

    @Test
    fun two_phase_cfheaders_complete_before_cfilters() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            db.headers.append(fixture.headers)
            seedPeer(db)
            val order = mutableListOf<String>()
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        filterBatchSize = 10,
                        openSession = { _, _, _ ->
                            val base = ScriptedSession(fixture)
                            FilterBatchResult.Ok(
                                object : FilterSessionApi by base {
                                    override suspend fun getCFHeaders(
                                        startHeight: Int,
                                        stopHash: ByteArray,
                                    ): CFHeadersResult {
                                        order.add("cfheaders")
                                        return ScriptedSession(fixture).getCFHeaders(startHeight, stopHash)
                                    }

                                    override suspend fun getCFilters(
                                        startHeight: Int,
                                        stopHash: ByteArray,
                                        expectCount: Int,
                                        onFilter: (suspend (CFilterItem) -> Unit)?,
                                    ): List<CFilterItem> {
                                        order.add("cfilters")
                                        return ScriptedSession(fixture).getCFilters(startHeight, stopHash, expectCount)
                                    }
                                },
                            )
                        },
                    ),
                )
            mod.start()
            waitFor { db.filters.countInRange(fixture.from, fixture.to) == 3 }
            assertTrue(order.indexOf("cfheaders") >= 0)
            assertTrue(order.indexOf("cfilters") > order.indexOf("cfheaders"))
            mod.stop()
            db.close()
        }

    @Test
    fun internal_filter_holes_do_not_report_downloaded_gt_total() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            db.headers.append(fixture.headers)
            seedPeer(db)
            db.filterHeaders.append(
                listOf(FilterHeaderRecord(fixture.from - 1, fixture.bootstrapPrev.copyOf())) +
                    fixture.filterHeaderByHeight.map { FilterHeaderRecord(it.key, it.value.copyOf()) },
            )
            db.filters.append(
                listOf(
                    FilterRecord(
                        fixture.from,
                        fixture.headers[0].hashInternalHex,
                        fixture.filterBytesByHeight.getValue(fixture.from).copyOf(),
                    ),
                    FilterRecord(fixture.to, fixture.headers[2].hashInternalHex, fixture.filterBytesByHeight.getValue(fixture.to).copyOf()),
                ),
            )
            val progress = mutableListOf<Pair<Int, Int>>()
            bus.on(Event.FiltersProgress) { progress.add(it.downloaded to it.total) }
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        filterBatchSize = 10,
                        openSession = { _, _, _ ->
                            val base = ScriptedSession(fixture)
                            FilterBatchResult.Ok(
                                object : FilterSessionApi by base {
                                    override suspend fun getCFHeaders(
                                        startHeight: Int,
                                        stopHash: ByteArray,
                                    ): CFHeadersResult = error("headers already present")
                                },
                            )
                        },
                    ),
                )
            mod.start()
            waitFor { db.filters.countInRange(fixture.from, fixture.to) == 3 }
            mod.stop()
            assertTrue(progress.all { it.first <= it.second })
            assertTrue(progress.any { it.first == 2 && it.second == 3 })
            assertEquals(3 to 3, progress.last())
            db.close()
        }

    @Test
    fun backfills_filters_when_filter_headers_already_exist() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            db.headers.append(fixture.headers)
            seedPeer(db)
            db.filterHeaders.append(
                listOf(FilterHeaderRecord(fixture.from - 1, fixture.bootstrapPrev.copyOf())) +
                    fixture.filterHeaderByHeight.map { FilterHeaderRecord(it.key, it.value.copyOf()) },
            )
            var cfHeadersCalls = 0
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        filterBatchSize = 10,
                        openSession = { _, _, _ ->
                            val base = ScriptedSession(fixture)
                            FilterBatchResult.Ok(
                                object : FilterSessionApi by base {
                                    override suspend fun getCFHeaders(
                                        startHeight: Int,
                                        stopHash: ByteArray,
                                    ): CFHeadersResult {
                                        cfHeadersCalls++
                                        error("headers already present")
                                    }
                                },
                            )
                        },
                    ),
                )
            mod.start()
            waitFor { db.filters.countInRange(fixture.from, fixture.to) == 3 }
            assertEquals(0, cfHeadersCalls)
            mod.stop()
            db.close()
        }

    @Test
    fun falls_back_to_stored_compact_filter_peers_when_none_are_alive() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            db.headers.append(fixture.headers)
            seedPeer(db, "9.9.9.9", alive = false)
            val openedHosts = mutableListOf<String>()
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        filterBatchSize = 10,
                        openSession = { host, _, _ ->
                            openedHosts.add(host)
                            FilterBatchResult.Ok(ScriptedSession(fixture))
                        },
                    ),
                )
            mod.start()
            waitFor { db.filters.countInRange(fixture.from, fixture.to) == 3 }
            assertEquals("9.9.9.9", openedHosts.first())
            assertTrue(db.peers.listAlive().any { it.host == "9.9.9.9" })
            mod.stop()
            db.close()
        }

    @Test
    fun retries_session_dead_peers_and_marks_them_alive_on_success() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            db.headers.append(fixture.headers)
            seedPeer(db, "8.8.8.8")
            var opens = 0
            val lines = mutableListOf<String>()
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        filterBatchSize = 10,
                        idleDelayMs = 20,
                        coolMs = 1,
                        log = { lines.add(it) },
                        openSession = { _, _, _ ->
                            opens++
                            if (opens == 1) {
                                FilterBatchResult.Err("boom")
                            } else {
                                FilterBatchResult.Ok(ScriptedSession(fixture))
                            }
                        },
                    ),
                )
            mod.start()
            waitFor { db.filters.countInRange(fixture.from, fixture.to) == 3 }
            assertTrue(opens >= 2)
            assertTrue(db.peers.listAlive().any { it.host == "8.8.8.8" })
            mod.stop()
            assertTrue(
                lines.any {
                    Regex("session open failure peer=8\\.8\\.8\\.8:8333 elapsedMs=\\d+ cooldownMs=1 error=boom").containsMatchIn(it)
                },
            )
            db.close()
        }

    @Test
    fun pending_wallet_birthday_downloads_no_filters() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            db.headers.append(fixture.headers)
            seedPeer(db)
            markWalletBirthdayPending(db)
            var sessionOpened = false
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        filterBatchSize = 10,
                        idleDelayMs = 30,
                        openSession = { _, _, _ ->
                            sessionOpened = true
                            FilterBatchResult.Ok(ScriptedSession(fixture))
                        },
                    ),
                )
            mod.start()
            delay(120)
            assertFalse(sessionOpened)
            assertEquals(0, db.filters.count())
            mod.stop()
            db.close()
        }

    @Test
    fun frozen_birthday_skips_cfilters_below_that_height() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            db.headers.append(fixture.headers)
            seedPeer(db)
            markWalletBirthdayPending(db)
            assertTrue(maybeFreezeWalletBirthday(db, 1000))
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        filterBatchSize = 10,
                        idleDelayMs = 50,
                        openSession = makeOpenSession(fixture),
                    ),
                )
            mod.start()
            waitFor { db.filters.has(1000) }
            assertFalse(db.filters.has(998))
            assertFalse(db.filters.has(999))
            assertTrue(db.filters.has(1000))
            mod.stop()
            db.close()
        }

    @Test
    fun reorg_at_birthday_checkpoint_reuses_bootstrap_prev_instead_of_re_inserting_it() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            val forkRecord = fakeRecord(1000, hexToBytes(fixture.headers[1].hashInternalHex), 130)
            val forkFilterBytes = byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xab.toByte())
            val forkFilterHash = filterHash(forkFilterBytes)
            val prev999 = fixture.filterHeaderByHeight.getValue(999)
            val forkFilterHeader = filterHeader(forkFilterHash, prev999)
            val forkFixture =
                fixture.copy(
                    headers = listOf(fixture.headers[0], fixture.headers[1], forkRecord),
                    filterBytesByHeight = fixture.filterBytesByHeight + (1000 to forkFilterBytes),
                    filterHashesByHeight = fixture.filterHashesByHeight + (1000 to forkFilterHash),
                    filterHeaderByHeight = fixture.filterHeaderByHeight + (1000 to forkFilterHeader),
                )
            db.headers.append(fixture.headers)
            seedPeer(db)
            markWalletBirthdayPending(db)
            assertTrue(maybeFreezeWalletBirthday(db, 1000))
            var useFork = false
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        idleDelayMs = 20,
                        coolMs = 1,
                        openSession = { _, _, _ ->
                            FilterBatchResult.Ok(ScriptedSession(if (useFork) forkFixture else fixture))
                        },
                    ),
                )
            mod.start()
            waitFor { db.filterHeaders.get(1000) != null }
            assertTrue(equalBytes(db.filterHeaders.get(999)!!.header, prev999))
            db.transaction {
                db.rewindAfter(999)
                db.headers.replaceAfter(999, listOf(forkRecord))
            }
            useFork = true
            bus.emit(Event.HeadersProgress, HeadersProgressPayload(1, 3, 3, 1000))
            waitFor {
                val row = db.filterHeaders.get(1000)
                row != null && equalBytes(row.header, forkFilterHeader)
            }
            assertTrue(equalBytes(db.filterHeaders.get(999)!!.header, prev999))
            mod.stop()
            db.close()
        }

    @Test
    fun while_sync_idle_peers_updated_does_not_start_a_new_download_run() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            db.headers.append(fixture.headers)
            seedPeer(db)
            var downloadRuns = 0
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        filterBatchSize = 10,
                        idleDelayMs = 50,
                        onDownloadRun = { downloadRuns++ },
                        openSession = makeOpenSession(fixture),
                    ),
                )
            mod.start()
            waitFor { db.filters.countInRange(fixture.from, fixture.to) == 3 }
            val runsAfterSync = downloadRuns
            bus.emit(Event.SyncIdle, SyncIdlePayload(1))
            delay(20)
            bus.emit(Event.PeersUpdated, PeersUpdatedPayload(2))
            delay(50)
            assertEquals(runsAfterSync, downloadRuns)
            mod.stop()
            db.close()
        }

    @Test
    fun logs_successful_filter_batches_with_peer_metrics() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            db.headers.append(fixture.headers)
            seedPeer(db)
            val lines = mutableListOf<String>()
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        filterBatchSize = 10,
                        openSession = makeOpenSession(fixture),
                        log = { lines.add(it) },
                    ),
                )
            mod.start()
            waitFor { db.filters.countInRange(fixture.from, fixture.to) == 3 }
            mod.stop()
            assertTrue(
                lines.any {
                    Regex(
                        "filter batch success range=998-1000 peer=1\\.1\\.1\\.1:8333 received=3 saved=3 bytes=9 elapsedMs=\\d+",
                    ).containsMatchIn(it)
                },
            )
            assertTrue(lines.any { Regex("filter queue range=998-1000 batches=1 missing=3").containsMatchIn(it) })
            assertTrue(lines.any { Regex("sync plan filterRange=998-1000 headerRange=998-1000 cached=0 peers=1").containsMatchIn(it) })
            assertTrue(lines.any { Regex("run complete .*remaining=0").containsMatchIn(it) })
            db.close()
        }

    @Test
    fun authenticates_genesis_filter_headers_against_checkpoint_1000_not_height_0() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildGenesisFixture()
            db.headers.append(fixture.headers)
            seedPeer(db)
            val lines = mutableListOf<String>()
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        filterBatchSize = 2000,
                        headerBatchSize = 1,
                        openSession = makeOpenSession(fixture),
                        log = { lines.add(it) },
                    ),
                )
            mod.start()
            waitFor(10_000) { db.filterHeaders.get(1000) != null }
            assertTrue(equalBytes(db.filterHeaders.get(1000)!!.header, fixture.filterHeaderByHeight.getValue(1000)))
            mod.stop()
            assertTrue(lines.any { Regex("header batch success range=0-1000").containsMatchIn(it) })
            db.close()
        }

    @Test
    fun fills_prefix_filter_holes_while_still_behind_the_header_tip() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            val h1001 = fakeRecord(1001, hexToBytes(fixture.headers[2].hashInternalHex))
            val filter1001Bytes = byteArrayOf(0x01, 0x04, 0xab.toByte())
            val filter1001Hash = filterHash(filter1001Bytes)
            val filter1001Header = filterHeader(filter1001Hash, fixture.filterHeaderByHeight.getValue(1000))
            db.headers.append(fixture.headers + h1001)
            seedPeer(db)
            db.filterHeaders.append(
                listOf(FilterHeaderRecord(fixture.from - 1, fixture.bootstrapPrev.copyOf())) +
                    fixture.filterHeaderByHeight.map { FilterHeaderRecord(it.key, it.value.copyOf()) } +
                    FilterHeaderRecord(1001, filter1001Header.copyOf()),
            )
            db.filters.append(
                listOf(
                    FilterRecord(
                        fixture.from,
                        fixture.headers[0].hashInternalHex,
                        fixture.filterBytesByHeight.getValue(fixture.from).copyOf(),
                    ),
                    FilterRecord(fixture.to, fixture.headers[2].hashInternalHex, fixture.filterBytesByHeight.getValue(fixture.to).copyOf()),
                ),
            )
            val extended =
                fixture.copy(
                    to = 1001,
                    headers = fixture.headers + h1001,
                    filterBytesByHeight = fixture.filterBytesByHeight + (1001 to filter1001Bytes),
                    filterHashesByHeight = fixture.filterHashesByHeight + (1001 to filter1001Hash),
                    filterHeaderByHeight = fixture.filterHeaderByHeight + (1001 to filter1001Header),
                )
            val lines = mutableListOf<String>()
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        filterBatchSize = 10,
                        openSession = makeOpenSession(extended),
                        log = { lines.add(it) },
                    ),
                )
            mod.start()
            waitFor { db.filters.has(999) && db.filters.has(1001) }
            mod.stop()
            assertTrue(lines.any { Regex("filter queue range=998-1001 batches=2 missing=2").containsMatchIn(it) })
            db.close()
        }

    @Test
    fun progress_totals_stay_on_the_birthday_filter_range_during_header_sync() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            db.headers.append(fixture.headers)
            seedPeer(db)
            markWalletBirthdayPending(db)
            assertTrue(maybeFreezeWalletBirthday(db, 999))
            val totals = mutableSetOf<Int>()
            bus.on(Event.FiltersProgress) { totals.add(it.total) }
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        filterBatchSize = 10,
                        headerBatchSize = 3,
                        openSession = makeOpenSession(fixture),
                    ),
                )
            mod.start()
            waitFor { db.filters.has(1000) }
            mod.stop()
            assertTrue(2 in totals)
            assertFalse(3 in totals)
            db.close()
        }

    @Test
    fun stop_waits_for_an_in_flight_download_run_to_finish() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val fixture = buildFilterFixture()
            db.headers.append(fixture.headers)
            seedPeer(db)
            val held = CompletableDeferred<Unit>()
            var inFlight = false
            val mod =
                createFiltersDownloadModule(
                    ModuleContext(bus, db),
                    FiltersDownloadOptions(
                        net = stubPlatformNet(),
                        concurrency = 1,
                        idleDelayMs = 20,
                        openSession = { _, _, _ ->
                            val base = ScriptedSession(fixture, onClose = { held.complete(Unit) })
                            FilterBatchResult.Ok(
                                object : FilterSessionApi by base {
                                    override suspend fun getCFilters(
                                        startHeight: Int,
                                        stopHash: ByteArray,
                                        expectCount: Int,
                                        onFilter: (suspend (CFilterItem) -> Unit)?,
                                    ): List<CFilterItem> {
                                        inFlight = true
                                        try {
                                            held.await()
                                            return base.getCFilters(startHeight, stopHash, expectCount, onFilter)
                                        } finally {
                                            inFlight = false
                                        }
                                    }

                                    override suspend fun close() {
                                        held.complete(Unit)
                                    }
                                },
                            )
                        },
                    ),
                )
            mod.start()
            waitFor { inFlight }
            mod.stop()
            assertFalse(inFlight)
            db.close()
        }
}
