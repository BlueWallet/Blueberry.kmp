package io.bluewallet.blueberry.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EncodePsbtUrTest {
    /**
     * Golden interop vector: `ur:crypto-psbt/…` fragments produced for [GOLDEN_PSBT_HEX] by
     * `@keystonehq/bc-ur-registry` (`CryptoPSBT.toUREncoder`) backed by `@ngraveio/bc-ur`, at
     * [BC_UR_PSBT_CAPACITY] max fragment length. Regenerate with a one-off Node script against
     * those packages (e.g. under a helix3 `node_modules` checkout) if this vector ever changes.
     */
    private val goldenPsbtUrFragments =
        listOf(
            "ur:crypto-psbt/1-2/lpadaocsttcyfyckjkbdhdinhdtkjojkidjyzmadaejsaoaeaeaeadbybybybybybybybybybybybybybybybybybybybybybybybybybybybybybybybyaeaeaeaeaezmzmzmzmaogdsraeaeaeaeaeaecmaebbnsmhyteewdgyzsbsihaachjofxvtmhlgolmonllstoryaeaeaeaeaeaecmaebbfmeemkhlsgjlrlcprhfs",
            "ur:crypto-psbt/2-2/lpaoaocsttcyfyckjkbdhdinutsozoennlfzvesttpvoltfhgmnsaeaeaeaeaeadadctnblnadaeaeaeaeaecmaebbrttorftbsrtesglkkpuohyswdmrngoeobaytbevocpamaxdytlgwtiutfwbkjthelgendkykwffddwplecbskktlwtkpfrykrnwsnsdpmepefnbnzcbwpksoaeaeaeaeaeaeaeaeaeaeaeaeahvwwytt",
        )

    @Test
    fun encodes_unsigned_psbt_as_ur_crypto_psbt_and_round_trips() {
        val wallet = deriveWatchWallet(BLUE_ZPUB, WatchGaps(2, 1))
        val recv = wallet.addresses.first { !it.change }
        val dest = wallet.addresses.first { !it.change && it.index == 1 }
        val change = wallet.addresses.first { it.change }
        val psbtHex =
            buildUnsignedSendPsbt(
                BuildSendTxParams(
                    secret = BLUE_ZPUB,
                    wallet = wallet,
                    utxos =
                        listOf(
                            SendInputUtxo(
                                txid = "11".repeat(32),
                                vout = 0,
                                valueSats = 100_000L,
                                scriptPubKey = recv.scriptPubKey,
                            ),
                        ),
                    toAddress = dest.address,
                    amountSats = SendAmount.Exact(50_000L),
                    feeRateSatPerVb = 10.0,
                    changeAddress = change.address,
                ),
            ).psbtHex
        assertEquals(GOLDEN_PSBT_HEX, psbtHex.lowercase())

        val parts = encodeCryptoPsbtUrFragments(psbtHex, BC_UR_PSBT_CAPACITY)
        assertTrue(parts.isNotEmpty())
        assertTrue(parts.all { it.lowercase().startsWith("ur:crypto-psbt/") })
        assertEquals(goldenPsbtUrFragments, parts)
        assertEquals(psbtHex.lowercase(), hexFromBytes(decodeCryptoPsbtUrFragments(parts)))
    }

    private companion object {
        /** The unsigned PSBT this test builds; pinned so [goldenPsbtUrFragments] stays interop-checked. */
        const val GOLDEN_PSBT_HEX =
            "70736274ff010071020000000111111111111111111111111111111111111111111111111111111111" +
                "111111110000000000ffffffff0250c30000000000001600149c90f934ea51fa0f6504177043e0" +
                "908da6929983cebd0000000000001600143e34985dca6fddc9fb369940e4c7d8e2873f529c00000" +
                "0000001011fa086010000000000160014c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2220603" +
                "30d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c0cfd13aac9000000" +
                "0000000000000000"
    }
}
