package io.bluewallet.blueberry

import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxHash
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import io.bluewallet.blueberry.parse.CoinLinkGraph
import io.bluewallet.blueberry.parse.scriptHex
import io.bluewallet.blueberry.wallet.AddressScriptType
import io.bluewallet.blueberry.wallet.WatchAddress
import io.bluewallet.blueberry.wallet.addressFromOutputScript
import io.bluewallet.blueberry.wallet.canonicalBitcoinAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TxWalletPartsTest {
    @Test
    fun an_uppercase_watch_address_still_shows_its_label() {
        val out = labelledOutput()
        val watch =
            WatchAddress("address/0", 0, false, out.address.uppercase(), out.script, AddressScriptType.P2WPKH)
        val mine = displayOutputs(out.row, out.tx, facts(out.script to watch, out.address), destination = null).single()
        assertEquals(out.address.uppercase(), mine.address)
        assertEquals("savings", mine.addressLabel)
    }

    @Test
    fun an_uppercase_send_destination_still_shows_its_label() {
        val out = labelledOutput()
        val facts = facts(address = out.address)
        val paid = displayOutputs(out.row, out.tx, facts, destination = out.address.uppercase()).single()
        assertEquals("savings", paid.addressLabel)
        val blank = displayOutputs(out.row, out.tx, facts, destination = null).single()
        assertEquals("", blank.addressLabel)
        assertNull(blank.address)
    }
}

private data class LabelledOutput(
    val row: WalletTxRow,
    val tx: Transaction,
    val address: String,
    val script: ByteArray,
)

private fun labelledOutput(): LabelledOutput {
    val script = byteArrayOf(0x00, 0x14) + ByteArray(20) { 9 }
    val address = addressFromOutputScript(script)!!
    val tx =
        Transaction(
            2L,
            listOf(TxIn(OutPoint(TxHash(ByteArray(32) { 1 }), 0L), 0xffffffffL)),
            listOf(TxOut(Satoshi(1_000L), script)),
            0L,
        )
    return LabelledOutput(WalletTxRow(tx.txid.toString(), "aa", 1, "", 1_000L, ""), tx, address, script)
}

private fun facts(
    watch: Pair<ByteArray, WatchAddress>? = null,
    address: String,
): TxFacts =
    TxFacts(
        watchByScript = watch?.let { mapOf(scriptHex(it.first) to it.second) }.orEmpty(),
        groups = emptyMap(),
        utxos = emptyMap(),
        graph = CoinLinkGraph.Empty,
        spends = emptyMap(),
        labels = emptyMap(),
        names = emptyMap(),
        frozen = emptySet(),
        sends = emptyMap(),
        broadcast = emptyMap(),
        stored = emptyMap(),
        feeRateSatPerVb = null,
        addressLabels = mapOf(canonicalBitcoinAddress(address) to "savings"),
    )
