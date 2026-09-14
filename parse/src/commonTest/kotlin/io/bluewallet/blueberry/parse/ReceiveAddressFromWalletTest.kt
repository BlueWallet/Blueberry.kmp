package io.bluewallet.blueberry.parse

import fr.acinq.bitcoin.Transaction
import io.bluewallet.blueberry.storage.FilterRecord
import io.bluewallet.blueberry.storage.StoredTx
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
import io.bluewallet.blueberry.wallet.saveReceiveScriptType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReceiveAddressFromWalletTest {
    @Test
    fun snapshot_uses_saved_receive_script_type() {
        val db = createSqliteDatabase(":memory:")
        val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 2))
        saveReceiveScriptType(db, AddressScriptType.P2TR)
        assertEquals(
            wallet.snapshot().hd(AddressScriptType.P2TR, 0).address,
            snapshotReceiveAddress(db, wallet),
        )
        db.close()
    }

    @Test
    fun zpub_ignores_saved_receive_type_and_uses_bip84() {
        val db = createSqliteDatabase(":memory:")
        saveReceiveScriptType(db, AddressScriptType.P2TR)
        val wallet =
            createWallet(
                db,
                CreateWalletOptions(
                    secret =
                        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs",
                ),
            )
        val expected =
            wallet
                .snapshot()
                .addresses
                .first { !it.change && it.scriptType == AddressScriptType.P2WPKH }
                .address
        assertEquals(expected, snapshotReceiveAddress(db, wallet))
        db.close()
    }

    @Test
    fun snapshot_unwraps_first_external_when_watch_window_is_empty() {
        val db = createSqliteDatabase(":memory:")
        val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 0))
        val expected =
            deriveWatchWallet(ABANDON_MNEMONIC, WatchGaps(1, 0))
                .hd(AddressScriptType.P2WPKH, 0)
                .address
        assertEquals(expected, snapshotReceiveAddress(db, wallet))
        assertTrue(wallet.gaps()[AddressScriptType.P2WPKH].external >= 1)
        db.close()
    }

    @Test
    fun snapshot_keeps_unwrapping_when_one_gap_grow_is_still_all_used() {
        val db = createSqliteDatabase(":memory:")
        val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 2))
        val wide = deriveWatchWallet(ABANDON_MNEMONIC, WatchGaps(2 + GAP_LIMIT + 1, 1))
        wide.addresses
            .filter { !it.change && it.scriptType == AddressScriptType.P2WPKH && it.index < 2 + GAP_LIMIT }
            .forEachIndexed { i, addr ->
                val receive = coinbaseLikeReceive(addr.scriptPubKey, 1000, prevSalt = i.toByte())
                db.transactions.upsert(
                    StoredTx(
                        txid = receive.txid.toString(),
                        height = 100 + i,
                        txIndex = 0,
                        blockHashInternalHex = "11".repeat(32),
                        tx = Transaction.write(receive),
                        netDeltaSats = 1000,
                    ),
                )
            }
        val next = wide.hd(AddressScriptType.P2WPKH, 2 + GAP_LIMIT).address
        assertEquals(next, snapshotReceiveAddress(db, wallet))
        assertTrue(wallet.gaps()[AddressScriptType.P2WPKH].external > 2 + GAP_LIMIT)
        db.close()
    }

    @Test
    fun snapshot_grow_does_not_rewrite_other_script_windows() {
        val db = createSqliteDatabase(":memory:")
        val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 2))
        saveHdWatchGaps(
            db,
            loadHdWatchGaps(db)
                .with(AddressScriptType.P2WPKH, WatchGaps(2, 2))
                .with(AddressScriptType.P2TR, WatchGaps(40, 21)),
        )
        wallet.refresh()
        wallet
            .snapshot()
            .addresses
            .filter { !it.change && it.scriptType == AddressScriptType.P2WPKH }
            .forEachIndexed { i, addr ->
                val receive = coinbaseLikeReceive(addr.scriptPubKey, 1000, prevSalt = i.toByte())
                db.transactions.upsert(
                    StoredTx(
                        txid = receive.txid.toString(),
                        height = 100 + i,
                        txIndex = 0,
                        blockHashInternalHex = "11".repeat(32),
                        tx = Transaction.write(receive),
                        netDeltaSats = 1000,
                    ),
                )
            }
        assertNotNull(snapshotReceiveAddress(db, wallet))
        val gaps = loadHdWatchGaps(db)
        assertTrue(gaps[AddressScriptType.P2WPKH].external > 2)
        assertEquals(40, gaps[AddressScriptType.P2TR].external)
        assertEquals(21, gaps[AddressScriptType.P2TR].internal)
        db.close()
    }

    @Test
    fun empty_hd_wallet_shows_first_external() {
        val wallet = deriveWatchWallet(ABANDON_MNEMONIC, WatchGaps(3, 1))
        assertEquals(
            wallet.hd(AddressScriptType.P2WPKH, 0).address,
            receiveAddressFromWallet(wallet, emptyList()),
        )
    }

    @Test
    fun funding_first_external_advances_to_next() {
        val wallet = deriveWatchWallet(ABANDON_MNEMONIC, WatchGaps(3, 1))
        val first = wallet.hd(AddressScriptType.P2WPKH, 0)
        val second = wallet.hd(AddressScriptType.P2WPKH, 1)
        val receive = coinbaseLikeReceive(first.scriptPubKey, 1000)
        val row =
            StoredTx(
                txid = receive.txid.toString(),
                height = 100,
                txIndex = 0,
                blockHashInternalHex = "11".repeat(32),
                tx = Transaction.write(receive),
                netDeltaSats = 1000,
            )
        assertEquals(second.address, receiveAddressFromWallet(wallet, listOf(row)))
    }

    @Test
    fun all_watched_externals_used_is_null() {
        val wallet = deriveWatchWallet(ABANDON_MNEMONIC, WatchGaps(2, 1))
        val rows =
            wallet.addresses
                .filter { !it.change && it.scriptType == AddressScriptType.P2WPKH }
                .mapIndexed { i, addr ->
                    val receive = coinbaseLikeReceive(addr.scriptPubKey, 1000, prevSalt = i.toByte())
                    StoredTx(
                        txid = receive.txid.toString(),
                        height = 100 + i,
                        txIndex = 0,
                        blockHashInternalHex = "11".repeat(32),
                        tx = Transaction.write(receive),
                        netDeltaSats = 1000,
                    )
                }
        assertEquals(null, receiveAddressFromWallet(wallet, rows))
    }

    @Test
    fun exhausted_watch_grows_and_unwraps_next_external() {
        val db = createSqliteDatabase(":memory:")
        val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 2))
        saveHdWatchGaps(db, HdWatchGaps.uniform(WatchGaps(2, 2)))
        val before = wallet.snapshot()
        before.addresses
            .filter { !it.change && it.scriptType == AddressScriptType.P2WPKH }
            .forEachIndexed { i, addr ->
                val receive = coinbaseLikeReceive(addr.scriptPubKey, 1000, prevSalt = i.toByte())
                db.transactions.upsert(
                    StoredTx(
                        txid = receive.txid.toString(),
                        height = 100 + i,
                        txIndex = 0,
                        blockHashInternalHex = "11".repeat(32),
                        tx = Transaction.write(receive),
                        netDeltaSats = 1000,
                    ),
                )
            }
        snapshotReceiveAddress(db, wallet)
        assertTrue(wallet.gaps()[AddressScriptType.P2WPKH].external > 2)
    }

    @Test
    fun exhausted_watch_queues_rematch_so_parse_cannot_skip_it() {
        val db = createSqliteDatabase(":memory:")
        val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 2))
        saveHdWatchGaps(db, HdWatchGaps.uniform(WatchGaps(2, 2)))
        wallet
            .snapshot()
            .addresses
            .filter { !it.change && it.scriptType == AddressScriptType.P2WPKH }
            .forEachIndexed { i, addr ->
                val receive = coinbaseLikeReceive(addr.scriptPubKey, 1000, prevSalt = i.toByte())
                db.transactions.upsert(
                    StoredTx(
                        txid = receive.txid.toString(),
                        height = 100 + i,
                        txIndex = 0,
                        blockHashInternalHex = "11".repeat(32),
                        tx = Transaction.write(receive),
                        netDeltaSats = 1000,
                    ),
                )
            }
        for (h in 100..102) {
            db.filters.append(listOf(FilterRecord(h, "bb".repeat(32), byteArrayOf(0x00))))
        }
        db.filters.markScanned(listOf(100, 101, 102))
        db.parsedBlocks.mark(100)
        db.parsedBlocks.mark(101)

        snapshotReceiveAddress(db, wallet)

        assertTrue(wallet.gaps()[AddressScriptType.P2WPKH].external > 2)
        assertEquals(listOf(100, 101, 102), db.filters.listNeedingMatch(10).map { it.height })
        assertEquals(false, db.parsedBlocks.has(100))
        assertEquals(false, db.parsedBlocks.has(101))
        db.close()
    }
}
