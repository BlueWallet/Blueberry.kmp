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
    fun an_uppercase_output_address_still_shows_its_label() {
        val script = byteArrayOf(0x00, 0x14) + ByteArray(20) { 9 }
        val address = addressFromOutputScript(script)!!
        val tx =
            Transaction(
                2L,
                listOf(TxIn(OutPoint(TxHash(ByteArray(32) { 1 }), 0L), 0xffffffffL)),
                listOf(TxOut(Satoshi(1_000L), script)),
                0L,
            )
        val row = WalletTxRow(tx.txid.toString(), "aa", 1, "", 1_000L, "")
        val labels = mapOf(canonicalBitcoinAddress(address) to "savings")
        val watch = WatchAddress("address/0", 0, false, address.uppercase(), script, AddressScriptType.P2WPKH)
        val mine = displayOutputs(row, tx, facts(mapOf(scriptHex(script) to watch), labels), null).single()
        assertEquals(address.uppercase(), mine.address)
        assertEquals("savings", mine.addressLabel)
        val foreign = facts(emptyMap(), labels)
        assertEquals("savings", displayOutputs(row, tx, foreign, address.uppercase()).single().addressLabel)
        val blank = displayOutputs(row, tx, foreign, null).single()
        assertEquals("", blank.addressLabel)
        assertNull(blank.address)
    }
}

private fun facts(
    watchByScript: Map<String, WatchAddress>,
    addressLabels: Map<String, String>,
): TxFacts =
    TxFacts(
        watchByScript,
        emptyMap(),
        emptyMap(),
        CoinLinkGraph.Empty,
        emptyMap(),
        emptyMap(),
        emptyMap(),
        emptySet(),
        emptyMap(),
        emptyMap(),
        emptyMap(),
        null,
        addressLabels,
    )
