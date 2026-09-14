package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxHash
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ReceiveAddressTest {
    @Test
    fun first_unused_external_and_internal() {
        val w = deriveWatchWallet(ABANDON, WatchGaps(3, 1))
        val ext0 = firstUnusedExternalAddress(w, emptyList())
        assertEquals(0, ext0!!.index)
        assertFalse(ext0.change)
        assertEquals(BLUE_EXTERNAL_0, ext0.address)
        assertEquals(
            2,
            firstUnusedExternalAddress(deriveWatchWallet(ABANDON, WatchGaps(5, 2)), listOf(0, 1, 3))!!.index,
        )
        assertNull(firstUnusedExternalAddress(deriveWatchWallet(ABANDON, WatchGaps(2, 1)), listOf(0, 1)))
        val int0 = firstUnusedInternalAddress(deriveWatchWallet(ABANDON, WatchGaps(1, 3)), emptyList())
        assertEquals(0, int0!!.index)
        assertEquals(true, int0.change)
        assertEquals(1, firstUnusedInternalAddress(deriveWatchWallet(ABANDON, WatchGaps(1, 4)), listOf(0, 2))!!.index)
        assertNull(firstUnusedInternalAddress(deriveWatchWallet(ABANDON, WatchGaps(1, 2)), listOf(0, 1)))
    }

    @Test
    fun preferred_wif_receive_defaults_and_earliest_touch() {
        val w = deriveWatchWallet(WIF_BECH32)
        val native = preferredWifReceiveAddress(w, emptyList())
        assertEquals(AddressScriptType.P2WPKH, native.scriptType)
        assertEquals(ADDR_BECH32, native.address)
        val legacy = w.addresses.first { it.scriptType == AddressScriptType.P2PKH }
        val tap = w.addresses.first { it.scriptType == AddressScriptType.P2TR }
        val fundLegacy = fundingTx(legacy.scriptPubKey, 10_000L, salt = 1)
        val fundTap = fundingTx(tap.scriptPubKey, 10_000L, salt = 2)
        val addr =
            preferredWifReceiveAddress(
                w,
                listOf(
                    WifReceiveTxRow(200, 0, fundTap.tx),
                    WifReceiveTxRow(100, 5, fundLegacy.tx),
                ),
            )
        assertEquals(AddressScriptType.P2PKH, addr.scriptType)
        assertEquals(legacy.address, addr.address)
    }

    @Test
    fun preferred_wif_same_height_lower_tx_index_wins() {
        val w = deriveWatchWallet(WIF_P2SH)
        val nested = byType(w, AddressScriptType.P2SH_P2WPKH)
        val native = byType(w, AddressScriptType.P2WPKH)
        val a = fundingTx(native.scriptPubKey, 1_000L, salt = 3)
        val b = fundingTx(nested.scriptPubKey, 1_000L, salt = 4)
        val addr =
            preferredWifReceiveAddress(
                w,
                listOf(
                    WifReceiveTxRow(50, 9, a.tx),
                    WifReceiveTxRow(50, 2, b.tx),
                ),
            )
        assertEquals(AddressScriptType.P2SH_P2WPKH, addr.scriptType)
    }

    @Test
    fun preferred_wif_spend_of_known_outpoint_counts_as_touch() {
        val w = deriveWatchWallet(WIF_BECH32)
        val legacy = byType(w, AddressScriptType.P2PKH)
        val native = byType(w, AddressScriptType.P2WPKH)
        val fund = fundingTx(legacy.scriptPubKey, 10_000L, salt = 30)
        val spend = spendTx(fund.txid, hexToBytes("76a914" + "11".repeat(20) + "88ac"), 9_000L)
        val laterNative = fundingTx(native.scriptPubKey, 1_000L, salt = 31)
        val addr =
            preferredWifReceiveAddress(
                w,
                listOf(
                    WifReceiveTxRow(200, 0, fund.tx),
                    WifReceiveTxRow(100, 0, spend),
                    WifReceiveTxRow(150, 0, laterNative.tx),
                ),
            )
        assertEquals(AddressScriptType.P2PKH, addr.scriptType)
        assertEquals(legacy.address, addr.address)
    }

    @Test
    fun resolve_bip84_uses_first_unused_external() {
        val w = deriveWatchWallet(ABANDON, WatchGaps(5, 2))
        val addr =
            resolveReceiveAddress(
                w,
                usedExternal = listOf(0, 1, 3),
                receiveType = AddressScriptType.P2TR,
            )!!
        assertEquals(2, addr.index)
        assertEquals(false, addr.change)
        assertEquals(AddressScriptType.P2TR, addr.resolvedScriptType())
    }

    @Test
    fun resolve_bip84_is_null_when_all_externals_used() {
        val w = deriveWatchWallet(ABANDON, WatchGaps(2, 1))
        assertNull(resolveReceiveAddress(w, usedExternal = listOf(0, 1)))
    }

    @Test
    fun resolve_wif_defaults_to_native() {
        val w = deriveWatchWallet(WIF_BECH32)
        assertEquals(ADDR_BECH32, resolveReceiveAddress(w)!!.address)
    }

    @Test
    fun resolve_imported_address_is_the_watch_address() {
        val w = deriveWatchWallet(ADDR_BECH32)
        assertEquals(ADDR_BECH32, resolveReceiveAddress(w)!!.address)
    }
}

private fun byType(
    wallet: WatchWallet,
    scriptType: AddressScriptType,
): WatchAddress = wallet.addresses.first { it.scriptType == scriptType }

private data class FundingTx(
    val txid: String,
    val tx: ByteArray,
)

private fun fundingTx(
    scriptPubKey: ByteArray,
    valueSats: Long,
    salt: Int = 1,
): FundingTx {
    val prevHash = ByteArray(32).also { it[0] = salt.toByte() }
    val tx =
        Transaction(
            version = 2,
            txIn = listOf(TxIn(OutPoint(TxHash(prevHash), 0), ByteVector.empty, 0xffffffffL)),
            txOut = listOf(TxOut(Satoshi(valueSats), scriptPubKey)),
            lockTime = 0,
        )
    return FundingTx(txid = tx.txid.toString(), tx = Transaction.write(tx))
}

private fun spendTx(
    fundTxidDisplay: String,
    outputScript: ByteArray,
    valueSats: Long,
): ByteArray {
    val prevHash = hexToBytes(fundTxidDisplay).reversedArray()
    val tx =
        Transaction(
            version = 2,
            txIn = listOf(TxIn(OutPoint(TxHash(prevHash), 0), ByteVector.empty, 0xffffffffL)),
            txOut = listOf(TxOut(Satoshi(valueSats), outputScript)),
            lockTime = 0,
        )
    return Transaction.write(tx)
}
