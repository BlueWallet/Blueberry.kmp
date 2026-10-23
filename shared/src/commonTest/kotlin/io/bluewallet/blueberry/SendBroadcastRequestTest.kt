package io.bluewallet.blueberry

import io.bluewallet.blueberry.broadcast.BROADCAST_CANCELLED
import io.bluewallet.blueberry.bus.BroadcastHandedOffPayload
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.storage.PrivateSendCoin
import io.bluewallet.blueberry.storage.PrivateSendRow
import io.bluewallet.blueberry.storage.SendRow
import io.bluewallet.blueberry.wallet.SendAmount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendBroadcastRequestTest {
    @Test
    fun a_request_nobody_takes_fails_and_frees_the_coins() =
        runFlow { _, runtime, flow ->
            prime(flow, coin("11", 100_000_000))
            flow.build = ReviewBuild.Built(signed("aa"), flow.draftStamp())
            flow.send()
            assertEquals(SendStep.Broadcast, flow.step)
            assertEquals("error", flow.broadcast.phase)
            assertEquals(BROADCAST_NOT_READY, flow.broadcast.error)
            assertTrue(broadcastCanBeForgotten(flow.broadcast.phase))
            assertTrue(runtime.reservedOutpoints().isEmpty())
        }

    @Test
    fun stop_before_a_hand_off_asks_the_module_to_stop() =
        runFlow { _, runtime, flow ->
            listenForRequests(runtime)
            val stops = mutableListOf<String>()
            runtime.bus.on(Event.BroadcastStop) { stops += it.id }
            prime(flow, coin("11", 100_000_000))
            flow.build = ReviewBuild.Built(signed("aa"), flow.draftStamp())
            flow.send()
            val id = checkNotNull(flow.broadcast.id)
            assertTrue(broadcastCanBeStopped(flow.broadcast))
            flow.stopSending()
            assertEquals(listOf(id), stops)
        }

    @Test
    fun stop_with_no_module_fails_the_job_and_frees_the_coins() =
        runFlow { _, runtime, flow ->
            listenForRequests(runtime)
            prime(flow, coin("11", 100_000_000))
            flow.build = ReviewBuild.Built(signed("aa"), flow.draftStamp())
            flow.send()
            flow.stopSending()
            assertEquals("error", flow.broadcast.phase)
            assertEquals(BROADCAST_CANCELLED, flow.broadcast.error)
            assertTrue(runtime.reservedOutpoints().isEmpty())
        }

    @Test
    fun stop_is_gone_once_a_node_has_it() =
        runFlow { _, runtime, flow ->
            listenForRequests(runtime)
            prime(flow, coin("11", 100_000_000))
            flow.build = ReviewBuild.Built(signed("aa"), flow.draftStamp())
            flow.send()
            val id = checkNotNull(flow.broadcast.id)
            val unbind = bindBroadcastEvents(runtime.bus, runtime.broadcastStore)
            runtime.bus.emit(Event.BroadcastHandedOff, BroadcastHandedOffPayload(id))
            unbind()
            flow.refreshBroadcast()
            assertTrue(flow.broadcast.handedOff)
            assertFalse(broadcastCanBeStopped(flow.broadcast))
            flow.stopSending()
            assertEquals("starting", flow.broadcast.phase)
        }

    @Test
    fun a_second_private_broadcast_tap_follows_the_running_job() =
        runFlow { _, runtime, flow ->
            val requests = listenForRequests(runtime)
            val held = coin("11", 100_000_000)
            prime(flow, held)
            val signed = signed()
            flow.step = SendStep.PrivateSend
            flow.privateSession =
                PrivateSendSession(flow.address, "", 1.0, SendAmount.Exact(10_000), emptyList(), flow.address)
            val row =
                PrivateSendRow(
                    txid = signed.txid,
                    partner = "rocketx",
                    orderId = "order",
                    txHex = signed.txHex,
                    destination = flow.address,
                    refundAddress = flow.address,
                    coins = listOf(PrivateSendCoin(held.txid, held.vout, held.valueSats)),
                )
            flow.privateBroadcast(signed, row)
            flow.privateBroadcast(signed, row)
            assertNull(flow.sendError)
            assertEquals(1, requests.size)
            assertEquals(requests.single().id, flow.broadcast.id)
            assertEquals("starting", flow.broadcast.phase)
        }

    @Test
    fun coins_taken_by_another_send_name_that_send() =
        runFlow { _, runtime, flow ->
            val kept = coin("11", 100_000_000)
            val taken = coin("22", 100_000_000)
            prime(flow, kept, taken)
            runtime.broadcastStore.start("job", "bb")
            runtime.armSendPersist(
                "job",
                SendRow(
                    txid = "cd".repeat(32),
                    txHex = "bb",
                    destination = flow.address,
                    coins = listOf(PrivateSendCoin(taken.txid, taken.vout, taken.valueSats)),
                ),
            )
            flow.applyHeldOutpoints(runtime.reservedOutpoints())
            assertTrue(flow.selectionNeedsAck)
            assertEquals(SELECTION_HELD, flow.selectionNotice)
        }

    @Test
    fun coins_that_left_the_wallet_keep_the_general_notice() =
        runFlow { _, _, flow ->
            val kept = coin("11", 100_000_000)
            prime(flow, kept, coin("22", 100_000_000))
            flow.updateSnap(WalletTxsSnapshot(utxos = listOf(kept)), emptySet())
            assertTrue(flow.selectionNeedsAck)
            assertEquals(SELECTION_CHANGED, flow.selectionNotice)
        }

    @Test
    fun max_shows_the_selected_coins_until_the_fee_is_known() {
        val coins = listOf(coin("11", 70_000), coin("22", 30_000))
        assertEquals(100_000, reviewHeroSats(null, SendAmount.Max, coins))
        assertEquals(99_000, reviewHeroSats(99_000, SendAmount.Max, coins))
        assertEquals(5_000, reviewHeroSats(null, SendAmount.Exact(5_000), coins))
    }

    @Test
    fun a_read_that_raced_a_clear_is_not_kept() {
        var loads = 0
        lateinit var cache: StoredHoldsCache
        cache =
            StoredHoldsCache {
                loads++
                if (loads == 1) cache.clear()
                listOf("tx" to "key$loads")
            }
        assertEquals(listOf("tx" to "key1"), cache.get())
        assertEquals(listOf("tx" to "key2"), cache.get())
        assertEquals(listOf("tx" to "key2"), cache.get())
        assertEquals(2, loads)
    }
}
