package io.bluewallet.blueberry

import io.bluewallet.blueberry.wallet.PSBT_NOT_A_PSBT
import io.bluewallet.blueberry.wallet.UR_CHECKSUM_FAILED
import io.bluewallet.blueberry.wallet.encodeCryptoPsbtUrFragments
import io.bluewallet.blueberry.wallet.hexFromBytes
import io.bluewallet.blueberry.wallet.hexToBytes
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
    fun a_rejected_psbt_is_not_handed_over_again() {
        val scan = SignedPsbtScan()
        val base64 = psbtBase64FromHex(PSBT_HEX)
        assertIs<SignedPsbtScanState.Done>(scan.accept(base64))
        scan.reject("Not this one")
        assertEquals(SignedPsbtScanState.Scanning(null, "Not this one"), scan.accept(base64))
        val parts = encodeCryptoPsbtUrFragments(PSBT_HEX, 30)
        assertEquals(SignedPsbtScanState.Scanning(null, "Not this one"), parts.map { scan.accept(it) }.last())
        val other = PSBT_HEX.replace("70aaf008", "70aaf009")
        assertIs<SignedPsbtScanState.Done>(scan.accept(psbtBase64FromHex(other)))
    }

    @Test
    fun a_rejection_stays_on_every_frame_of_the_same_animation_until_start_over() {
        val scan = SignedPsbtScan()
        val parts = encodeCryptoPsbtUrFragments(PSBT_HEX, 30)
        assertIs<SignedPsbtScanState.Done>(parts.map { scan.accept(it) }.last())
        scan.reject("Not this one")
        for (part in parts + parts) {
            assertEquals("Not this one", assertIs<SignedPsbtScanState.Scanning>(scan.accept(part)).note, part)
        }
        scan.reset()
        assertEquals(null, assertIs<SignedPsbtScanState.Scanning>(scan.accept(parts.first())).note)
    }

    @Test
    fun a_checksum_failure_stays_on_screen_while_good_frames_come_in() {
        val scan = SignedPsbtScan()
        val bad = wrongChecksumParts(PSBT_HEX)
        scan.accept(bad[0])
        assertEquals(SignedPsbtScanState.Scanning(null, UR_CHECKSUM_FAILED), scan.accept(bad[1]))
        val parts = encodeCryptoPsbtUrFragments(PSBT_HEX, 30)
        for (part in parts.dropLast(1)) {
            assertEquals(UR_CHECKSUM_FAILED, assertIs<SignedPsbtScanState.Scanning>(scan.accept(part)).note, part)
        }
        assertIs<SignedPsbtScanState.Done>(scan.accept(parts.last()))
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

        /** A two-frame `crypto-psbt` animation of [psbtHex] whose header checksum is off by one bit. */
        fun wrongChecksumParts(psbtHex: String): List<String> {
            val psbt = hexToBytes(psbtHex)
            val message = cborHead(2, psbt.size.toLong()) + psbt
            val half = (message.size + 1) / 2
            val padded = message.copyOf(half * 2)
            val checksum = crc32(message) xor 1
            return (1..2).map { seq ->
                val fragment = padded.copyOfRange((seq - 1) * half, seq * half)
                val cbor =
                    byteArrayOf(0x85.toByte()) + cborHead(0, seq.toLong()) + cborHead(0, 2) + cborHead(0, message.size.toLong()) +
                        cborHead(0, checksum) + cborHead(2, fragment.size.toLong()) + fragment
                "ur:crypto-psbt/$seq-2/${bytewordsMinimal(cbor)}"
            }
        }

        fun cborHead(
            major: Int,
            value: Long,
        ): ByteArray {
            val type = major shl 5
            return when {
                value < 24 -> byteArrayOf((type or value.toInt()).toByte())
                value < 0x100 -> byteArrayOf((type or 24).toByte(), value.toByte())
                value < 0x10000 -> byteArrayOf((type or 25).toByte(), (value shr 8).toByte(), value.toByte())
                else -> byteArrayOf((type or 26).toByte()) + ByteArray(4) { (value shr (24 - 8 * it)).toByte() }
            }
        }

        fun crc32(bytes: ByteArray): Long {
            var crc = 0xFFFFFFFFL
            for (b in bytes) {
                crc = crc xor (b.toLong() and 0xFF)
                repeat(8) { crc = if (crc and 1L != 0L) (crc ushr 1) xor 0xEDB88320L else crc ushr 1 }
            }
            return crc xor 0xFFFFFFFFL
        }

        fun bytewordsMinimal(body: ByteArray): String {
            val crc = crc32(body)
            val all = body + ByteArray(4) { (crc shr (24 - 8 * it)).toByte() }
            return all.joinToString("") { b ->
                val word = BYTEWORDS.substring((b.toInt() and 0xFF) * 4, (b.toInt() and 0xFF) * 4 + 4)
                "${word[0]}${word[3]}"
            }
        }

        const val BYTEWORDS =
            "ableacidalsoapexaquaarchatomauntawayaxisbackbaldbarnbeltbetabiasblue" +
                "bodybragbrewbulbbuzzcalmcashcatschefcityclawcodecolacookcostcruxcurlcuspcyandarkdatadaysdelidice" +
                "dietdoordowndrawdropdrumdulldutyeacheasyechoedgeepicevenexamexiteyesfactfairfernfigsfilmfishfizz" +
                "flapflewfluxfoxyfreefrogfuelfundgalagamegeargemsgiftgirlglowgoodgraygrimgurugushgyrohalfhanghard" +
                "hawkheathelphighhillholyhopehornhutsicedideaidleinchinkyintoirisironitemjadejazzjoinjoltjowljudo" +
                "jugsjumpjunkjurykeepkenokeptkeyskickkilnkingkitekiwiknoblamblavalazyleaflegsliarlimplionlistlogo" +
                "loudloveluaulucklungmainmanymathmazememomenumeowmildmintmissmonknailnavyneednewsnextnoonnotenumb" +
                "obeyoboeomitonyxopenovalowlspaidpartpeckplaypluspoempoolposepuffpumapurrquadquizraceramprealredo" +
                "richroadrockroofrubyruinrunsrustsafesagascarsetssilkskewslotsoapsolosongstubsurfswantacotasktaxi" +
                "tenttiedtimetinytoiltombtoystriptunatwinuglyundouniturgeuservastveryvetovialvibeviewvisavoidvows" +
                "wallwandwarmwaspwavewaxywebswhatwhenwhizwolfworkyankyawnyellyogayurtzapszerozestzinczonezoom"
    }
}
