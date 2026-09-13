package io.bluewallet.blueberry

import com.ionspin.kotlin.bignum.integer.BigInteger
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.HeadersProgressPayload
import io.bluewallet.blueberry.bus.createMessageBus
import io.bluewallet.blueberry.headers.checkpointDbRecord
import io.bluewallet.blueberry.headers.checkpointSeedRecord
import io.bluewallet.blueberry.headers.modules.ChainHeadersOptions
import io.bluewallet.blueberry.headers.modules.createChainHeadersModule
import io.bluewallet.blueberry.peers.modules.ModuleContext
import io.bluewallet.blueberry.peers.net.DnsResolver
import io.bluewallet.blueberry.peers.net.HeaderBatchResult
import io.bluewallet.blueberry.peers.net.PlatformNet
import io.bluewallet.blueberry.storage.HeaderWrite
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.headers.BlockHeader
import io.bluewallet.headers.encodeBlockHeader
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

private fun stubNet(): PlatformNet =
    PlatformNet(
        connect = { _, _ -> error("stub PlatformNet.connect unused") },
        dns =
            object : DnsResolver {
                override suspend fun resolve4(host: String) = emptyList<String>()

                override suspend fun resolve6(host: String) = emptyList<String>()
            },
    )

private fun dummyHeader(timestamp: Long = 1): ByteArray =
    encodeBlockHeader(
        BlockHeader(
            version = 1,
            previousBlockHash = ByteArray(32),
            merkleRoot = ByteArray(32),
            timestamp = timestamp,
            bits = 0x1d00ffff,
            nonce = 0,
        ),
    )

private fun addHeader(
    db: io.bluewallet.blueberry.storage.Database,
    height: Int,
    nibble: String,
    timestamp: Long = 1,
) {
    db.headers.append(
        listOf(
            HeaderWrite(
                height = height,
                hashInternalHex = nibble.repeat(32),
                header = dummyHeader(timestamp),
                cumulativeWork = BigInteger.fromInt(height),
            ),
        ),
    )
}

class HeadersHydrateTest {
    @Test
    fun empty_db_hydrate_leaves_zeros() {
        val db = createSqliteDatabase(":memory:")
        val store = createHeadersProgressStore()
        hydrateHeaders(db, store, 500, 1)
        assertEquals(0, store.get().downloaded)
        assertEquals(0, store.get().total)
        assertEquals(0, store.get().height)
        db.close()
    }

    @Test
    fun headers_total_gt_zero_updates_total_downloaded_height_stay_from_db() {
        val db = createSqliteDatabase(":memory:")
        addHeader(db, 10, "aa")
        addHeader(db, 11, "bb")
        val store = createHeadersProgressStore()
        hydrateHeaders(db, store, null, 1)
        assertEquals(1, store.get().downloaded)
        assertEquals(1, store.get().total)
        assertEquals(11, store.get().height)

        hydrateHeaders(db, store, 0, 2)
        assertEquals(1, store.get().downloaded)
        assertEquals(1, store.get().total)
        assertEquals(11, store.get().height)

        hydrateHeaders(db, store, 500, 3)
        assertEquals(1, store.get().downloaded)
        assertEquals(500, store.get().total)
        assertEquals(11, store.get().height)

        hydrateHeaders(db, store, 0, 4)
        assertEquals(1, store.get().downloaded)
        assertEquals(500, store.get().total)
        assertEquals(11, store.get().height)
        db.close()
    }

    @Test
    fun hydrates_from_db_payload_total_gt_zero_only_zeros_do_not_clobber() {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        addHeader(db, 10, "aa")
        addHeader(db, 11, "bb")
        val store = createHeadersProgressStore()
        val off = bindHeaderProgressEvents(bus, db, store)
        hydrateHeaders(db, store)
        assertEquals(1, store.get().downloaded)
        assertEquals(1, store.get().total)
        assertEquals(11, store.get().height)

        bus.emit(Event.HeadersProgress, HeadersProgressPayload(1000, 0, 0, 0))
        assertEquals(1, store.get().downloaded)
        assertEquals(1, store.get().total)
        assertEquals(11, store.get().height)

        bus.emit(Event.HeadersProgress, HeadersProgressPayload(2000, 999, 500, 1))
        assertEquals(1, store.get().downloaded)
        assertEquals(500, store.get().total)
        assertEquals(11, store.get().height)
        assertEquals(2000, store.get().at)
        off()
        db.close()
    }

    @Test
    fun chain_headers_start_does_not_clobber_db_seeded_progress() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val seed = checkpointSeedRecord()
            db.headers.ensureCheckpoint(checkpointDbRecord())
            val base = db.headers.tip()!!.cumulativeWork
            db.headers.append(
                listOf(
                    HeaderWrite(
                        height = seed.height + 1,
                        hashInternalHex = "aa".repeat(32),
                        header = dummyHeader(),
                        cumulativeWork = base + BigInteger.ONE,
                    ),
                ),
            )
            val store = createHeadersProgressStore()
            val off = bindHeaderProgressEvents(bus, db, store)
            hydrateHeaders(db, store)
            val seeded = store.get()
            assertEquals(seed.height + 1, seeded.height)
            assertEquals(1, seeded.downloaded)
            assertEquals(1, seeded.total)

            val headers =
                createChainHeadersModule(
                    ModuleContext(bus, db),
                    ChainHeadersOptions(
                        net = stubNet(),
                        connectTimeoutMs = 50,
                        headersTimeoutMs = 50,
                        pollIntervalMs = 10_000,
                        fetchBatch = { _, _, _ ->
                            HeaderBatchResult.Ok(0, emptyList())
                        },
                    ),
                )
            headers.start()
            val after = store.get()
            assertEquals(seeded.downloaded, after.downloaded)
            assertEquals(seeded.total, after.total)
            assertEquals(seeded.percent, after.percent)
            headers.stop()
            off()
            db.close()
        }

    @Test
    fun hydrate_reads_tip_timestamp_from_header() {
        val db = createSqliteDatabase(":memory:")
        addHeader(db, 10, "aa", timestamp = 1_600_000_000)
        addHeader(db, 11, "bb", timestamp = 1_700_000_042)
        val store = createHeadersProgressStore()
        hydrateHeaders(db, store, 1, 1)
        assertEquals(11, store.get().height)
        assertEquals(1_700_000_042, store.get().tipTimeS)
        db.close()
    }
}
