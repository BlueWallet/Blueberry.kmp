package io.bluewallet.blueberry

import io.bluewallet.bip324.OutPoint
import io.bluewallet.bip324.Transaction
import io.bluewallet.bip324.TxInput
import io.bluewallet.bip324.TxOutput
import io.bluewallet.bip324.bytesToHex
import io.bluewallet.bip324.encodeTransaction
import io.bluewallet.blueberry.storage.BroadcastViaRepository
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.PrivateSendCoin
import io.bluewallet.blueberry.storage.SendRow
import io.bluewallet.blueberry.storage.StoredTx
import io.bluewallet.blueberry.storage.TxPaymentLabelRow
import io.bluewallet.blueberry.wallet.SignedPsbt
import io.bluewallet.blueberry.wallet.hexToBytes
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignedPsbtBroadcastTest {
    private val c = coin("ab", 100_000)
    private val unsignedTxid = "cd".repeat(32)
    private val unsignedRow = SendRow(unsignedTxid, spending(signed = false), ADDRESS, listOf(PrivateSendCoin(c.txid, c.vout, c.valueSats)))

    private fun signedPsbt(txid: String) = SignedPsbt(spending(signed = true), txid, 141, 141, emptyList())

    @Test
    fun a_segwit_spend_keeps_its_row_and_gets_the_signed_hex() =
        runFlow { db, runtime, _ ->
            storeUnsignedSend(db, runtime, unsignedRow).join()
            val createdAt = db.sends.get(unsignedTxid)!!.createdAt
            assertTrue(storeSignedPsbt(db, runtime, unsignedTxid, signedPsbt(unsignedTxid)))
            val row = db.sends.get(unsignedTxid)!!
            assertEquals(spending(signed = true), row.txHex)
            assertEquals(createdAt, row.createdAt)
            assertEquals(unsignedRow.coins, row.coins)
            assertFalse(storedSendIsPsbt(db, unsignedTxid))
        }

    @Test
    fun a_legacy_spend_moves_to_the_signed_txid_with_its_note() =
        runFlow { db, runtime, _ ->
            storeUnsignedSend(db, runtime, unsignedRow).join()
            db.txPaymentLabels.upsert(TxPaymentLabelRow(unsignedTxid, "Rent"))
            db.utxoNames.upsert("$unsignedTxid:1", "change from: Rent")
            val signed = signedPsbt("ef".repeat(32))
            assertTrue(storeSignedPsbt(db, runtime, unsignedTxid, signed))
            assertNull(db.sends.get(unsignedTxid))
            assertEquals(ADDRESS, db.sends.get(signed.txid)?.destination)
            assertEquals("Rent", db.txPaymentLabels.get(signed.txid)?.label)
            assertEquals(
                "change from: Rent",
                db.utxoNames
                    .list()
                    .single { it.outpoint == "${signed.txid}:1" }
                    .name,
            )
            assertTrue(storeSignedPsbt(db, runtime, unsignedTxid, signed), "a second scan finds the moved row")
        }

    /** The row moved but the app died before its note did: the next scan finds the row on the signed txid. */
    @Test
    fun a_note_left_behind_by_an_interrupted_move_follows_on_the_next_scan() =
        runFlow { db, runtime, _ ->
            storeUnsignedSend(db, runtime, unsignedRow).join()
            val signed = signedPsbt("ef".repeat(32))
            db.sends.delete(unsignedTxid)
            db.sends.upsert(unsignedRow.copy(txid = signed.txid))
            db.txPaymentLabels.upsert(TxPaymentLabelRow(unsignedTxid, "Rent"))
            db.utxoNames.upsert("$unsignedTxid:1", "change from: Rent")
            assertTrue(storeSignedPsbt(db, runtime, unsignedTxid, signed))
            assertEquals(signed.txHex, db.sends.get(signed.txid)?.txHex)
            assertEquals("Rent", db.txPaymentLabels.get(signed.txid)?.label)
            assertEquals(
                "change from: Rent",
                db.utxoNames
                    .list()
                    .single { it.outpoint == "${signed.txid}:1" }
                    .name,
            )
        }

    @Test
    fun a_forgotten_payment_is_refused() =
        runFlow { db, runtime, _ ->
            assertFalse(storeSignedPsbt(db, runtime, unsignedTxid, signedPsbt(unsignedTxid)))
            assertNull(db.sends.get(unsignedTxid))
        }

    @Test
    fun a_segwit_spend_already_in_a_block_is_refused() = refusedOnceInABlock(unsignedTxid)

    @Test
    fun a_legacy_spend_already_in_a_block_is_refused() = refusedOnceInABlock("ef".repeat(32))

    /** The signed txid is in the chain table, so storing settles the row and the payment isn't open anymore. */
    private fun refusedOnceInABlock(signedTxid: String) =
        runFlow { db, runtime, _ ->
            storeUnsignedSend(db, runtime, unsignedRow).join()
            val signed = signedPsbt(signedTxid)
            db.transactions.upsert(StoredTx(signedTxid, 300, 0, "33".repeat(32), hexToBytes(signed.txHex), -1_141L))
            assertFalse(storeSignedPsbt(db, runtime, unsignedTxid, signed))
            assertEquals(300L, db.sends.get(signedTxid)?.confirmedInBlock)
        }

    @Test
    fun a_segwit_spend_found_in_a_block_on_broadcast_says_it_was_stored() = storedOnceInABlock(unsignedTxid)

    @Test
    fun a_legacy_spend_found_in_a_block_on_broadcast_says_it_was_stored() = storedOnceInABlock("ef".repeat(32))

    /** Storing wrote (or moved) the row before hydrating found it confirmed, so Back must leave to the signed txid. */
    private fun storedOnceInABlock(signedTxid: String) =
        runFlow { db, runtime, _ ->
            storeUnsignedSend(db, runtime, unsignedRow).join()
            val signed = signedPsbt(signedTxid)
            db.transactions.upsert(StoredTx(signedTxid, 300, 0, "33".repeat(32), hexToBytes(signed.txHex), -1_141L))
            val receipt = BroadcastReceipt(unsignedTxid, ADDRESS, "", 50_000, 141)
            val refused = assertIs<SignedPsbtSent.Refused>(broadcastSignedPsbt(db, runtime, unsignedTxid, signed, receipt))
            assertEquals(SIGNED_PSBT_GONE, refused.reason)
            assertTrue(refused.stored)
        }

    @Test
    fun the_broadcast_starts_held_and_the_payment_can_go_again() =
        runFlow { db, runtime, _ ->
            val requests = listenForRequests(runtime)
            storeUnsignedSend(db, runtime, unsignedRow).join()
            val signed = signedPsbt("ef".repeat(32))
            assertTrue(storeSignedPsbt(db, runtime, unsignedTxid, signed))
            runtime.walletTxsStore.update { it.copy(utxos = listOf(c), utxosReady = true) }
            val receipt = BroadcastReceipt(unsignedTxid, ADDRESS, "Rent", 50_000, 141)
            val started = assertIs<SignedPsbtSent.Started>(startSignedPsbtBroadcast(runtime, db, signed, receipt))
            val session = runtime.broadcastStore.sessions().single { it.snapshot.id == started.id }
            assertTrue(session.snapshot.heldBefore)
            assertEquals(signed.txid, session.receipt?.txid)
            assertEquals(signed.txHex, requests.single().txHex)
            assertTrue(storedSendCanGoAgain(db, signed.txid))
            assertEquals(BROADCAST_VIA_TOR, db.broadcastVia.get(signed.txid))
            assertNull(db.broadcastVia.get(unsignedTxid))
        }

    /**
     * Back during Broadcast cancels the review's scope; the store and the start must still run as one step.
     * A segwit spend keeps its txid, so storing leaves the wallet's tx set alone and the coins seeded here stay
     * known: the start isn't refused for loading coins, and only a real start passes.
     */
    @Test
    fun storing_and_starting_finish_when_the_caller_is_cancelled() =
        runFlow { db, runtime, _ ->
            val requests = listenForRequests(runtime)
            storeUnsignedSend(db, runtime, unsignedRow).join()
            runtime.walletTxsStore.update { it.copy(utxos = listOf(c), utxosReady = true) }
            val signed = signedPsbt(unsignedTxid)
            val receipt = BroadcastReceipt(unsignedTxid, ADDRESS, "Rent", 50_000, 141)
            var result: SignedPsbtSent? = null
            coroutineScope {
                val job = launch(start = CoroutineStart.UNDISPATCHED) { result = broadcastSignedPsbt(db, runtime, unsignedTxid, signed, receipt) }
                job.cancel()
                job.join()
            }
            assertEquals(signed.txHex, db.sends.get(signed.txid)?.txHex)
            val started = assertIs<SignedPsbtSent.Started>(result)
            val session = runtime.broadcastStore.sessions().single { it.snapshot.id == started.id }
            assertTrue(session.snapshot.heldBefore)
            assertEquals(signed.txHex, requests.single().txHex)
        }

    /** The route is noted after the job starts; failing to note it must not report a started payment as unsaved. */
    @Test
    fun a_failed_route_write_after_the_start_still_reports_started() =
        runFlow { db, runtime, _ ->
            val requests = listenForRequests(runtime)
            storeUnsignedSend(db, runtime, unsignedRow).join()
            runtime.walletTxsStore.update { it.copy(utxos = listOf(c), utxosReady = true) }
            val signed = signedPsbt(unsignedTxid)
            val receipt = BroadcastReceipt(unsignedTxid, ADDRESS, "Rent", 50_000, 141)
            val failingRoute =
                object : Database by db {
                    override val broadcastVia =
                        object : BroadcastViaRepository by db.broadcastVia {
                            override fun upsert(
                                txid: String,
                                via: String,
                            ): Unit = error("disk full")
                        }
                }
            val started = assertIs<SignedPsbtSent.Started>(broadcastSignedPsbt(failingRoute, runtime, unsignedTxid, signed, receipt))
            assertTrue(runtime.broadcastStore.sessions().any { it.snapshot.id == started.id })
            assertEquals(signed.txHex, requests.single().txHex)
        }

    /** The review leaves to the signed txid after this, since the payment is no longer waiting to be signed. */
    @Test
    fun a_start_refused_after_storing_says_the_payment_was_stored() =
        runFlow { db, runtime, _ ->
            storeUnsignedSend(db, runtime, unsignedRow).join()
            val signed = signedPsbt("ef".repeat(32))
            val receipt = BroadcastReceipt(unsignedTxid, ADDRESS, "", 50_000, 141)
            val refused = assertIs<SignedPsbtSent.Refused>(broadcastSignedPsbt(db, runtime, unsignedTxid, signed, receipt))
            assertTrue(refused.stored)
            assertFalse(assertIs<SignedPsbtSent.Refused>(broadcastSignedPsbt(db, runtime, "aa".repeat(32), signedPsbt("bb".repeat(32)), receipt)).stored)
        }

    @Test
    fun the_broadcast_is_refused_while_the_coins_are_not_known() =
        runFlow { db, runtime, _ ->
            storeUnsignedSend(db, runtime, unsignedRow).join()
            val signed = signedPsbt(unsignedTxid)
            assertTrue(storeSignedPsbt(db, runtime, unsignedTxid, signed))
            val receipt = BroadcastReceipt(unsignedTxid, ADDRESS, "", 50_000, 141)
            assertIs<SignedPsbtSent.Refused>(startSignedPsbtBroadcast(runtime, db, signed, receipt))
        }

    private fun spending(signed: Boolean): String =
        bytesToHex(
            encodeTransaction(
                Transaction(
                    version = 1u,
                    inputs = listOf(TxInput(OutPoint(hexToBytes(c.txid).reversedArray(), 0u), if (signed) byteArrayOf(0x51) else ByteArray(0), 0xffffffffu)),
                    outputs = listOf(TxOutput(value = 1000, scriptPubKey = byteArrayOf(0x51))),
                    lockTime = 0u,
                ),
            ),
        )

    private companion object {
        const val ADDRESS = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
    }
}
