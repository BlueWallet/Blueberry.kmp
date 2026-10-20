package io.bluewallet.blueberry.parse

import fr.acinq.bitcoin.Transaction
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.StoredTx
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.AddressScriptType
import io.bluewallet.blueberry.wallet.CreateWalletOptions
import io.bluewallet.blueberry.wallet.GAP_LIMIT
import io.bluewallet.blueberry.wallet.createWallet
import io.bluewallet.blueberry.wallet.hd
import io.bluewallet.blueberry.wallet.resolvedScriptType
import io.bluewallet.blueberry.wallet.saveReceiveScriptType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReceiveChainTest {
    @Test
    fun chain_lists_external_addresses_with_paid_and_spent_from_indexes_used() {
        val db = createSqliteDatabase(":memory:")
        val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 40))
        val snap = wallet.snapshot()
        val paysTwo = coinbaseLikeReceive(snap.hd(AddressScriptType.P2WPKH, 2).scriptPubKey, 1000, prevSalt = 1)
        val paysOne = coinbaseLikeReceive(snap.hd(AddressScriptType.P2WPKH, 1).scriptPubKey, 1000, prevSalt = 2)
        val spendsThree = witnessSpend(watchPubkey0("m/84'/0'/0'/0/3"))
        listOf(paysTwo, paysOne, knownOutpointSpend(paysOne.txid.toString()), spendsThree)
            .forEachIndexed { i, tx -> db.store(tx, 100 + i) }

        val snapshot = snapshotReceive(db, wallet)
        val chain = snapshot.chain!!

        assertEquals(0, chain.start)
        assertEquals(snapshot.address, chain.addresses[chain.start].address)
        assertEquals(setOf(1, 2, 3), chain.used)
        assertEquals(40, chain.addresses.size)
        assertEquals(snap.hd(AddressScriptType.P2WPKH, 7).address, chain.addresses[7].address)
        assertEquals(3 + GAP_LIMIT, chain.lastIndex)
        db.close()
    }

    @Test
    fun chain_follows_saved_receive_type_and_skips_wif() {
        val db = createSqliteDatabase(":memory:")
        val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 40))
        saveReceiveScriptType(db, AddressScriptType.P2TR)
        val taproot = snapshotReceive(db, wallet)
        assertEquals(wallet.snapshot().hd(AddressScriptType.P2TR, 0).address, taproot.address)
        assertTrue(taproot.chain!!.addresses.all { it.resolvedScriptType() == AddressScriptType.P2TR && !it.change })

        val wif = createWallet(db, CreateWalletOptions(secret = "L4ccWrPMmFDZw4kzAKFqJNxgHANjdy6b7YKNXMwB4xac4FLF3Tov"))
        val wifSnapshot = snapshotReceive(db, wif)
        assertEquals(snapshotReceiveAddress(db, wif), wifSnapshot.address)
        assertNull(wifSnapshot.chain)
        db.close()
    }

    @Test
    fun chain_paths_follow_the_script_type_purpose() {
        val db = createSqliteDatabase(":memory:")
        val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 40))
        val purposes = mapOf(AddressScriptType.P2PKH to 44, AddressScriptType.P2SH_P2WPKH to 49, AddressScriptType.P2WPKH to 84, AddressScriptType.P2TR to 86)
        for ((type, purpose) in purposes) {
            saveReceiveScriptType(db, type)
            val chain = snapshotReceive(db, wallet).chain!!
            assertEquals("m/$purpose'/0'/0'/0/5", chain.addresses[5].path)
        }
        db.close()
    }

    @Test
    fun zpub_chain_paths_are_bip84_account_zero() {
        val db = createSqliteDatabase(":memory:")
        val wallet = createWallet(db, CreateWalletOptions(secret = ZPUB))
        val chain = snapshotReceive(db, wallet).chain!!
        assertEquals("m/84'/0'/0'/0/2", chain.addresses[2].path)
        db.close()
    }

    private companion object {
        const val ZPUB =
            "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"
    }
}

private fun Database.store(
    tx: Transaction,
    height: Int,
) = transactions.upsert(
    StoredTx(
        txid = tx.txid.toString(),
        height = height,
        txIndex = 0,
        blockHashInternalHex = "11".repeat(32),
        tx = Transaction.write(tx),
        netDeltaSats = 1000,
    ),
)
