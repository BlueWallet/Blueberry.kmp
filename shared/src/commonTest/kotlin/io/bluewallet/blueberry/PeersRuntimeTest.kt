package io.bluewallet.blueberry

import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.ModuleStatus
import io.bluewallet.blueberry.bus.PeerSocketKind
import io.bluewallet.blueberry.bus.PeersSocketsPayload
import io.bluewallet.blueberry.bus.PeersUpdatedPayload
import io.bluewallet.blueberry.bus.createMessageBus
import io.bluewallet.blueberry.storage.PeerWrite
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.saveWalletSecret
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeersRuntimeTest {
    @Test
    fun start_is_idempotent() = runBlocking {
        val db = createSqliteDatabase(":memory:")
        val runtime = PeersRuntime(db)
        var blockStarts = 0
        val starts = mutableListOf<String>()
        runtime.bus.on(Event.ModuleStatus) {
            if (it.status != ModuleStatus.STARTING) return@on
            starts.add(it.module)
            if (it.module == "blocks-download") blockStarts++
        }

        runtime.start()
        runtime.start()

        assertEquals(1, blockStarts)
        assertTrue(starts.indexOf("sync-idle") < starts.indexOf("peers-discovery"))
        runtime.stop()
        db.close()
    }

    @Test
    fun starts_parse_blocks_when_wallet_secret_is_present() = runBlocking {
        val db = createSqliteDatabase(":memory:")
        saveWalletSecret(
            db,
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        )
        val runtime = PeersRuntime(db)
        val starts = mutableListOf<String>()
        runtime.bus.on(Event.ModuleStatus) {
            if (it.status == ModuleStatus.STARTING) starts.add(it.module)
        }
        runtime.start()
        assertTrue(starts.contains("parse-blocks"))
        assertTrue(starts.indexOf("parse-blocks") < starts.indexOf("filters-matching"))
        runtime.stop()
        db.close()
    }

    @Test
    fun current_receive_address_reads_secret_before_start() {
        val db = createSqliteDatabase(":memory:")
        saveWalletSecret(
            db,
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        )
        val runtime = PeersRuntime(db)
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", currentReceiveAddress(runtime, db))
        runtime.stop()
        db.close()
    }

    @Test
    fun start_exposes_wallet_when_secret_is_present() = runBlocking {
        val db = createSqliteDatabase(":memory:")
        saveWalletSecret(
            db,
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        )
        val runtime = PeersRuntime(db)
        runtime.start()
        val first = runtime.wallet!!.snapshot().addresses.first { !it.change && it.index == 0 }
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", first.address)
        assertEquals(first.address, currentReceiveAddress(runtime, db))
        runtime.stop()
        db.close()
    }

    @Test
    fun bindPeerSocketEvents_hydrates_and_applies() {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(
            PeerWrite("1.1.1.1", 8333, 0uL, false, false, null),
        )
        val store = createPeerSocketsStore()
        val off = bindPeerSocketEvents(bus, db, store)
        hydratePeers(db, store)
        assertEquals(1, store.get().known)
        bus.emit(Event.PeersSockets, PeersSocketsPayload(1, PeerSocketKind.PROBE, 2))
        assertEquals(2, store.get().probe)
        db.peers.upsert(PeerWrite("9.9.9.9", 8333, 0uL, false, false, null))
        bus.emit(Event.PeersUpdated, PeersUpdatedPayload(2))
        assertEquals(2, store.get().known)
        off()
        db.close()
    }

    @Test
    fun bindHeaderProgressEvents_hydrates_from_db_then_progress() {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.headers.append(
            listOf(
                io.bluewallet.blueberry.storage.HeaderWrite(
                    height = 10,
                    hashInternalHex = "aa".repeat(32),
                    header = ByteArray(80),
                    cumulativeWork = com.ionspin.kotlin.bignum.integer.BigInteger.fromInt(10),
                ),
                io.bluewallet.blueberry.storage.HeaderWrite(
                    height = 11,
                    hashInternalHex = "bb".repeat(32),
                    header = ByteArray(80),
                    cumulativeWork = com.ionspin.kotlin.bignum.integer.BigInteger.fromInt(11),
                ),
            ),
        )
        val store = createHeadersProgressStore()
        val off = bindHeaderProgressEvents(bus, db, store)
        hydrateHeaders(db, store)
        assertEquals(1, store.get().downloaded)
        assertEquals(1, store.get().total)
        assertEquals(11, store.get().height)
        bus.emit(
            Event.HeadersProgress,
            io.bluewallet.blueberry.bus.HeadersProgressPayload(3, 1, 500, 11),
        )
        assertEquals(1, store.get().downloaded)
        assertEquals(500, store.get().total)
        assertEquals(11, store.get().height)
        off()
        db.close()
    }

    @Test
    fun bindFilterProgressEvents_hydrates_from_db_then_progress() {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.filters.append(
            listOf(
                io.bluewallet.blueberry.storage.FilterRecord(
                    height = 1,
                    blockHashInternalHex = "11".repeat(32),
                    filter = byteArrayOf(0xaa.toByte()),
                ),
                io.bluewallet.blueberry.storage.FilterRecord(
                    height = 2,
                    blockHashInternalHex = "22".repeat(32),
                    filter = byteArrayOf(0xbb.toByte()),
                ),
            ),
        )
        val store = createFiltersProgressStore()
        val off = bindFilterProgressEvents(bus, db, store)
        hydrateFilters(db, store)
        assertEquals(2, store.get().downloaded)
        assertEquals(2, store.get().total)
        bus.emit(
            Event.FiltersProgress,
            io.bluewallet.blueberry.bus.FiltersProgressPayload(3, 1, 500),
        )
        assertEquals(2, store.get().downloaded)
        assertEquals(500, store.get().total)
        off()
        db.close()
    }

    @Test
    fun bindMatchingProgressEvents_hydrates_from_db_then_progress() {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.filters.append(
            listOf(
                io.bluewallet.blueberry.storage.FilterRecord(
                    height = 1,
                    blockHashInternalHex = "11".repeat(32),
                    filter = byteArrayOf(0xaa.toByte()),
                ),
                io.bluewallet.blueberry.storage.FilterRecord(
                    height = 2,
                    blockHashInternalHex = "22".repeat(32),
                    filter = byteArrayOf(0xbb.toByte()),
                ),
            ),
        )
        db.filters.markScanned(listOf(1))
        val store = createMatchingProgressStore()
        val off = bindMatchingProgressEvents(bus, db, store)
        hydrateMatching(db, store)
        assertEquals(1, store.get().scanned)
        assertEquals(2, store.get().total)
        db.filters.markScanned(listOf(2))
        bus.emit(
            Event.MatchingProgress,
            io.bluewallet.blueberry.bus.MatchingProgressPayload(3, 0, 0),
        )
        assertEquals(2, store.get().scanned)
        assertEquals(2, store.get().total)
        off()
        db.close()
    }

    @Test
    fun bindBlocksProgressEvents_hydrates_from_db_then_progress() {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.matchedBlocks.insert(
            io.bluewallet.blueberry.storage.MatchedBlock(1, "11".repeat(32)),
        )
        val store = createBlocksMatchedStore()
        val off = bindBlocksProgressEvents(bus, db, store)
        hydrateBlocks(db, store)
        assertEquals(0, store.get().downloaded)
        assertEquals(1, store.get().matched)
        db.blocks.insert(
            io.bluewallet.blueberry.storage.DownloadedBlock(1, "11".repeat(32), byteArrayOf(1)),
        )
        bus.emit(
            Event.BlocksProgress,
            io.bluewallet.blueberry.bus.BlocksProgressPayload(3, 0, 0),
        )
        assertEquals(1, store.get().downloaded)
        assertEquals(1, store.get().matched)
        off()
        db.close()
    }
}
