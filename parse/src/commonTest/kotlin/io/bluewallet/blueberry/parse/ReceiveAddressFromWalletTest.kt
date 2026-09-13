package io.bluewallet.blueberry.parse

import fr.acinq.bitcoin.Transaction
import io.bluewallet.blueberry.storage.FilterRecord
import io.bluewallet.blueberry.storage.StoredTx
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.CreateWalletOptions
import io.bluewallet.blueberry.wallet.WatchGaps
import io.bluewallet.blueberry.wallet.createWallet
import io.bluewallet.blueberry.wallet.deriveWatchWallet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReceiveAddressFromWalletTest {
    @Test
    fun empty_hd_wallet_shows_first_external() {
        val wallet = deriveWatchWallet(ABANDON_MNEMONIC, WatchGaps(3, 1))
        assertEquals(
            wallet.addresses.first { !it.change && it.index == 0 }.address,
            receiveAddressFromWallet(wallet, emptyList()),
        )
    }

    @Test
    fun funding_first_external_advances_to_next() {
        val wallet = deriveWatchWallet(ABANDON_MNEMONIC, WatchGaps(3, 1))
        val first = wallet.addresses.first { !it.change && it.index == 0 }
        val second = wallet.addresses.first { !it.change && it.index == 1 }
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
            wallet.addresses.filter { !it.change }.mapIndexed { i, addr ->
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
        val before = wallet.snapshot()
        before.addresses.filter { !it.change }.forEachIndexed { i, addr ->
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
        val next = snapshotReceiveAddress(db, wallet)
        val after = wallet.snapshot()
        val expected = after.addresses.first { !it.change && it.index == 2 }.address
        assertEquals(expected, next)
        assertTrue(wallet.gaps().external > 2)
    }

    @Test
    fun exhausted_watch_queues_rematch_so_parse_cannot_skip_it() {
        val db = createSqliteDatabase(":memory:")
        val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 2))
        wallet.snapshot().addresses.filter { !it.change }.forEachIndexed { i, addr ->
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

        assertTrue(wallet.gaps().external > 2)
        assertEquals(listOf(100, 101, 102), db.filters.listNeedingMatch(10).map { it.height })
        assertEquals(false, db.parsedBlocks.has(100))
        assertEquals(false, db.parsedBlocks.has(101))
        db.close()
    }
}
