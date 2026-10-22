package io.bluewallet.blueberry

import io.bluewallet.bip324.OutPoint
import io.bluewallet.bip324.Transaction
import io.bluewallet.bip324.TxInput
import io.bluewallet.bip324.TxOutput
import io.bluewallet.bip324.bytesToHex
import io.bluewallet.bip324.encodeTransaction
import io.bluewallet.blueberry.wallet.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendBroadcastTest {
    private fun line(snap: BroadcastSnapshot) = broadcastStatus(snap).line

    @Test
    fun working_phases_read_as_plain_steps() {
        assertEquals("Starting Tor", line(BroadcastSnapshot(phase = "starting")))
        assertEquals("Finding Bitcoin nodes", line(BroadcastSnapshot(phase = "waiting-peers")))
        assertEquals("Building a private circuit", line(BroadcastSnapshot(phase = "attempt", percent = 40)))
        val handing = broadcastStatus(BroadcastSnapshot(phase = "attempt", attempt = 2, maxAttempts = 5, peer = "1.2.3.4"))
        assertEquals("Handing it to Bitcoin nodes", handing.line)
        assertEquals("Attempt 2 of 5", handing.caption)
        assertEquals("Trying another node", line(BroadcastSnapshot(phase = "failed-attempt")))
        assertEquals(BroadcastTone.Working, broadcastStatus(BroadcastSnapshot(phase = "attempt")).tone)
    }

    @Test
    fun finished_phases() {
        val sent = broadcastStatus(BroadcastSnapshot(phase = "success"))
        assertEquals("Sent", sent.title)
        assertEquals(BroadcastTone.Sent, sent.tone)
        val failed = broadcastStatus(BroadcastSnapshot(phase = "error", error = "no peers accepted"))
        assertEquals("Not sent yet", failed.title)
        assertEquals("no peers accepted", failed.line)
        assertEquals(BroadcastTone.Failed, failed.tone)
        assertEquals("No node accepted it.", line(BroadcastSnapshot(phase = "error")))
    }

    @Test
    fun only_a_cancelled_broadcast_can_be_forgotten() {
        assertTrue(broadcastCancelled("cancelled"))
        assertTrue(broadcastCancelled("  Cancelled  "))
        assertFalse(broadcastCancelled("no peers accepted"))
        assertFalse(broadcastCancelled(null))
        assertTrue(broadcastCanBeForgotten("error"))
        assertFalse(broadcastCanBeForgotten("success"))
        assertFalse(broadcastCanBeForgotten("attempt"))
    }

    @Test
    fun retry_refuses_a_missing_or_reserved_input() {
        val display = "ab".repeat(32)
        val tx =
            Transaction(
                version = 1u,
                inputs =
                    listOf(
                        TxInput(
                            previousOutput = OutPoint(hexToBytes(display).reversedArray(), 0u),
                            scriptSig = ByteArray(0),
                            sequence = 0xffffffffu,
                        ),
                    ),
                outputs = listOf(TxOutput(value = 1000, scriptPubKey = byteArrayOf(0x51))),
                lockTime = 0u,
            )
        val hex = bytesToHex(encodeTransaction(tx))
        val key = "$display:0"
        assertNull(retryRefusal(hex, setOf(key), utxosReady = true, reserved = emptySet()))
        assertEquals(RETRY_COINS_GONE, retryRefusal(hex, emptySet(), utxosReady = true, reserved = emptySet()))
        assertEquals(RETRY_COINS_HELD, retryRefusal(hex, setOf(key), utxosReady = true, reserved = setOf(key)))
        assertEquals(RETRY_WALLET_LOADING, retryRefusal(hex, setOf(key), utxosReady = false, reserved = emptySet()))
    }

    @Test
    fun cancelling_overrides_working_but_not_finished() {
        val stopping = broadcastStatus(BroadcastSnapshot(phase = "attempt", percent = 40, stopRequested = true))
        assertEquals("Stopping", stopping.title)
        assertEquals("This send will not go out", stopping.line)
        assertEquals("The circuit it already opened is closing.", stopping.caption)
        assertEquals(BroadcastTone.Working, stopping.tone)
        assertEquals("Sent privately", broadcastStatus(BroadcastSnapshot(phase = "success", stopRequested = true)).line)
    }

    @Test
    fun another_transactions_success_is_not_this_receipt() {
        val other = BroadcastSnapshot(txHex = "bb", phase = "success")
        val status = ownedBroadcastStatus(other, "aa")
        assertEquals(BroadcastTone.Working, status.tone)
        assertEquals("Starting Tor", status.line)
        assertEquals(BroadcastTone.Sent, ownedBroadcastStatus(other, "bb").tone)
    }

    @Test
    fun list_rows_follow_a_job_until_the_transaction_is_stored() {
        val running =
            BroadcastSession(
                BroadcastSnapshot(id = "1", txHex = "aa", phase = "starting"),
                BroadcastReceipt("txid-run", "bc1qexample", "Rent", 10_000, 200),
            )
        val failed =
            BroadcastSession(
                BroadcastSnapshot(id = "2", txHex = "bb", phase = "error", error = "eof"),
                BroadcastReceipt("txid-fail", "bc1qother", "", 5_000, 100),
            )
        val rows = broadcastListRows(listOf(running, failed), emptySet())
        assertEquals(listOf("2", "1"), rows.map { it.id })
        assertEquals("Not sent", rows[0].status)
        assertEquals("bc1qother", rows[0].title)
        assertEquals("Broadcasting", rows[1].status)
        val stopping =
            BroadcastSession(
                BroadcastSnapshot(id = "4", txHex = "dd", phase = "attempt", percent = 40, stopRequested = true),
                BroadcastReceipt("txid-stop", "bc1qstop", "Rent", 10_000, 200),
            )
        assertEquals("Stopping", broadcastListRows(listOf(stopping), emptySet()).single().status)
        assertEquals("Rent", rows[1].title)
        assertEquals(emptyList(), broadcastListRows(listOf(running), setOf("txid-run")))
        val sent =
            BroadcastSession(
                BroadcastSnapshot(id = "3", txHex = "cc", phase = "success"),
                BroadcastReceipt("txid-sent", "bc1qsent", "Done", 1, 1),
            )
        assertEquals("Sent", broadcastListRows(listOf(sent), emptySet()).single().status)
        assertEquals(emptyList(), broadcastListRows(listOf(sent), setOf("txid-sent")))
    }

    @Test
    fun owns_only_this_transaction() {
        assertTrue(broadcastOwns(BroadcastSnapshot(txHex = "aa", phase = "starting"), "aa"))
        assertFalse(broadcastOwns(BroadcastSnapshot(txHex = "bb", phase = "starting"), "aa"))
        assertFalse(broadcastOwns(BroadcastSnapshot(), null))
    }
}
