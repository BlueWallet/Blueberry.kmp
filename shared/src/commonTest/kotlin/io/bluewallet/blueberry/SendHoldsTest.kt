package io.bluewallet.blueberry

import io.bluewallet.blueberry.bus.BroadcastDonePayload
import io.bluewallet.blueberry.storage.PrivateSendCoin
import io.bluewallet.blueberry.storage.SendRow
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
}

private const val ADDRESS = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
