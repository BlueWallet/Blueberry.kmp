package io.bluewallet.blueberry.parse

import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.SyncIdlePayload
import io.bluewallet.blueberry.bus.createMessageBus
import io.bluewallet.blueberry.parse.modules.ParseBlocksOptions
import io.bluewallet.blueberry.parse.modules.createParseBlocksModule
import io.bluewallet.blueberry.peers.modules.ModuleContext
import io.bluewallet.blueberry.storage.DownloadedBlock
import io.bluewallet.blueberry.storage.FilterRecord
import io.bluewallet.blueberry.storage.HeaderWrite
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.AddressScriptType
import io.bluewallet.blueberry.wallet.CreateWalletOptions
import io.bluewallet.blueberry.wallet.GAP_LIMIT
import io.bluewallet.blueberry.wallet.HdWatchGaps
import io.bluewallet.blueberry.wallet.WatchGaps
import io.bluewallet.blueberry.wallet.createWallet
import io.bluewallet.blueberry.wallet.deriveWatchWallet
import io.bluewallet.blueberry.wallet.hd
import io.bluewallet.blueberry.wallet.loadHdWatchGaps
import io.bluewallet.blueberry.wallet.saveHdWatchGaps
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun blockBytesPaying(
    script: ByteArray,
    value: Long,
): ByteArray = wrapBlock(coinbaseLikeReceive(script, value))

class ParseBlocksGapTest {
    @Test
    fun used_address_in_danger_zone_grows_external_and_rematches_from_first_used_height() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val window = 40
            val grown = window + GAP_LIMIT
            val wallet = deriveWatchWallet(ABANDON_MNEMONIC, window)
            val danger = wallet.hd(AddressScriptType.P2WPKH, 25)

            db.blocks.insert(DownloadedBlock(3, "aa".repeat(32), blockBytesPaying(danger.scriptPubKey, 1000)))
            for (h in 1..5) {
                db.filters.append(
                    listOf(
                        FilterRecord(h, "bb".repeat(32), byteArrayOf(0x00)),
                    ),
                )
            }
            db.filters.markScanned(listOf(1, 2, 3, 4, 5))

            val progress = mutableListOf<Pair<Int, Int>>()
            bus.on(Event.FiltersProgress) { progress.add(it.downloaded to it.total) }

            val watch = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = window))
            saveHdWatchGaps(db, HdWatchGaps.uniform(WatchGaps(window, window)))
            val mod =
                createParseBlocksModule(
                    ModuleContext(bus, db),
                    ParseBlocksOptions(wallet = watch, idleDelayMs = 50, blockGapMs = 0),
                )
            mod.start()
            bus.emit(Event.SyncIdle, SyncIdlePayload(at = 1))
            waitFor(10_000) {
                loadHdWatchGaps(db)[AddressScriptType.P2WPKH].external == grown && progress.isNotEmpty()
            }
            assertEquals(window, loadHdWatchGaps(db)[AddressScriptType.P2WPKH].internal)
            assertEquals(3, db.transactions.minHeight())
            assertEquals(listOf(3, 4, 5), db.filters.listNeedingMatch(10).map { it.height })
            assertEquals(5, progress[0].first)
            mod.stop()
            db.close()
        }

    @Test
    fun gap_growth_aborts_the_current_batch_so_later_heights_use_the_new_watchlist() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val wide = deriveWatchWallet(ABANDON_MNEMONIC, 60)
            val danger = wide.hd(AddressScriptType.P2WPKH, 25)
            val next = wide.hd(AddressScriptType.P2WPKH, 45)

            db.blocks.insert(DownloadedBlock(3, "dd".repeat(32), blockBytesPaying(danger.scriptPubKey, 1000)))
            db.blocks.insert(DownloadedBlock(4, "cc".repeat(32), blockBytesPaying(next.scriptPubKey, 2000)))

            val watch = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 40))
            saveHdWatchGaps(db, HdWatchGaps.uniform(WatchGaps(40, 40)))
            val mod =
                createParseBlocksModule(
                    ModuleContext(bus, db),
                    ParseBlocksOptions(wallet = watch, idleDelayMs = 50, batchSize = 8, blockGapMs = 0),
                )
            mod.start()
            bus.emit(Event.SyncIdle, SyncIdlePayload(at = 1))
            waitFor(10_000) {
                usedWatchIndexes(
                    db.transactions.list().map { it.tx },
                    watch.snapshot(),
                ).get(AddressScriptType.P2WPKH).external.contains(45)
            }
            assertEquals(2, db.transactions.count())
            assertTrue(loadHdWatchGaps(db)[AddressScriptType.P2WPKH].external >= 40 + GAP_LIMIT)
            mod.stop()
            db.close()
        }

    @Test
    fun gap_growth_re_parses_already_downloaded_blocks_for_newly_watched_indexes() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val wide = deriveWatchWallet(ABANDON_MNEMONIC, 60)
            val danger = wide.hd(AddressScriptType.P2WPKH, 25)
            val next = wide.hd(AddressScriptType.P2WPKH, 45)

            db.blocks.insert(DownloadedBlock(4, "cc".repeat(32), blockBytesPaying(next.scriptPubKey, 2000)))
            db.parsedBlocks.mark(4)
            db.blocks.insert(DownloadedBlock(3, "dd".repeat(32), blockBytesPaying(danger.scriptPubKey, 1000)))

            val watch = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 40))
            saveHdWatchGaps(db, HdWatchGaps.uniform(WatchGaps(40, 40)))
            val mod =
                createParseBlocksModule(
                    ModuleContext(bus, db),
                    ParseBlocksOptions(wallet = watch, idleDelayMs = 50, blockGapMs = 0),
                )
            mod.start()
            bus.emit(Event.SyncIdle, SyncIdlePayload(at = 1))
            waitFor(10_000) {
                usedWatchIndexes(
                    db.transactions.list().map { it.tx },
                    watch.snapshot(),
                ).get(AddressScriptType.P2WPKH).external.contains(45)
            }
            assertTrue(loadHdWatchGaps(db)[AddressScriptType.P2WPKH].external >= 40 + GAP_LIMIT)
            mod.stop()
            db.close()
        }

    @Test
    fun gap_growth_rematches_from_header_floor_so_earlier_new_index_payments_are_found() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val wide = deriveWatchWallet(ABANDON_MNEMONIC, 60)
            val danger = wide.hd(AddressScriptType.P2WPKH, 25)
            val next = wide.hd(AddressScriptType.P2WPKH, 45)

            for (h in 1..5) {
                db.headers.append(
                    listOf(
                        HeaderWrite(
                            height = h,
                            hashInternalHex = h.toString(16).padStart(2, '0').repeat(32),
                            header = ByteArray(80),
                        ),
                    ),
                )
                db.filters.append(
                    listOf(FilterRecord(h, "bb".repeat(32), byteArrayOf(0x00))),
                )
            }
            db.filters.markScanned(listOf(1, 2, 3, 4, 5))

            db.blocks.insert(DownloadedBlock(2, "cc".repeat(32), blockBytesPaying(next.scriptPubKey, 2000)))
            db.parsedBlocks.mark(2)
            db.blocks.insert(DownloadedBlock(5, "dd".repeat(32), blockBytesPaying(danger.scriptPubKey, 1000)))

            val watch = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 40))
            saveHdWatchGaps(db, HdWatchGaps.uniform(WatchGaps(40, 40)))
            val mod =
                createParseBlocksModule(
                    ModuleContext(bus, db),
                    ParseBlocksOptions(wallet = watch, idleDelayMs = 50, blockGapMs = 0),
                )
            mod.start()
            bus.emit(Event.SyncIdle, SyncIdlePayload(at = 1))
            waitFor(10_000) {
                usedWatchIndexes(
                    db.transactions.list().map { it.tx },
                    watch.snapshot(),
                ).get(AddressScriptType.P2WPKH).external.contains(45)
            }
            assertEquals(listOf(1, 2, 3, 4, 5), db.filters.listNeedingMatch(10).map { it.height })
            mod.stop()
            db.close()
        }
}
