package io.bluewallet.blueberry

import io.bluewallet.blueberry.bus.BroadcastDonePayload
import io.bluewallet.blueberry.parse.SendBuildParams
import io.bluewallet.blueberry.parse.buildActiveSendTx
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.PrivateSendCoin
import io.bluewallet.blueberry.storage.PrivateSendRow
import io.bluewallet.blueberry.storage.SendRow
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.BuildSendResult
import io.bluewallet.blueberry.wallet.SendAmount
import io.bluewallet.blueberry.wallet.SignedSendResult
import io.bluewallet.blueberry.wallet.SyncFromDbResult
import io.bluewallet.blueberry.wallet.Wallet
import io.bluewallet.blueberry.wallet.WatchWallet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendFlowTest {
    @Test
    fun send_refuses_a_signature_for_a_different_fee() =
        runFlow { _, _, flow ->
            prime(flow, coin("11", 100_000_000))
            flow.build = ReviewBuild.Built(signed(), flow.draftStamp())
            flow.feeRate = 9.0
            flow.send()
            assertEquals(DRAFT_MISMATCH, flow.sendError)
            assertTrue(flow.build is ReviewBuild.Idle)
            assertEquals(SendStep.Address, flow.step)
            assertNull(flow.sent)
        }

    @Test
    fun a_second_send_keeps_the_broadcast_already_running() =
        runFlow { db, runtime, flow ->
            prime(flow, coin("11", 100_000_000))
            flow.build = ReviewBuild.Built(signed("aa"), flow.draftStamp())
            runtime.broadcastStore.start("busy", "ff")
            flow.send()
            assertEquals(SendStep.Broadcast, flow.step)
            assertEquals("aa", flow.sent?.signed?.txHex)
            assertEquals("ff", runtime.broadcastStore.get("busy")?.txHex)
            assertEquals(
                "aa",
                runtime.broadcastStore
                    .sessions()
                    .find { it.snapshot.txHex == "aa" }
                    ?.snapshot
                    ?.txHex,
            )
            assertEquals(1.0, loadSendFeeRate(db))
        }

    @Test
    fun close_on_the_broadcast_step_leaves_the_job_running() =
        runFlow { _, runtime, flow ->
            prime(flow, coin("11", 100_000_000))
            flow.build = ReviewBuild.Built(signed("aa"), flow.draftStamp())
            flow.send()
            assertEquals(SendStep.Broadcast, flow.step)
            val id = flow.broadcast.id
            flow.goBack()
            assertTrue(closed)
            assertEquals("starting", runtime.broadcastStore.get(id!!)?.phase)
            assertEquals("aa", runtime.broadcastStore.get(id)?.txHex)
        }

    @Test
    fun send_broadcasts_only_the_transaction_on_screen() =
        runFlow { db, runtime, flow ->
            prime(flow, coin("11", 100_000_000))
            val signed = signed("aa")
            flow.build = ReviewBuild.Built(signed, flow.draftStamp())
            flow.send()
            assertEquals(SendStep.Broadcast, flow.step)
            assertEquals(
                "aa",
                runtime.broadcastStore
                    .sessions()
                    .single { it.snapshot.txHex == "aa" }
                    .snapshot.txHex,
            )
            assertEquals("aa", flow.sent?.signed?.txHex)
            assertEquals(1.0, loadSendFeeRate(db))
        }

    @Test
    fun change_coins_back_returns_to_review() =
        runFlow { _, _, flow ->
            prime(flow, coin("11", 100_000_000))
            flow.continueFromAddress()
            flow.continueFromAmount()
            assertEquals(SendStep.Review, flow.step)
            flow.addCoins()
            assertEquals(SendStep.Coins, flow.step)
            assertTrue(flow.navForward)
            flow.goBack()
            assertEquals(SendStep.Review, flow.step)
            assertFalse(flow.navForward)
            flow.goBack()
            assertEquals(SendStep.Amount, flow.step)
        }

    @Test
    fun coins_opened_from_amount_go_back_to_amount() =
        runFlow { _, _, flow ->
            prime(flow, coin("11", 100_000_000))
            flow.selectedKeys = emptySet()
            flow.continueFromAddress()
            flow.continueFromAmount()
            assertEquals(SendStep.Coins, flow.step)
            flow.goBack()
            assertEquals(SendStep.Amount, flow.step)
            assertFalse(flow.navForward)
        }

    @Test
    fun a_finished_private_send_closes_instead_of_offering_the_same_coins() =
        runFlow { _, runtime, flow ->
            prime(flow, coin("11", 100_000_000))
            val signed = signed("aa")
            flow.build = ReviewBuild.Built(signed, flow.draftStamp())
            flow.privateSigned = signed
            flow.step = SendStep.PrivateSend
            runtime.broadcastStore.start("1", signed.txHex)
            runtime.broadcastStore.applyDone(BroadcastDonePayload.Ok("1", "peer"))
            flow.goBack()
            assertTrue(closed)
            assertTrue(flow.build is ReviewBuild.Idle)
            assertNull(runtime.broadcastStore.get("1"))
        }

    @Test
    fun back_from_a_live_private_send_closes_and_leaves_the_job() =
        runFlow { _, runtime, flow ->
            prime(flow, coin("11", 100_000_000))
            val signed = signed("aa")
            flow.build = ReviewBuild.Built(signed, flow.draftStamp())
            flow.privateSigned = signed
            flow.step = SendStep.PrivateSend
            runtime.broadcastStore.start("1", signed.txHex)
            flow.goBack()
            assertTrue(closed)
            assertTrue(flow.build is ReviewBuild.Idle)
            assertEquals("starting", runtime.broadcastStore.get("1")?.phase)
            assertEquals(signed.txHex, runtime.broadcastStore.get("1")?.txHex)
        }

    @Test
    fun applying_the_fee_already_showing_keeps_the_signed_transaction() =
        runFlow { _, _, flow ->
            prime(flow, coin("11", 100_000_000))
            val built = ReviewBuild.Built(signed(), flow.draftStamp())
            flow.build = built
            flow.applyFee(1.0)
            assertTrue(flow.build is ReviewBuild.Built)
        }

    @Test
    fun an_in_flight_send_keeps_its_coins_out_of_the_next_picker() =
        runFlow { db, runtime, flow ->
            val live = coin("11", 100_000_000)
            val resting = coin("22", 100_000_000)
            runtime.broadcastStore.start("job", "aa")
            runtime.armSendPersist(
                "job",
                SendRow(
                    txid = "ab".repeat(32),
                    txHex = "aa",
                    destination = ADDRESS,
                    coins = listOf(PrivateSendCoin(live.txid, live.vout, live.valueSats)),
                ),
            )
            db.sends.upsert(
                SendRow(
                    txid = "cd".repeat(32),
                    txHex = "bb",
                    destination = ADDRESS,
                    coins = listOf(PrivateSendCoin(resting.txid, resting.vout, resting.valueSats)),
                ),
            )
            prime(flow, live, resting)
            flow.updateSnap(flow.snap)
            assertTrue(flow.spendableRows.isEmpty())
            assertTrue(flow.selectionNeedsAck)
        }

    @Test
    fun a_shrunk_selection_blocks_send_until_the_new_set_is_confirmed() =
        runFlow { _, _, flow ->
            val kept = coin("11", 50_000_000)
            val gone = coin("22", 50_000_000)
            prime(flow, kept, gone)
            flow.continueFromAddress()
            flow.continueFromAmount()
            flow.build = ReviewBuild.Built(signed(), flow.draftStamp())
            flow.updateSnap(WalletTxsSnapshot(utxos = listOf(kept)))
            assertEquals(setOf(kept.key), flow.selectedKeys)
            assertTrue(flow.selectionNeedsAck)
            assertTrue(flow.build is ReviewBuild.Idle)
            flow.send()
            assertNull(flow.sent)
            flow.addCoins()
            flow.continueFromCoins()
            assertEquals(SendStep.Review, flow.step)
            assertFalse(flow.navForward)
            assertFalse(flow.selectionNeedsAck)
        }

    @Test
    fun rebuild_retries_once_the_wallet_exists_and_keeps_the_draft() =
        runFlow { _, runtime, flow ->
            prime(flow, coin("11", 100_000_000))
            flow.rebuild()
            val failed = flow.build as ReviewBuild.Failed
            assertEquals(WALLET_NOT_LOADED, failed.message)
            val revision = runtime.walletRevision
            runtime.installWallet(IdleWallet())
            assertTrue(runtime.walletRevision > revision)
            flow.buildTx = { _, _, _ -> signed() }
            flow.rebuild()
            val built = flow.build as ReviewBuild.Built
            assertEquals(flow.draftStamp(), built.draft)
        }

    @Test
    fun a_superseded_build_cannot_publish() =
        runFlow { _, runtime, flow ->
            runtime.installWallet(IdleWallet())
            prime(flow, coin("11", 100_000_000))
            val gate = CompletableDeferred<BuildSendResult>()
            flow.buildTx = { _, _, _ -> gate.await() }
            coroutineScope {
                val job = launch { flow.rebuild() }
                while (flow.build !is ReviewBuild.Building) yield()
                flow.applyFee(2.0)
                gate.complete(signed())
                job.join()
            }
            assertTrue(flow.build is ReviewBuild.Idle)
        }

    @Test
    fun only_one_build_runs_at_a_time() =
        runFlow { _, runtime, flow ->
            runtime.installWallet(IdleWallet())
            prime(flow, coin("11", 100_000_000))
            var current = 0
            var peak = 0
            flow.buildTx = { _, _, _ ->
                current++
                if (current > peak) peak = current
                delay(30)
                current--
                signed()
            }
            coroutineScope {
                val first = launch { flow.rebuild() }
                val second = launch { flow.rebuild() }
                first.join()
                second.join()
            }
            assertEquals(1, peak)
            assertTrue(flow.build is ReviewBuild.Built)
        }

    @Test
    fun forgetting_a_cancelled_broadcast_releases_its_coins() =
        runFlow { _, runtime, flow ->
            val held = coin("11", 100_000_000)
            prime(flow, held)
            runtime.armSendPersist(
                "1",
                SendRow(
                    txid = "ab".repeat(32),
                    txHex = "aa",
                    destination = ADDRESS,
                    coins = listOf(PrivateSendCoin(held.txid, held.vout, held.valueSats)),
                ),
            )
            runtime.broadcastStore.start("1", "aa")
            flow.updateSnap(flow.snap)
            assertTrue(flow.spendableRows.isEmpty())
            runtime.broadcastStore.applyDone(BroadcastDonePayload.Error("1", "cancelled"))
            flow.broadcast = checkNotNull(runtime.broadcastStore.get("1"))
            flow.updateSnap(flow.snap)
            assertEquals(listOf(held.key), flow.spendableRows.map { it.key })
            flow.forgetFailed()
            assertTrue(closed)
            assertNull(runtime.broadcastStore.get("1"))
        }

    @Test
    fun a_failed_broadcast_releases_its_coins_and_stays_for_retry() =
        runFlow { _, runtime, flow ->
            val held = coin("11", 100_000_000)
            prime(flow, held)
            runtime.armSendPersist(
                "1",
                SendRow(
                    txid = "ab".repeat(32),
                    txHex = "aa",
                    destination = ADDRESS,
                    coins = listOf(PrivateSendCoin(held.txid, held.vout, held.valueSats)),
                ),
            )
            runtime.broadcastStore.start("1", "aa")
            runtime.broadcastStore.applyDone(BroadcastDonePayload.Error("1", "no peers accepted"))
            flow.broadcast = checkNotNull(runtime.broadcastStore.get("1"))
            flow.updateSnap(flow.snap)
            assertFalse(closed)
            assertEquals("error", runtime.broadcastStore.get("1")?.phase)
            assertTrue(runtime.reservedOutpoints().isEmpty())
            assertEquals(listOf(held.key), flow.spendableRows.map { it.key })
            flow.forgetFailed()
            assertTrue(closed)
            assertNull(runtime.broadcastStore.get("1"))
        }

    @Test
    fun a_failed_private_send_drops_the_signature() =
        runFlow { _, runtime, flow ->
            val held = coin("11", 100_000_000)
            prime(flow, held)
            val signed = signed()
            flow.build = ReviewBuild.Built(signed, flow.draftStamp())
            flow.privateSigned = signed
            flow.step = SendStep.PrivateSend
            runtime.armPrivateSendPersist(
                "1",
                PrivateSendRow(
                    txid = signed.txid,
                    partner = "rocketx",
                    orderId = "order",
                    txHex = signed.txHex,
                    destination = ADDRESS,
                    refundAddress = ADDRESS,
                    coins = listOf(PrivateSendCoin(held.txid, held.vout, held.valueSats)),
                ),
            )
            runtime.broadcastStore.start("1", signed.txHex)
            runtime.broadcastStore.applyDone(BroadcastDonePayload.Error("1", "no peers accepted"))
            flow.goBack()
            assertTrue(flow.build is ReviewBuild.Idle)
            assertEquals(setOf(held.key), flow.selectedKeys)
            assertFalse(flow.selectionNeedsAck)
            assertTrue(runtime.reservedOutpoints().isEmpty())
        }

    @Test
    fun a_failed_private_send_asks_again_when_a_coin_leaves() =
        runFlow { _, runtime, flow ->
            val held = coin("11", 100_000_000)
            val gone = coin("22", 100_000_000)
            prime(flow, held, gone)
            val signed = signed()
            flow.build = ReviewBuild.Built(signed, flow.draftStamp())
            flow.privateSigned = signed
            flow.step = SendStep.PrivateSend
            runtime.broadcastStore.start("other", "bb")
            runtime.armSendPersist(
                "other",
                SendRow(
                    txid = "cd".repeat(32),
                    txHex = "bb",
                    destination = ADDRESS,
                    coins = listOf(PrivateSendCoin(gone.txid, gone.vout, gone.valueSats)),
                ),
            )
            runtime.broadcastStore.start("1", signed.txHex)
            runtime.broadcastStore.applyDone(BroadcastDonePayload.Error("1", "no peers accepted"))
            flow.goBack()
            assertEquals(setOf(held.key), flow.selectedKeys)
            assertTrue(flow.selectionNeedsAck)
            assertTrue(flow.build is ReviewBuild.Idle)
        }

    @Test
    fun a_reservation_on_review_drops_the_signature() =
        runFlow { _, runtime, flow ->
            val held = coin("11", 100_000_000)
            val gone = coin("22", 100_000_000)
            prime(flow, held, gone)
            flow.build = ReviewBuild.Built(signed(), flow.draftStamp())
            runtime.broadcastStore.start("job", "bb")
            runtime.armSendPersist(
                "job",
                SendRow(
                    txid = "cd".repeat(32),
                    txHex = "bb",
                    destination = ADDRESS,
                    coins = listOf(PrivateSendCoin(gone.txid, gone.vout, gone.valueSats)),
                ),
            )
            flow.applyHeldOutpoints(runtime.reservedOutpoints())
            assertEquals(setOf(held.key), flow.selectedKeys)
            assertTrue(flow.selectionNeedsAck)
            assertTrue(flow.build is ReviewBuild.Idle)
        }

    @Test
    fun forgetting_an_unconfirmed_send_releases_its_coins() =
        runFlow { db, runtime, flow ->
            val held = coin("11", 100_000_000)
            val txid = "cd".repeat(32)
            prime(flow, held)
            db.sends.upsert(
                SendRow(
                    txid = txid,
                    txHex = "bb",
                    destination = ADDRESS,
                    coins = listOf(PrivateSendCoin(held.txid, held.vout, held.valueSats)),
                ),
            )
            db.sends.setConfirmedInBlock(txid, 0)
            flow.updateSnap(flow.snap)
            assertTrue(flow.spendableRows.isEmpty())
            assertTrue(abandonUnconfirmedSend(db, runtime, txid))
            flow.updateSnap(flow.snap)
            assertNull(db.sends.get(txid))
            assertEquals(listOf(held.key), flow.spendableRows.map { it.key })
            db.sends.upsert(
                SendRow(
                    txid = txid,
                    txHex = "bb",
                    destination = ADDRESS,
                    coins = listOf(PrivateSendCoin(held.txid, held.vout, held.valueSats)),
                    confirmedInBlock = 10,
                ),
            )
            assertFalse(abandonUnconfirmedSend(db, runtime, txid))
            assertEquals(txid, db.sends.get(txid)?.txid)
        }

    @Test
    fun a_dropped_build_does_not_touch_the_wallet() =
        runFlow { db, _, _ ->
            assertFailsWith<CancellationException> {
                buildActiveSendTx(
                    db,
                    IdleWallet(),
                    SendBuildParams(emptyList(), ADDRESS, SendAmount.Exact(1), 1.0),
                    accept = { false },
                )
            }
        }
}

private const val ADDRESS = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"

private class CloseGate {
    var closed = false
}

private fun runFlow(body: suspend CloseGate.(Database, PeersRuntime, SendFlow) -> Unit) {
    val db = createSqliteDatabase(":memory:")
    val runtime = PeersRuntime(db)
    val gate = CloseGate()
    try {
        runBlocking {
            val flow = SendFlow(runtime, db, this, close = { gate.closed = true })
            gate.body(db, runtime, flow)
        }
    } finally {
        runtime.stop()
        db.close()
    }
}

private fun prime(
    flow: SendFlow,
    vararg coins: WalletUtxoRow,
) {
    flow.address = ADDRESS
    flow.label = "Rent"
    flow.amount = "0.0001"
    flow.feeRate = 1.0
    flow.snap = WalletTxsSnapshot(utxos = coins.toList())
    flow.selectedKeys = coins.map { it.key }.toSet()
}

private fun coin(
    id: String,
    sats: Long,
): WalletUtxoRow {
    val txid = id.repeat(32)
    return WalletUtxoRow(
        key = "$txid:0",
        txid = txid,
        vout = 0,
        outpointShort = "",
        valueSats = sats,
        scriptPubKey = ByteArray(22),
        amountLabel = "",
        height = 1,
        ageLabel = "",
        valueBar = "",
        name = null,
        isChange = false,
    )
}

private fun signed(hex: String = "aa") = SignedSendResult(hex, "ab".repeat(32), 100, 110, 0, emptyList())

private class IdleWallet : Wallet {
    override fun snapshot(): WatchWallet = error("unused")

    override fun scripts(): List<ByteArray> = error("unused")

    override fun gaps() = error("unused")

    override fun peekGaps() = error("unused")

    override fun refresh(): WatchWallet = error("unused")

    override fun syncFromDb(): SyncFromDbResult = error("unused")
}
