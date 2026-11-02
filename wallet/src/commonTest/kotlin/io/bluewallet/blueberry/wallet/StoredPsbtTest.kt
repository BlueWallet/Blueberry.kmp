package io.bluewallet.blueberry.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class StoredPsbtTest {
    private fun params(
        secret: String,
        scriptType: AddressScriptType,
        legacy: Boolean = false,
    ): BuildSendTxParams {
        val wallet = deriveWatchWallet(secret)
        val recv = wallet.addresses.first { it.scriptType == scriptType }
        val fund = testFundingTx(recv.scriptPubKey, 100_000L, 21)
        val utxo = SendInputUtxo(fund.txid, 0, 100_000L, recv.scriptPubKey, nonWitnessUtxo = if (legacy) fund.bytes else null)
        return BuildSendTxParams(
            secret = secret,
            wallet = wallet,
            utxos = listOf(utxo),
            toAddress = DEST_LEGACY,
            amountSats = SendAmount.Exact(50_000L),
            feeRateSatPerVb = 1.0,
            changeAddress = recv.address,
        )
    }

    @Test
    fun signing_a_nested_segwit_or_legacy_input_keeps_the_unsigned_txid() {
        for (
        (secret, type, legacy) in
        listOf(
            Triple(WIF_P2SH, AddressScriptType.P2SH_P2WPKH, false),
            Triple(WIF_LEGACY, AddressScriptType.P2PKH, true),
        )
        ) {
            val p = params(secret, type, legacy)
            val unsigned = buildUnsignedSendPsbt(p)
            val signed = buildSignedSendTx(p)
            assertNotEquals(unsigned.txid, signed.txid)
            assertEquals(unsigned.txid, unsignedTxid(hexToBytes(signed.txHex)))
            assertEquals(unsigned.txid, unsignedTxid(hexToBytes(unsigned.unsignedTxHex)))
        }
    }

    @Test
    fun a_stored_unsigned_transaction_rebuilds_the_same_psbt() {
        for (
        (secret, type, legacy) in
        listOf(
            Triple(WIF_P2SH, AddressScriptType.P2SH_P2WPKH, false),
            Triple(WIF_LEGACY, AddressScriptType.P2PKH, true),
            Triple(WIF_BECH32, AddressScriptType.P2WPKH, false),
        )
        ) {
            val p = params(secret, type, legacy)
            val direct = buildUnsignedSendPsbt(p)
            assertEquals(direct.psbtHex, rebuildSendPsbt(p.secret, p.wallet, direct.unsignedTxHex, p.utxos))
        }
    }

    @Test
    fun a_psbt_input_with_no_known_coin_is_refused() {
        val p = params(WIF_BECH32, AddressScriptType.P2WPKH)
        val direct = buildUnsignedSendPsbt(p)
        assertFailsWith<IllegalArgumentException> { rebuildSendPsbt(p.secret, p.wallet, direct.unsignedTxHex, emptyList()) }
    }
}
