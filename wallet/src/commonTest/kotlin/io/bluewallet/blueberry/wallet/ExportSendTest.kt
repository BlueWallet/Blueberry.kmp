package io.bluewallet.blueberry.wallet

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportSendTest {
    @Test
    fun psbt_hex_encodes_to_standard_base64() {
        val zWallet = deriveWatchWallet(BLUE_ZPUB, WatchGaps(2, 1))
        val psbt =
            buildUnsignedSendPsbt(
                BuildSendTxParams(
                    secret = BLUE_ZPUB,
                    wallet = zWallet,
                    utxos =
                        listOf(
                            SendInputUtxo(
                                "11".repeat(32),
                                0,
                                100_000L,
                                zWallet.hd(AddressScriptType.P2WPKH, 0).scriptPubKey,
                            ),
                        ),
                    toAddress = BLUE_EXTERNAL_1,
                    amountSats = SendAmount.Exact(50_000L),
                    feeRateSatPerVb = 1.0,
                    changeAddress = BLUE_INTERNAL_0,
                ),
            )
        val b64 = psbtBase64FromHex(psbt.psbtHex)
        assertTrue(b64.startsWith("cHNidP8"))
        assertEquals(psbt.psbtHex, hexFromBytes(Base64.decode(b64)))
    }
}
