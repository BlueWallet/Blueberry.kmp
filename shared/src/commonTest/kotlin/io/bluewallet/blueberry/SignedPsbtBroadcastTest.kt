package io.bluewallet.blueberry

import io.bluewallet.bip324.OutPoint
import io.bluewallet.bip324.Transaction
import io.bluewallet.bip324.TxInput
import io.bluewallet.bip324.TxOutput
import io.bluewallet.bip324.bytesToHex
import io.bluewallet.bip324.encodeTransaction
import io.bluewallet.blueberry.broadcast.BROADCAST_REFUSED
import io.bluewallet.blueberry.bus.BroadcastDonePayload
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.WalletTxsPayload
import io.bluewallet.blueberry.storage.BroadcastViaRepository
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.PrivateSendCoin
import io.bluewallet.blueberry.storage.PrivateSendRow
import io.bluewallet.blueberry.storage.PrivateSendsRepository
import io.bluewallet.blueberry.storage.SendRow
import io.bluewallet.blueberry.storage.SendsRepository
import io.bluewallet.blueberry.storage.StoredTx
import io.bluewallet.blueberry.storage.TxPaymentLabelRow
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.SignedPsbt
import io.bluewallet.blueberry.wallet.hexToBytes
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

    /** Owner decision: a signed PSBT is persisted like a hot send, its Tor record written with its row. */
    @Test
    fun storing_a_segwit_spend_records_tor_like_a_hot_send() =
        runFlow { db, runtime, _ ->
            storeUnsignedSend(db, runtime, unsignedRow).join()
            assertNull(db.broadcastVia.get(unsignedTxid), "an unsigned PSBT has no broadcast yet")
            assertTrue(storeSignedPsbt(db, runtime, unsignedTxid, signedPsbt(unsignedTxid)))
            assertEquals(BROADCAST_VIA_TOR, db.broadcastVia.get(unsignedTxid))
        }

    @Test
    fun storing_a_legacy_spend_records_tor_on_the_signed_txid() =
        runFlow { db, runtime, _ ->
            val (signed, _) = startMoved(db, runtime)
            assertEquals(BROADCAST_VIA_TOR, db.broadcastVia.get(signed.txid))
            assertNull(db.broadcastVia.get(unsignedTxid))
        }

    /** The broadcast is Broadcast again on a stored send, so it starts held as a hot send's does on master. */
    @Test
    fun the_broadcast_starts_held_like_a_hot_sends_broadcast_again() =
        runFlow { db, runtime, _ ->
            val requests = listenForRequests(runtime)
            val (signed, id) = startMoved(db, runtime)
            val session = runtime.broadcastStore.sessions().single { it.snapshot.id == id }
            assertTrue(session.snapshot.heldBefore)
            assertFalse(broadcastCanBeStopped(session.snapshot))
            assertEquals(signed.txid, session.receipt?.txid)
            assertEquals(signed.txHex, requests.single().txHex)
            assertTrue(storedSendCanGoAgain(db, signed.txid))
        }

    /** A run that reaches no node ends as a hot send's Broadcast again does: held, row and Tor record kept. */
    @Test
    fun a_broadcast_that_reaches_no_node_ends_like_a_hot_sends_broadcast_again() =
        runFlow { db, runtime, _ ->
            val (signed, id) = startMoved(db, runtime)
            runtime.broadcastStore.applyDone(BroadcastDonePayload.Error(id, "broadcast failed after 60 attempts: tor connection closed"))
            val snap = runtime.broadcastStore.get(id)!!
            assertEquals("success", snap.phase)
            assertEquals(signed.txHex, db.sends.get(signed.txid)?.txHex)
            assertEquals(BROADCAST_VIA_TOR, db.broadcastVia.get(signed.txid))
            assertTrue(storedSendCanGoAgain(db, signed.txid))
            assertNull(sendStoredAgain(runtime, db, signed.txid, BroadcastReceipt(signed.txid, ADDRESS, "", 50_000, 141)))
            assertTrue(storedSendSession(runtime.broadcastStore, signed.txid)!!.snapshot.heldBefore)
        }

    /** Stores a legacy spend (its row moves to the signed txid) and starts its broadcast. */
    private suspend fun startMoved(
        db: Database,
        runtime: PeersRuntime,
    ): Pair<SignedPsbt, String> {
        storeUnsignedSend(db, runtime, unsignedRow).join()
        val signed = signedPsbt("ef".repeat(32))
        assertTrue(storeSignedPsbt(db, runtime, unsignedTxid, signed))
        runtime.walletTxsStore.update { it.copy(utxos = listOf(c), utxosReady = true) }
        val receipt = BroadcastReceipt(unsignedTxid, ADDRESS, "Rent", 50_000, 141)
        return signed to assertIs<SignedPsbtSent.Started>(startSignedPsbtBroadcast(runtime, db, signed, receipt)).id
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
            assertEquals("starting", session.snapshot.phase)
            assertEquals(signed.txHex, requests.single().txHex)
        }

    /** The review's Broadcast runs on the UI dispatcher, so storing and starting must read SQLite off it. */
    @Test
    fun storing_and_starting_read_no_sqlite_on_the_callers_dispatcher() {
        val db = createSqliteDatabase(":memory:")
        var ui: UiDispatcher? = null
        val spy = UiReadSpy(db) { ui }
        val runtime = PeersRuntime(spy.store)
        try {
            runBlocking {
                listenForRequests(runtime)
                storeUnsignedSend(spy.store, runtime, unsignedRow).join()
                runtime.walletTxsStore.update { it.copy(utxos = listOf(c), utxosReady = true) }
                val signed = signedPsbt(unsignedTxid)
                val receipt = BroadcastReceipt(unsignedTxid, ADDRESS, "Rent", 50_000, 141)
                val sent =
                    onUiDispatcher {
                        ui = it
                        broadcastSignedPsbt(spy.store, runtime, unsignedTxid, signed, receipt)
                    }
                assertIs<SignedPsbtSent.Started>(sent)
                assertEquals(emptyList(), spy.uiReads)
            }
        } finally {
            runtime.stop()
            db.close()
        }
    }

    /** The Tor record is written with the row, as a hot send's is; one that fails leaves the row saved but not started. */
    @Test
    fun a_failed_tor_write_after_storing_says_saved_but_not_started() =
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
            val refused = assertIs<SignedPsbtSent.Refused>(broadcastSignedPsbt(failingRoute, runtime, unsignedTxid, signed, receipt))
            assertEquals(SIGNED_PSBT_NOT_STARTED, refused.reason)
            assertTrue(refused.stored)
            assertEquals(signed.txHex, db.sends.get(signed.txid)?.txHex)
            assertTrue(requests.isEmpty())
        }

    /**
     * The signed row committed before the start threw, so the review must not say it couldn't be saved: it says
     * the start failed and, being stored, Back leaves to the signed txid.
     */
    @Test
    fun a_start_that_throws_after_storing_says_the_payment_was_saved() =
        runFlow { db, _, _ ->
            val failingRead =
                object : Database by db {
                    override val privateSends =
                        object : PrivateSendsRepository by db.privateSends {
                            override fun get(txid: String): PrivateSendRow? = error("disk I/O error")
                        }
                }
            val runtime = PeersRuntime(failingRead)
            try {
                storeUnsignedSend(db, runtime, unsignedRow).join()
                runtime.walletTxsStore.update { it.copy(utxos = listOf(c), utxosReady = true) }
                val signed = signedPsbt(unsignedTxid)
                val receipt = BroadcastReceipt(unsignedTxid, ADDRESS, "Rent", 50_000, 141)
                val refused = assertIs<SignedPsbtSent.Refused>(broadcastSignedPsbt(failingRead, runtime, unsignedTxid, signed, receipt))
                assertEquals(SIGNED_PSBT_NOT_STARTED, refused.reason)
                assertTrue(refused.stored)
                assertEquals(signed.txHex, db.sends.get(signed.txid)?.txHex)
            } finally {
                runtime.stop()
            }
        }

    /** The row committed, then every read fails: a failed read is not a rollback, so it still says it was saved. */
    @Test
    fun a_throw_after_the_row_commits_says_saved_even_when_its_status_read_fails() =
        runFlow { db, runtime, _ ->
            storeUnsignedSend(db, runtime, unsignedRow).join()
            val signed = signedPsbt(unsignedTxid)
            val receipt = BroadcastReceipt(unsignedTxid, ADDRESS, "Rent", 50_000, 141)
            var committed = false
            val failingReads =
                object : Database by db {
                    override val sends =
                        object : SendsRepository by db.sends {
                            override fun upsert(row: SendRow) {
                                db.sends.upsert(row)
                                committed = true
                            }

                            override fun get(txid: String): SendRow? = if (committed) error("disk I/O error") else db.sends.get(txid)
                        }
                }
            val refused = assertIs<SignedPsbtSent.Refused>(broadcastSignedPsbt(failingReads, runtime, unsignedTxid, signed, receipt))
            assertEquals(SIGNED_PSBT_NOT_STARTED, refused.reason)
            assertTrue(refused.stored)
            assertEquals(signed.txHex, db.sends.get(signed.txid)?.txHex)
        }

    /** Nothing was stored, so the review keeps the unsaved text and Back stays in the flow. */
    @Test
    fun a_store_that_throws_before_the_row_commits_says_it_was_not_saved() =
        runFlow { db, runtime, _ ->
            storeUnsignedSend(db, runtime, unsignedRow).join()
            val signed = signedPsbt(unsignedTxid)
            val receipt = BroadcastReceipt(unsignedTxid, ADDRESS, "Rent", 50_000, 141)
            val failingWrite =
                object : Database by db {
                    override val sends =
                        object : SendsRepository by db.sends {
                            override fun upsert(row: SendRow): Unit = error("disk full")
                        }
                }
            val refused = assertIs<SignedPsbtSent.Refused>(broadcastSignedPsbt(failingWrite, runtime, unsignedTxid, signed, receipt))
            assertEquals(SIGNED_PSBT_NOT_STORED, refused.reason)
            assertFalse(refused.stored)
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
