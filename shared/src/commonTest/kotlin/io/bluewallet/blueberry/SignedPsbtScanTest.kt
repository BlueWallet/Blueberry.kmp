package io.bluewallet.blueberry

import io.bluewallet.blueberry.wallet.PSBT_NOT_A_PSBT
import io.bluewallet.blueberry.wallet.encodeCryptoPsbtUrFragments
import io.bluewallet.blueberry.wallet.hexFromBytes
import io.bluewallet.blueberry.wallet.psbtBase64FromHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SignedPsbtScanTest {
    @Test
    fun animated_frames_reach_done_with_the_psbt() {
        val scan = SignedPsbtScan()
        val parts = encodeCryptoPsbtUrFragments(PSBT_HEX, 30)
        assertTrue(parts.size > 3)
        var last = 0
        for (part in parts.dropLast(1)) {
            val state = assertIs<SignedPsbtScanState.Scanning>(scan.accept(part))
            val percent = state.percent ?: error("no progress")
            assertTrue(percent >= last)
            last = percent
        }
        val done = assertIs<SignedPsbtScanState.Done>(scan.accept(parts.last()))
        assertEquals(PSBT_HEX, hexFromBytes(done.psbt))
        assertIs<SignedPsbtScanState.Done>(scan.accept("anything after"))
    }

    @Test
    fun the_same_frame_twice_changes_nothing() {
        val scan = SignedPsbtScan()
        val part = encodeCryptoPsbtUrFragments(PSBT_HEX, 30).first()
        val first = scan.accept(part)
        assertEquals(first, scan.accept(part))
    }

    @Test
    fun a_base64_psbt_is_done_at_once() {
        val done = assertIs<SignedPsbtScanState.Done>(SignedPsbtScan().accept(psbtBase64FromHex(PSBT_HEX)))
        assertEquals(PSBT_HEX, hexFromBytes(done.psbt))
    }

    @Test
    fun unknown_text_keeps_progress_and_says_why() {
        val scan = SignedPsbtScan()
        val started = assertIs<SignedPsbtScanState.Scanning>(scan.accept(encodeCryptoPsbtUrFragments(PSBT_HEX, 30).first()))
        assertEquals(SignedPsbtScanState.Scanning(started.percent, PSBT_NOT_A_PSBT), scan.accept("bitcoin:1GX36PGBUrF8XahZEGQqHqnJGW2vCZteoB"))
    }

    @Test
    fun reset_clears_progress_and_shows_the_note() {
        val scan = SignedPsbtScan()
        scan.accept(encodeCryptoPsbtUrFragments(PSBT_HEX, 30).first())
        scan.reset("Try again")
        assertEquals(SignedPsbtScanState.Scanning(null, "Try again"), scan.state)
    }

    private companion object {
        const val PSBT_HEX =
            "70736274ff01009a020000000258e87a21b56daf0c23be8e7070456c336f7cbaa5c8757924f545887bb2abdd750000000000ffffffff" +
                "838d0427d0ec650a68aa46bb0b098aea4422c071b2ca78352a077959d07cea1d0100000000ffffffff0270aaf00800000000160014d8" +
                "5c2b71d0060b09c9886aeb815e50991dda124d00e1f5050000000016001400aea9a2e5f0f876a588df5546e8742d1d87008f000000000000000000"
    }
}
