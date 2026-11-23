package io.bluewallet.blueberry.wallet

import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WalletTest {
    @Test
    fun loads_kv_secret_and_first_address() {
        val db = createSqliteDatabase(":memory:")
        saveWalletSecret(db, ABANDON)
        val wallet = createWallet(db)
        assertEquals(HdWatchGaps.initial(), wallet.gaps())
        assertEquals(INITIAL_WATCH_COUNT.toString(), db.keyValue.get(WATCH_EXTERNAL_P2WPKH_KEY))
        assertEquals(BLUE_EXTERNAL_0, wallet.snapshot().hd(AddressScriptType.P2WPKH, 0).address)
        assertEquals(INITIAL_WATCH_COUNT * 2 * 4, wallet.scripts().size)
        db.close()
    }

    @Test
    fun secret_override_and_address_gap_does_not_write_secret() {
        val db = createSqliteDatabase(":memory:")
        val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON, addressGap = 3))
        assertEquals(HdWatchGaps.uniform(WatchGaps(3, 3)), wallet.gaps())
        assertEquals(HdWatchGaps.uniform(WatchGaps(3, 3)), loadHdWatchGaps(db))
        assertEquals(24, wallet.snapshot().addresses.size)
        assertNull(db.keyValue.get(WALLET_SECRET_KEY))
        db.close()
    }

    @Test
    fun throws_when_secret_missing_or_invalid() {
        val db = createSqliteDatabase(":memory:")
        assertFailsWith<IllegalArgumentException> { createWallet(db) }.also {
            assertTrue(it.message!!.contains("wallet_secret"))
        }
        db.keyValue.set(WALLET_SECRET_KEY, "not a real mnemonic phrase at all")
        assertFailsWith<IllegalArgumentException> { createWallet(db) }
        db.close()
    }

    @Test
    fun sync_from_db_rederives_only_when_gaps_change() {
        val db = createSqliteDatabase(":memory:")
        val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON, addressGap = 2))
        assertEquals(16, wallet.scripts().size)
        val scripts1 = wallet.scripts()
        assertFalse(wallet.syncFromDb().grew)
        assertSame(scripts1, wallet.scripts())
        assertSame(wallet.snapshot(), wallet.refresh())
        saveHdWatchGaps(db, HdWatchGaps.uniform(WatchGaps(5, 2)))
        assertTrue(wallet.syncFromDb().grew)
        assertEquals(HdWatchGaps.uniform(WatchGaps(5, 2)), wallet.gaps())
        assertEquals(28, wallet.scripts().size)
        assertEquals(BLUE_EXTERNAL_0, wallet.snapshot().hd(AddressScriptType.P2WPKH, 0).address)
        db.close()
    }

    @Test
    fun peek_gaps_does_not_change_memory() {
        val db = createSqliteDatabase(":memory:")
        val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON, addressGap = 2))
        saveHdWatchGaps(db, HdWatchGaps.uniform(WatchGaps(9, 2)))
        assertEquals(HdWatchGaps.uniform(WatchGaps(9, 2)), wallet.peekGaps())
        assertEquals(HdWatchGaps.uniform(WatchGaps(2, 2)), wallet.gaps())
        assertEquals(16, wallet.scripts().size)
        db.close()
    }

    @Test
    fun watch_keys_round_trip_through_kv() {
        val db = createSqliteDatabase(":memory:")
        val pasted = "\n  $TR_ABANDON_RECEIVE_DESCRIPTOR  \r\n$TR_ABANDON_CHANGE_DESCRIPTOR \n"
        assertEquals(WalletSecretKind.DESCRIPTOR, saveWalletSecret(db, pasted).kind)
        assertEquals("$TR_ABANDON_RECEIVE_DESCRIPTOR\n$TR_ABANDON_CHANGE_DESCRIPTOR", db.keyValue.get(WALLET_SECRET_KEY))
        val wallet = createWallet(db, CreateWalletOptions(addressGap = 2))
        assertEquals(BIP86_ABANDON_EXT_1, wallet.snapshot().hd(AddressScriptType.P2TR, 1).address)
        assertEquals(4, wallet.scripts().size)
        saveWatchGaps(db, AddressScriptType.P2TR, WatchGaps(5, 2))
        assertTrue(wallet.syncFromDb().grew)
        assertEquals(7, wallet.scripts().size)
        db.close()

        val xdb = createSqliteDatabase(":memory:")
        saveWalletSecret(xdb, ABANDON_BIP44_XPUB)
        val xw = createWallet(xdb, CreateWalletOptions(addressGap = 1))
        assertEquals(ABANDON_BIP44_XPUB, xw.snapshot().secret)
        assertEquals(listOf(BIP44_ABANDON_EXT_0, BIP44_ABANDON_INT_0), xw.snapshot().addresses.map { it.address })
        xdb.close()
    }

    @Test
    fun zpub_and_wif_from_kv() {
        val zdb = createSqliteDatabase(":memory:")
        saveWalletSecret(zdb, BLUE_ZPUB)
        val zw = createWallet(zdb, CreateWalletOptions(addressGap = 2))
        assertEquals(BLUE_ZPUB, zw.snapshot().secret)
        assertEquals(BLUE_EXTERNAL_0, zw.snapshot().addresses[0].address)
        zdb.close()
        val wdb = createSqliteDatabase(":memory:")
        saveWalletSecret(wdb, WIF_BECH32)
        val ww = createWallet(wdb)
        assertEquals(WatchWalletKind.WIF, ww.snapshot().kind)
        assertEquals(4, ww.snapshot().addresses.size)
        assertEquals(
            ADDR_BECH32,
            ww
                .snapshot()
                .addresses
                .first { it.scriptType == AddressScriptType.P2WPKH }
                .address,
        )
        wdb.close()
    }
}
