package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun cancelling_overrides_working_but_not_finished() {
        assertEquals("Stopping…", broadcastStatus(BroadcastSnapshot(phase = "attempt"), cancelling = true).line)
        assertEquals("Sent privately", broadcastStatus(BroadcastSnapshot(phase = "success"), cancelling = true).line)
    }

    @Test
    fun owns_only_this_transaction() {
        assertTrue(broadcastOwns(BroadcastSnapshot(txHex = "aa", phase = "starting"), "aa"))
        assertFalse(broadcastOwns(BroadcastSnapshot(txHex = "bb", phase = "starting"), "aa"))
        assertFalse(broadcastOwns(BroadcastSnapshot(), null))
    }
}
