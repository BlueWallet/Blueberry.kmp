package io.bluewallet.blueberry

import io.bluewallet.bip324.OutPoint
import io.bluewallet.bip324.Transaction
import io.bluewallet.bip324.TxInput
import io.bluewallet.bip324.TxOutput
import io.bluewallet.bip324.bytesToHex
import io.bluewallet.bip324.encodeTransaction
import io.bluewallet.blueberry.bus.BroadcastDonePayload
import io.bluewallet.blueberry.storage.PrivateSendCoin
import io.bluewallet.blueberry.storage.SendRow
import io.bluewallet.blueberry.wallet.PsbtSendResult
import io.bluewallet.blueberry.wallet.hexToBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Coins another payment holds stay out of a new send, and a finished job keeps or frees them correctly. */
class SendHoldsTest {
    @Test
    fun send_refuses_coins_another_live_broadcast_holds() =
        runFlow { _, runtime, flow ->
            val requests = listenForRequests(runtime)
            val held = coin("11", 100_000_000)
            prime(flow, held)
            flow.build = ReviewBuild.Built(signed("aa"), flow.draftStamp())
            runtime.broadcastStore.start("other", "ff")
            runtime.armSendPersist(
                "other",
                SendRow(
                    txid = "cd".repeat(32),
                    txHex = "ff",
                    destination = ADDRESS,
                    coins = listOf(PrivateSendCoin(held.txid, held.vout, held.valueSats)),
                ),
            )
            flow.send()
            assertEquals(SELECTION_HELD, flow.sendError)
            assertNull(flow.sent)
            assertTrue(requests.isEmpty())
            assertTrue(flow.selectedKeys.isEmpty())
        }

    @Test
    fun nothing_commits_until_stored_holds_are_read() =
        runFlow { _, _, flow ->
            assertFalse(flow.heldReady)
            val c = coin("11", 100_000_000)
            flow.address = ADDRESS
            flow.label = "Rent"
            flow.amount = "0.0001"
            flow.feeRate = 1.0
            flow.snap = WalletTxsSnapshot(utxos = listOf(c))
            flow.toggleCoin(c.key)
            assertTrue(flow.selectedKeys.isEmpty())
            flow.selectedKeys = setOf(c.key)
            flow.continueFromAmount()
            assertEquals(SendStep.Address, flow.step)
            flow.refreshHeldOutpoints()
            assertTrue(flow.heldReady)
            flow.continueFromAmount()
            assertEquals(SendStep.Review, flow.step)
        }

    @Test
    fun a_hand_off_keeps_the_send_stored_even_if_the_job_fails() =
        runFlow { db, runtime, _ ->
            val held = coin("11", 100_000_000)
            val txid = "ab".repeat(32)
            runtime.armSendPersist(
                "1",
                SendRow(
                    txid = txid,
                    txHex = "aa",
                    destination = ADDRESS,
                    coins = listOf(PrivateSendCoin(held.txid, held.vout, held.valueSats)),
                ),
            )
            runtime.broadcastStore.start("1", "aa")
            runtime.broadcastStore.markHandedOff("1")
            runtime.persistArmedBroadcast("1", handedOff = true)
            assertEquals(txid, db.sends.get(txid)?.txid)
            assertEquals(setOf(held.key), runtime.reservedOutpoints())
            runtime.broadcastStore.applyDone(BroadcastDonePayload.Error("1", "peer rejected transaction"))
            runtime.persistArmedBroadcast("1")
            assertEquals("success", runtime.broadcastStore.get("1")?.phase)
            assertEquals(txid, db.sends.get(txid)?.txid)
            assertEquals(setOf(held.key), runtime.reservedOutpoints())
            runtime.broadcastStore.dismiss("1")
            assertEquals(setOf(held.key), runtime.reservedOutpoints())
        }

    @Test
    fun a_job_every_node_refused_drops_the_send_and_frees_its_coins() =
        runFlow { db, runtime, _ ->
            val held = coin("11", 100_000_000)
            val txid = "ab".repeat(32)
            runtime.armSendPersist(
                "1",
                SendRow(
                    txid = txid,
                    txHex = "aa",
                    destination = ADDRESS,
                    coins = listOf(PrivateSendCoin(held.txid, held.vout, held.valueSats)),
                ),
            )
            runtime.broadcastStore.start("1", "aa")
            runtime.broadcastStore.markHandedOff("1")
            runtime.persistArmedBroadcast("1", handedOff = true)
            assertEquals(txid, db.sends.get(txid)?.txid)
            runtime.broadcastStore.applyDone(BroadcastDonePayload.Error("1", "refused", refused = true))
            runtime.persistArmedBroadcast("1")
            val snap = runtime.broadcastStore.get("1")
            assertEquals("error", snap?.phase)
            assertTrue(broadcastCanBeForgotten(snap?.phase))
            assertNull(db.sends.get(txid))
            assertTrue(runtime.reservedOutpoints().isEmpty())
        }

    @Test
    fun a_hand_off_no_node_confirmed_is_sent_but_says_so() =
        runFlow { _, runtime, _ ->
            runtime.broadcastStore.start("1", "aa")
            runtime.broadcastStore.applyDone(BroadcastDonePayload.Ok("1", "peer", acknowledged = false))
            val status = broadcastStatus(runtime.broadcastStore.get("1")!!)
            assertEquals(BroadcastTone.Sent, status.tone)
            assertEquals("Given to Bitcoin nodes", status.line)
        }

    @Test
    fun send_refuses_while_a_stored_send_has_not_been_read_since_it_was_written() =
        runFlow { _, runtime, flow ->
            val requests = listenForRequests(runtime)
            val held = coin("11", 100_000_000)
            prime(flow, held)
            runtime.armSendPersist(
                "1",
                SendRow(
                    txid = "cd".repeat(32),
                    txHex = "ff",
                    destination = ADDRESS,
                    coins = listOf(PrivateSendCoin(held.txid, held.vout, held.valueSats)),
                ),
            )
            runtime.broadcastStore.start("1", "ff")
            runtime.broadcastStore.markHandedOff("1")
            runtime.persistArmedBroadcast("1", handedOff = true)
            runtime.broadcastStore.applyDone(BroadcastDonePayload.Ok("1", "peer"))
            runtime.persistArmedBroadcast("1")
            assertFalse(runtime.reservedOutpointsNow().complete)
            flow.build = ReviewBuild.Built(signed("aa"), flow.draftStamp())
            flow.send()
            assertEquals(HOLDS_LOADING, flow.sendError)
            assertNull(flow.sent)
            assertTrue(requests.isEmpty())
            withTimeout(5_000) {
                while (held.key !in flow.heldOutpoints) delay(10)
            }
            assertFalse(held.key in flow.selectedKeys)
            assertNull(flow.sendError)
        }

    @Test
    fun an_error_before_any_hand_off_drops_the_send() =
        runFlow { db, runtime, _ ->
            val held = coin("11", 100_000_000)
            val txid = "ab".repeat(32)
            runtime.armSendPersist(
                "1",
                SendRow(
                    txid = txid,
                    txHex = "aa",
                    destination = ADDRESS,
                    coins = listOf(PrivateSendCoin(held.txid, held.vout, held.valueSats)),
                ),
            )
            runtime.broadcastStore.start("1", "aa")
            runtime.broadcastStore.applyDone(BroadcastDonePayload.Error("1", "no peers accepted"))
            runtime.persistArmedBroadcast("1")
            assertNull(db.sends.get(txid))
            assertTrue(runtime.reservedOutpoints().isEmpty())
            runtime.broadcastStore.start("1", "aa")
            runtime.broadcastStore.markHandedOff("1")
            runtime.persistArmedBroadcast("1", handedOff = true)
            runtime.broadcastStore.applyDone(BroadcastDonePayload.Ok("1", "peer"))
            runtime.persistArmedBroadcast("1")
            runtime.broadcastStore.dismiss("1")
            assertEquals(txid, db.sends.get(txid)?.txid)
            assertEquals(setOf(held.key), runtime.reservedOutpoints())
        }

    @Test
    fun a_psbt_refuses_coins_another_payment_holds() =
        runFlow { _, runtime, flow ->
            val held = coin("11", 100_000_000)
            prime(flow, held)
            flow.build = ReviewBuild.Built(psbt("ee"), flow.draftStamp())
            runtime.psbtHolds.hold("cd".repeat(32), setOf(held.key))
            flow.send()
            assertEquals(SELECTION_HELD, flow.sendError)
            assertEquals(SendStep.Address, flow.step)
        }

    @Test
    fun a_psbt_on_screen_holds_its_coins_until_the_step_is_left() =
        runFlow { _, runtime, flow ->
            val c = coin("11", 100_000_000)
            prime(flow, c)
            val txid = "ee".repeat(32)
            flow.build = ReviewBuild.Built(psbt("ee"), flow.draftStamp())
            flow.send()
            assertEquals(SendStep.Psbt, flow.step)
            assertEquals(setOf(c.key), runtime.reservedOutpoints())
            assertTrue(runtime.reservedOutpoints(txid).isEmpty())
            flow.goBack()
            assertTrue(runtime.reservedOutpoints().isEmpty())
        }

    @Test
    fun send_again_ignores_its_own_stored_hold_but_not_another_payments() =
        runFlow { _, runtime, _ ->
            val c = coin("ab", 100_000_000)
            val txid = "cd".repeat(32)
            val hex = spending(c)
            runtime.armSendPersist(
                "1",
                SendRow(txid = txid, txHex = hex, destination = ADDRESS, coins = listOf(PrivateSendCoin(c.txid, c.vout, c.valueSats))),
            )
            runtime.broadcastStore.start("1", hex, receipt = BroadcastReceipt(txid, ADDRESS, "", 1_000, 141))
            runtime.broadcastStore.markHandedOff("1")
            runtime.persistArmedBroadcast("1", handedOff = true)
            runtime.broadcastStore.applyDone(BroadcastDonePayload.Ok("1", "1.1.1.1:8333", acknowledged = false))
            runtime.persistArmedBroadcast("1")
            val wallet = WalletTxsSnapshot(utxos = listOf(c), utxosReady = true)
            assertEquals(setOf(c.key), runtime.reservedOutpoints())
            assertNull(retryRefusalFor(runtime, "1", wallet))
            runtime.psbtHolds.hold("ef".repeat(32), setOf(c.key))
            assertEquals(RETRY_COINS_HELD, retryRefusalFor(runtime, "1", wallet))
        }

    @Test
    fun private_send_with_a_coin_taken_since_review_asks_to_review_the_selection() =
        runFlow { _, runtime, flow ->
            val kept = coin("11", 100_000_000)
            val taken = coin("22", 100_000_000)
            prime(flow, kept, taken)
            runtime.psbtHolds.hold("cd".repeat(32), setOf(taken.key))
            flow.refreshHeldOutpoints()
            flow.openPrivateSend()
            assertTrue(flow.selectionNeedsAck)
            assertEquals(SELECTION_HELD, flow.selectionNotice)
            assertEquals(setOf(kept.key), flow.selectedKeys)
            assertEquals(SendStep.Address, flow.step)
        }
}

private fun spending(coin: WalletUtxoRow): String =
    bytesToHex(
        encodeTransaction(
            Transaction(
                version = 1u,
                inputs = listOf(TxInput(OutPoint(hexToBytes(coin.txid).reversedArray(), 0u), ByteArray(0), 0xffffffffu)),
                outputs = listOf(TxOutput(value = 1000, scriptPubKey = byteArrayOf(0x51))),
                lockTime = 0u,
            ),
        ),
    )

private fun psbt(id: String) = PsbtSendResult(psbtHex = "00", txid = id.repeat(32), feeSats = 141, vsize = 141, changeSats = 0, changeVouts = emptyList())

private const val ADDRESS = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
