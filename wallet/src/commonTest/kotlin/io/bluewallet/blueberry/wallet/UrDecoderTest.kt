package io.bluewallet.blueberry.wallet

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UrDecoderTest {
    private fun psbtDecoder() = UrDecoder(PSBT_UR_TYPES)

    private fun UrDecoder.feed(parts: List<String>): UrReceive = parts.map { receive(it) }.last()

    private fun completedPsbt(result: UrReceive): String = hexFromBytes(psbtFromUrMessage(assertIs<UrReceive.Complete>(result).message))

    @Test
    fun bluewallet_two_part_vector_decodes_in_order() = assertEquals(BW_PSBT_HEX, completedPsbt(psbtDecoder().feed(BW_PARTS)))

    @Test
    fun bluewallet_two_part_vector_decodes_reversed_with_duplicates() = assertEquals(BW_PSBT_HEX, completedPsbt(psbtDecoder().feed(listOf(BW_PARTS[1], BW_PARTS[1], BW_PARTS[0]))))

    @Test
    fun fountain_only_parts_from_ngraveio_decode() = assertEquals(BW_PSBT_HEX, completedPsbt(psbtDecoder().feed(NGRAVE_MIXED_PARTS)))

    @Test
    fun uppercase_qr_alphanumeric_form_decodes() = assertEquals(BW_PSBT_HEX, completedPsbt(psbtDecoder().feed(BW_PARTS.map { it.uppercase() })))

    @Test
    fun progress_grows_and_reaches_100() {
        val decoder = psbtDecoder()
        assertNull(decoder.percent)
        var last = 0
        for (part in NGRAVE_MIXED_PARTS.dropLast(1)) {
            val step = assertIs<UrReceive.Progress>(decoder.receive(part))
            assertTrue(step.percent in last..99, "percent ${step.percent} after $last")
            last = step.percent
        }
        assertTrue(last > 0)
        assertIs<UrReceive.Complete>(decoder.receive(NGRAVE_MIXED_PARTS.last()))
        assertEquals(100, decoder.percent)
    }

    @Test
    fun single_part_crypto_psbt_and_psbt_types_decode() {
        val single = encodeCryptoPsbtUrFragments(BW_PSBT_HEX, 10_000).single()
        assertEquals(BW_PSBT_HEX, completedPsbt(psbtDecoder().receive(single)))
        val newType = "ur:psbt/" + single.substringAfter("ur:crypto-psbt/")
        assertEquals(BW_PSBT_HEX, completedPsbt(psbtDecoder().receive(newType)))
    }

    @Test
    fun foreign_type_is_ignored() {
        val decoder = psbtDecoder()
        assertEquals(UrReceive.Ignored(UR_WRONG_TYPE), decoder.receive(CRYPTO_ACCOUNT_UR))
        assertNull(decoder.percent)
    }

    @Test
    fun a_second_type_is_ignored_after_the_first() {
        val decoder = psbtDecoder()
        assertIs<UrReceive.Progress>(decoder.receive(BW_PARTS[0]))
        assertEquals(UrReceive.Ignored(UR_WRONG_TYPE), decoder.receive(BW_PARTS[1].replace("ur:crypto-psbt/", "ur:psbt/")))
        assertEquals(BW_PSBT_HEX, completedPsbt(decoder.receive(BW_PARTS[1])))
    }

    @Test
    fun a_part_of_another_animation_is_ignored_without_losing_progress() {
        val decoder = psbtDecoder()
        val first = assertIs<UrReceive.Progress>(decoder.receive(BW_PARTS[0]))
        assertEquals(UrReceive.Ignored(UR_OTHER_ANIMATION), decoder.receive(NGRAVE_MIXED_PARTS[0]))
        assertEquals(UrReceive.Ignored(UR_OTHER_ANIMATION), decoder.receive(encodeCryptoPsbtUrFragments(BW_PSBT_HEX, 10_000).single()))
        assertEquals(first.percent, decoder.percent)
        assertEquals(BW_PSBT_HEX, completedPsbt(decoder.receive(BW_PARTS[1])))
    }

    @Test
    fun corrupt_frame_is_ignored() {
        val part = BW_PARTS[0]
        val flipped = part.dropLast(1) + (if (part.last() == 'a') 'e' else 'a')
        assertEquals(UrReceive.Ignored(UR_CORRUPT), psbtDecoder().receive(flipped))
    }

    @Test
    fun text_that_is_not_a_ur_is_ignored() {
        assertEquals(UrReceive.Ignored(UR_NOT_UR), psbtDecoder().receive("bitcoin:bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"))
        assertEquals(UrReceive.Ignored(UR_NOT_UR), psbtDecoder().receive("ur:crypto-psbt/1-x/abcd"))
    }

    @Test
    fun parts_that_reassemble_into_a_bad_checksum_fail_until_reset() {
        val decoder = psbtDecoder()
        decoder.receive(BW_PARTS[0])
        assertEquals(UrReceive.Failed(UR_CHECKSUM_FAILED), decoder.receive(tamperedFragment(BW_PARTS[1])))
        assertEquals(UrReceive.Failed(UR_CHECKSUM_FAILED), decoder.receive(BW_PARTS[1]))
        decoder.reset()
        assertEquals(BW_PSBT_HEX, completedPsbt(decoder.feed(BW_PARTS)))
    }

    @Test
    fun a_sequence_zero_frame_is_ignored_and_the_animation_still_completes() {
        val decoder = psbtDecoder()
        assertIs<UrReceive.Progress>(decoder.receive(BW_PARTS[0]))
        assertEquals(UrReceive.Ignored(UR_CORRUPT), decoder.receive(reframed(BW_PARTS[0], seqNum = 0)))
        assertEquals(BW_PSBT_HEX, completedPsbt(decoder.receive(BW_PARTS[1])))
    }

    /** The same part with one fragment byte flipped and a valid bytewords checksum, so only the message CRC catches it. */
    private fun tamperedFragment(part: String): String = reframed(part, flip = true)

    /** [part] re-encoded with a new sequence number (path and CBOR agree) or a flipped fragment byte. */
    private fun reframed(
        part: String,
        seqNum: Long? = null,
        flip: Boolean = false,
    ): String {
        val decoded = cborDecodeFountainPart(bytewordsDecodeMinimal(part.substringAfterLast('/')))
        val seq = seqNum ?: decoded.seqNum
        val prefix = part.substringBeforeLast('/').substringBeforeLast('/') + "/$seq-${decoded.seqLength}"
        val fragment = decoded.fragment.copyOf().also { if (flip) it[0] = (it[0].toInt() xor 1).toByte() }
        val cbor =
            byteArrayOf(0x85.toByte()) +
                cborUInt(seq) + cborUInt(decoded.seqLength) + cborUInt(decoded.messageLength) + cborUInt(decoded.checksum) +
                byteArrayOf(0x58, fragment.size.toByte()) + fragment
        return "$prefix/${bytewordsEncodeMinimal(cbor)}"
    }

    private fun cborUInt(value: Long): ByteArray =
        when {
            value < 24 -> byteArrayOf(value.toByte())
            value < 256 -> byteArrayOf(0x18, value.toByte())
            value < 65_536 -> byteArrayOf(0x19, (value shr 8).toByte(), value.toByte())
            else -> byteArrayOf(0x1a, (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
        }

    @Test
    fun reset_starts_over() {
        val decoder = psbtDecoder()
        decoder.receive(BW_PARTS[0])
        decoder.reset()
        assertNull(decoder.percent)
        assertIs<UrReceive.Progress>(decoder.receive(NGRAVE_MIXED_PARTS[0]))
    }

    @Test
    fun round_trips_our_encoder_in_shuffled_order_at_several_capacities() {
        val random = Random(7)
        for (capacity in listOf(20, 60, 120, BC_UR_PSBT_CAPACITY)) {
            val parts = encodeCryptoPsbtUrFragments(LONG_MESSAGE_HEX, capacity).shuffled(random)
            val decoder = psbtDecoder()
            val result = parts.map { decoder.receive(it) }.last { it !is UrReceive.Ignored }
            assertEquals(LONG_MESSAGE_HEX, completedPsbt(result), "capacity $capacity")
        }
    }

    private companion object {
        /** BlueWallet `tests/unit/watch-only-wallet.test.js`, "v2: encodeUR() psbt works" (capacity 100). */
        const val BW_PSBT_HEX =
            "70736274ff01009a020000000258e87a21b56daf0c23be8e7070456c336f7cbaa5c8757924f545887bb2abdd750000000000ffffffff" +
                "838d0427d0ec650a68aa46bb0b098aea4422c071b2ca78352a077959d07cea1d0100000000ffffffff0270aaf00800000000160014d8" +
                "5c2b71d0060b09c9886aeb815e50991dda124d00e1f5050000000016001400aea9a2e5f0f876a588df5546e8742d1d87008f000000000000000000"

        val BW_PARTS =
            listOf(
                "ur:crypto-psbt/1-2/lpadaocsptcybkgdcarhhdgohdosjojkidjyzmadaenyaoaeaeaeaohdvsknclrejnpebncnrnmnjojofejzeojlkerdonspkp" +
                    "kkdkykfelokgprpyutkpaeaeaeaeaezmzmzmzmlslgaaditiwpihbkispkfgrkbdaslewdfycprtjsprsgksecdratkkhktimndacnch",
                "ur:crypto-psbt/2-2/lpaoaocsptcybkgdcarhhdgokewdcaadaeaeaeaezmzmzmzmaojopkwtayaeaeaeaecmaebbtphhdnjstiambdassoloimwmly" +
                    "hygdnlcatnbggtaevyykahaeaeaeaecmaebbaeplptoevwwtyakoonlourgofgvsjydpcaltaemyaeaeaeaeaeaeaeaeaeaeswhhtptt",
            )

        /**
         * [BW_PSBT_HEX] from `@ngraveio/bc-ur` `new UREncoder(ur, 30, 0)`: 6 fragments. These are parts 7 to 19 only,
         * every one past the simple run, so decoding needs the fountain reduction.
         */
        val NGRAVE_MIXED_PARTS =
            listOf(
                "ur:crypto-psbt/7-6/lpatamcsptcybkgdcarhhdcaytehzetkiahybdcmjsinylbssgaejtykahaeaeaeaecmaebbaeplptoevwrtsehtte",
                "ur:crypto-psbt/8-6/lpayamcsptcybkgdcarhhdcapdheamtbwdpypkflvswydlcaltaelghdvsknclrejnpebncnrnmnjojofecymhvwmt",
                "ur:crypto-psbt/9-6/lpasamcsptcybkgdcarhhdcawtyakoonlourgofgvsjydpcaltaemyaeaeaeaeaeaeaeaeaeaeaeaeaeaedlhfsews",
                "ur:crypto-psbt/10-6/lpbkamcsptcybkgdcarhhdcahdosjojkidjyzmadaenyaoaeaeaeaohdvsknclrejnpebncnrnmnjojofestfzsfkt",
                "ur:crypto-psbt/11-6/lpbdamcsptcybkgdcarhhdcacaadaeaeaeaezmzmzmzmaojopkwtayaeaeaeaecmaebbtphhdnjstiambddpjtsahn",
                "ur:crypto-psbt/12-6/lpbnamcsptcybkgdcarhhdcanssbcftaeyknnteomegdtphdbskgfspyutkpaeaeaeaeaezmzmzmzmlslgmuaytewm",
                "ur:crypto-psbt/13-6/lpbtamcsptcybkgdcarhhdcagyjtyacfldykoygynllttpbggtaevlpmweknclrejnrhbnemrncxtatdnbzcvtfywf",
                "ur:crypto-psbt/14-6/lpbaamcsptcybkgdcarhhdcaoymtmnrfaddrwkchjswfykbssgaejzpmweknclrejnrhbnemrncxtatdnbmyjyzmgo",
                "ur:crypto-psbt/15-6/lpbsamcsptcybkgdcarhhdcafshlmsiheogdindkvtotdphgskkggyamdybsclrejnrhbnspfpurdsgydprklsonnb",
                "ur:crypto-psbt/16-6/lpbeamcsptcybkgdcarhhdcawtyakoonlourgofgvsjydpcaltaemyaeaeaeaeaeaeaeaeaeaeaeaeaeaebalnttpt",
                "ur:crypto-psbt/17-6/lpbyamcsptcybkgdcarhhdcajzeojlkerdonspkpkkdkykfelokgprpyutkpaeaeaeaeaezmzmzmzmlslgftjsluhh",
                "ur:crypto-psbt/18-6/lpbgamcsptcybkgdcarhhdcahdosjojkidjyzmadaenyaoaeaeaeaohdvsknclrejnpebncnrnmnjojofegatyglko",
                "ur:crypto-psbt/19-6/lpbwamcsptcybkgdcarhhdcawlueolgawetlsabwgydydkieoscysrcprtjspruoksclwzhpgmdeaeknvycndwctgm",
            )

        /** BlueWallet `multisig-hd-wallet.test.js`: a `crypto-account` UR, which is not a PSBT. */
        const val CRYPTO_ACCOUNT_UR =
            "ur:crypto-account/oeadcytekbpmloaolytaadmetaaddloxaxhdclaofejnolgudllagdgodyweehzsmeyasnswrpdalnwzfenbmewlrtplsklbjkvd" +
                "loweaahdcxltjzjpctayfsimuogtpypffrnlisflswwzntbecabtbdwdbstojnfrahdamnpfcyamtaaddyotadlocsdyykaeykaeykaoykaocytekbpmlo" +
                "axaaaycyghykhpcmkgnstevs"

        /** Bytes long enough for many fragments at small capacities (the decoder doesn't parse the PSBT). */
        val LONG_MESSAGE_HEX = BW_PSBT_HEX.repeat(3)
    }
}
