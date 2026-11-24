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

    /** No run of ours has given nodes this transaction yet, so the job is a first run and claims nothing at start. */
    @Test
    fun the_broadcast_starts_as_a_first_run_and_the_payment_can_go_again() =
        runFlow { db, runtime, _ ->
            val requests = listenForRequests(runtime)
            val (signed, id) = startMoved(db, runtime)
            val session = runtime.broadcastStore.sessions().single { it.snapshot.id == id }
            assertFalse(session.snapshot.heldBefore)
            assertTrue(broadcastCanBeStopped(session.snapshot))
            assertEquals(signed.txid, session.receipt?.txid)
            assertEquals(signed.txHex, requests.single().txHex)
            assertTrue(storedSendCanGoAgain(db, signed.txid))
            assertNull(db.broadcastVia.get(signed.txid))
            assertNull(db.broadcastVia.get(unsignedTxid))
        }

    /** Device QA: offline, every Tor dial failed and no node got it. It must not read as Sent or record Tor. */
    @Test
    fun a_broadcast_that_reaches_no_node_is_not_sent_and_records_nothing() =
        runFlow { db, runtime, _ ->
            val (signed, id) = startMoved(db, runtime)
            runtime.broadcastStore.applyDone(BroadcastDonePayload.Error(id, "broadcast failed after 60 attempts: tor connection closed"))
            recordStoredSendBroadcast(db, runtime.broadcastStore, id)
            val snap = runtime.broadcastStore.get(id)!!
            assertEquals("error", snap.phase)
            assertEquals("Not sent yet", broadcastStatus(snap).title)
            assertNull(db.broadcastVia.get(signed.txid))
            assertTrue(storedSendCanGoAgain(db, signed.txid), "the payment stays and can go again")
            assertFalse(broadcastJobCanBeForgotten(runtime, id), "only its details forget the stored payment")
            assertEquals(signed.txHex, db.sends.get(signed.txid)?.txHex)
        }

    @Test
    fun a_broadcast_every_node_refuses_is_not_sent_and_records_nothing() =
        runFlow { db, runtime, _ ->
            val (signed, id) = startMoved(db, runtime)
            runtime.broadcastStore.markHandedOff(id)
            runtime.broadcastStore.applyDone(BroadcastDonePayload.Error(id, BROADCAST_REFUSED, refused = true))
            recordStoredSendBroadcast(db, runtime.broadcastStore, id)
            assertEquals("Not sent yet", broadcastStatus(runtime.broadcastStore.get(id)!!).title)
            assertNull(db.broadcastVia.get(signed.txid))
        }

    @Test
    fun a_broadcast_a_node_holds_records_tor_on_the_signed_txid() =
        runFlow { db, runtime, _ ->
            val (signed, id) = startMoved(db, runtime)
            runtime.broadcastStore.markHandedOff(id)
            runtime.broadcastStore.applyDone(BroadcastDonePayload.Ok(id, "1.1.1.1:8333", acknowledged = false))
            recordStoredSendBroadcast(db, runtime.broadcastStore, id)
            assertEquals("Sent", broadcastStatus(runtime.broadcastStore.get(id)!!).title)
            assertEquals(BROADCAST_VIA_TOR, db.broadcastVia.get(signed.txid))
            assertNull(db.broadcastVia.get(unsignedTxid))
        }

    /**
     * A hot-wallet send of the same transaction wrote Tor at hand-off, then every node refused it and its row was
     * dropped as unsent. Its Tor note must go with it, or the next run of that txid starts held and reads as Sent.
     */
    @Test
    fun a_tor_note_from_a_send_every_node_refused_does_not_make_the_next_run_held() =
        runFlow { db, runtime, _ ->
            val txid = "ef".repeat(32)
            val hex = spending(signed = true)
            runtime.armSendPersist("hot", SendRow(txid, hex, ADDRESS, listOf(PrivateSendCoin(c.txid, c.vout, c.valueSats))))
            runtime.broadcastStore.start("hot", hex)
            runtime.broadcastStore.markHandedOff("hot")
            runtime.persistArmedBroadcast("hot", handedOff = true)
            assertEquals(BROADCAST_VIA_TOR, db.broadcastVia.get(txid))
            runtime.broadcastStore.applyDone(BroadcastDonePayload.Error("hot", BROADCAST_REFUSED, refused = true))
            runtime.persistArmedBroadcast("hot")
            runtime.broadcastStore.dismiss("hot")
            assertNull(db.sends.get(txid))
            assertNull(db.broadcastVia.get(txid))
            val (_, id) = startMoved(db, runtime)
            assertFalse(runtime.broadcastStore.get(id)!!.heldBefore)
        }

    /** The Tor note is the only durable proof a node holds it, so a write that fails once is tried again. */
    @Test
    fun a_tor_note_that_fails_to_write_once_is_written_on_retry() =
        runFlow { db, runtime, _ ->
            val (signed, id) = startMoved(db, runtime)
            runtime.broadcastStore.markHandedOff(id)
            runtime.broadcastStore.applyDone(BroadcastDonePayload.Ok(id, "1.1.1.1:8333", acknowledged = false))
            var failures = 1
            val flaky =
                object : Database by db {
                    override val broadcastVia =
                        object : BroadcastViaRepository by db.broadcastVia {
                            override fun upsert(
                                txid: String,
                                via: String,
                            ) {
                                if (failures-- > 0) error("database is locked")
                                db.broadcastVia.upsert(txid, via)
                            }
                        }
                }
            recordStoredSendBroadcast(flaky, runtime.broadcastStore, id)
            assertEquals(BROADCAST_VIA_TOR, db.broadcastVia.get(signed.txid))
        }

    /**
     * A Tor note that still fails after its retries is not dropped: the runtime keeps it due and writes it on the
     * next wallet refresh, since it's the only proof that survives a restart that a node holds the payment.
     */
    @Test
    fun a_tor_note_that_keeps_failing_is_written_on_the_next_wallet_refresh() =
        runFlow { db, _, _ ->
            var broken = true
            val flaky =
                object : Database by db {
                    override val broadcastVia =
                        object : BroadcastViaRepository by db.broadcastVia {
                            override fun upsert(
                                txid: String,
                                via: String,
                            ) {
                                if (broken) error("database is locked")
                                db.broadcastVia.upsert(txid, via)
                            }
                        }
                }
            val runtime = PeersRuntime(flaky)
            try {
                val (signed, id) = startMoved(db, runtime)
                runtime.broadcastStore.markHandedOff(id)
                runtime.broadcastStore.applyDone(BroadcastDonePayload.Ok(id, "1.1.1.1:8333", acknowledged = true))
                runtime.noteStoredSendBroadcast(id)
                assertNull(db.broadcastVia.get(signed.txid))
                broken = false
                runtime.bus.emit(Event.WalletTxs, WalletTxsPayload(at = 1))
                assertEquals(BROADCAST_VIA_TOR, db.broadcastVia.get(signed.txid))
            } finally {
                runtime.stop()
            }
        }

    /**
     * The app can die while a node already holds the raw transaction, so the Tor note is written at hand-off, as a
     * hot-wallet send's is. After a restart details then say it was given to nodes and offer no Forget.
     */
    @Test
    fun a_signed_psbt_hand_off_records_tor_before_the_run_ends() =
        runFlow { db, runtime, _ ->
            listenForRequests(runtime)
            val (signed, id) = startMoved(db, runtime)
            runtime.broadcastStore.markHandedOff(id)
            runtime.broadcastHandedOff(id)
            assertEquals("starting", runtime.broadcastStore.get(id)?.phase)
            assertEquals(BROADCAST_VIA_TOR, db.broadcastVia.get(signed.txid))
            assertTrue(openSendPublished(db.broadcastVia.get(signed.txid) != null, null))
        }

    /** A first run every node refused drops the note its hand-off wrote, keeping the row, so the next run isn't held. */
    @Test
    fun a_hand_off_note_goes_when_every_node_refuses_a_first_run() =
        runFlow { db, runtime, _ ->
            listenForRequests(runtime)
            val (signed, id) = startMoved(db, runtime)
            runtime.broadcastStore.markHandedOff(id)
            runtime.broadcastHandedOff(id)
            runtime.broadcastStore.applyDone(BroadcastDonePayload.Error(id, BROADCAST_REFUSED, refused = true))
            runtime.broadcastDone(id)
            assertEquals("Not sent yet", broadcastStatus(runtime.broadcastStore.get(id)!!).title)
            assertNull(db.broadcastVia.get(signed.txid))
            assertEquals(signed.txHex, db.sends.get(signed.txid)?.txHex)
            val receipt = BroadcastReceipt(signed.txid, ADDRESS, "Rent", 50_000, 141)
            assertNull(sendStoredAgain(runtime, db, signed.txid, receipt))
            assertFalse(storedSendSession(runtime.broadcastStore, signed.txid)!!.snapshot.heldBefore)
        }

    /**
     * A refused first run whose note delete fails leaves the row in SQL. Broadcast again must still start as a first
     * run, and details must not say nodes were given it, or a run that then reaches no node would read as Sent.
     */
    @Test
    fun a_refused_first_run_whose_note_delete_fails_does_not_make_the_next_run_held() {
        val db = createSqliteDatabase(":memory:")
        val noDeletes =
            object : Database by db {
                override val broadcastVia =
                    object : BroadcastViaRepository by db.broadcastVia {
                        override fun delete(txid: String): Unit = error("database is locked")
                    }
            }
        val runtime = PeersRuntime(noDeletes)
        try {
            runBlocking {
                listenForRequests(runtime)
                val (signed, id) = startMoved(noDeletes, runtime)
                runtime.broadcastStore.markHandedOff(id)
                runtime.broadcastHandedOff(id)
                runtime.broadcastStore.applyDone(BroadcastDonePayload.Error(id, BROADCAST_REFUSED, refused = true))
                runtime.broadcastDone(id)
                assertEquals(BROADCAST_VIA_TOR, db.broadcastVia.get(signed.txid))
                assertFalse(openSendPublished(runtime.dueTorNotes.recorded(signed.txid), runtime.broadcastStore.get(id)))
                val receipt = BroadcastReceipt(signed.txid, ADDRESS, "Rent", 50_000, 141)
                assertNull(sendStoredAgain(runtime, noDeletes, signed.txid, receipt))
                assertFalse(storedSendSession(runtime.broadcastStore, signed.txid)!!.snapshot.heldBefore)
            }
        } finally {
            runtime.stop()
            db.close()
        }
    }

    /** A note from an earlier run that a node held stays when a later run is refused: that run started held. */
    @Test
    fun an_earlier_runs_note_stays_when_a_later_run_is_refused() =
        runFlow { db, runtime, _ ->
            listenForRequests(runtime)
            val (signed, id) = startMoved(db, runtime)
            runtime.broadcastStore.markHandedOff(id)
            runtime.broadcastHandedOff(id)
            runtime.broadcastStore.applyDone(BroadcastDonePayload.Ok(id, "1.1.1.1:8333", acknowledged = false))
            runtime.broadcastDone(id)
            val receipt = BroadcastReceipt(signed.txid, ADDRESS, "Rent", 50_000, 141)
            assertNull(sendStoredAgain(runtime, db, signed.txid, receipt))
            val again = storedSendSession(runtime.broadcastStore, signed.txid)!!.snapshot.id!!
            runtime.broadcastStore.applyDone(BroadcastDonePayload.Error(again, BROADCAST_REFUSED, refused = true))
            runtime.broadcastDone(again)
            assertEquals(BROADCAST_VIA_TOR, db.broadcastVia.get(signed.txid))
        }

    /** A hand-off note that fails to write stays due; if every node then refuses, it leaves the due set unwritten. */
    @Test
    fun a_failed_hand_off_note_stays_due_until_written_or_refused() =
        runFlow { db, _, _ ->
            var broken = true
            val flaky =
                object : Database by db {
                    override val broadcastVia =
                        object : BroadcastViaRepository by db.broadcastVia {
                            override fun upsert(
                                txid: String,
                                via: String,
                            ) {
                                if (broken) error("database is locked")
                                db.broadcastVia.upsert(txid, via)
                            }
                        }
                }
            val runtime = PeersRuntime(flaky)
            try {
                listenForRequests(runtime)
                val (signed, id) = startMoved(db, runtime)
                runtime.broadcastStore.markHandedOff(id)
                runtime.broadcastHandedOff(id)
                assertNull(db.broadcastVia.get(signed.txid))
                runtime.broadcastStore.applyDone(BroadcastDonePayload.Error(id, BROADCAST_REFUSED, refused = true))
                runtime.broadcastDone(id)
                broken = false
                runtime.bus.emit(Event.WalletTxs, WalletTxsPayload(at = 1))
                assertNull(db.broadcastVia.get(signed.txid), "a refused run's note is no longer due")
                broken = true
                val receipt = BroadcastReceipt(signed.txid, ADDRESS, "Rent", 50_000, 141)
                assertNull(sendStoredAgain(runtime, db, signed.txid, receipt))
                val again = storedSendSession(runtime.broadcastStore, signed.txid)!!.snapshot.id!!
                runtime.broadcastStore.markHandedOff(again)
                runtime.broadcastHandedOff(again)
                broken = false
                runtime.bus.emit(Event.WalletTxs, WalletTxsPayload(at = 2))
                assertEquals(BROADCAST_VIA_TOR, db.broadcastVia.get(signed.txid), "a held run's note is written on refresh")
            } finally {
                runtime.stop()
            }
        }

    /**
     * Persisting a hot-wallet send can throw after its row is written (here its own Tor write fails). The done hook
     * still makes the note due, so the next wallet refresh writes it.
     */
    @Test
    fun the_done_hook_keeps_the_tor_note_due_when_persisting_the_send_throws() =
        runFlow { db, _, _ ->
            var broken = true
            val flaky =
                object : Database by db {
                    override val broadcastVia =
                        object : BroadcastViaRepository by db.broadcastVia {
                            override fun upsert(
                                txid: String,
                                via: String,
                            ) {
                                if (broken) error("database is locked")
                                db.broadcastVia.upsert(txid, via)
                            }
                        }
                }
            val runtime = PeersRuntime(flaky)
            try {
                val txid = "ef".repeat(32)
                val hex = spending(signed = true)
                runtime.armSendPersist("hot", SendRow(txid, hex, ADDRESS, listOf(PrivateSendCoin(c.txid, c.vout, c.valueSats))))
                runtime.broadcastStore.start("hot", hex, receipt = BroadcastReceipt(txid, ADDRESS, "Rent", 50_000, 141))
                runtime.broadcastStore.markHandedOff("hot")
                runtime.broadcastStore.applyDone(BroadcastDonePayload.Ok("hot", "1.1.1.1:8333", acknowledged = true))
                assertTrue(runCatching { runtime.broadcastDone("hot") }.isFailure)
                assertEquals(hex, db.sends.get(txid)?.txHex)
                broken = false
                runtime.bus.emit(Event.WalletTxs, WalletTxsPayload(at = 1))
                assertEquals(BROADCAST_VIA_TOR, db.broadcastVia.get(txid))
            } finally {
                runtime.stop()
            }
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

    /** Starting never depends on the Tor record, so a database that can't write it still reports a started payment. */
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

    /**
     * The signed row committed before the start threw, so the review must not say it couldn't be saved: it says
     * the start failed and, being stored, Back leaves to the signed txid.
     */
    @Test
    fun a_start_that_throws_after_storing_says_the_payment_was_saved() =
        runFlow { db, runtime, _ ->
            storeUnsignedSend(db, runtime, unsignedRow).join()
            runtime.walletTxsStore.update { it.copy(utxos = listOf(c), utxosReady = true) }
            val signed = signedPsbt(unsignedTxid)
            val receipt = BroadcastReceipt(unsignedTxid, ADDRESS, "Rent", 50_000, 141)
            val failingRead =
                object : Database by db {
                    override val broadcastVia =
                        object : BroadcastViaRepository by db.broadcastVia {
                            override fun get(txid: String): String? = error("disk I/O error")
                        }
                }
            val refused = assertIs<SignedPsbtSent.Refused>(broadcastSignedPsbt(failingRead, runtime, unsignedTxid, signed, receipt))
            assertEquals(SIGNED_PSBT_NOT_STARTED, refused.reason)
            assertTrue(refused.stored)
            assertEquals(signed.txHex, db.sends.get(signed.txid)?.txHex)
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
