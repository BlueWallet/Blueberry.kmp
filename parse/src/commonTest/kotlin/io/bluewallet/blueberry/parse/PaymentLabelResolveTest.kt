package io.bluewallet.blueberry.parse

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxHash
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import io.bluewallet.blueberry.wallet.addressFromOutputScript
import io.bluewallet.blueberry.wallet.outpointKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaymentLabelResolveTest {
    @Test
    fun indexes_first_nonblank_label_per_txid_by_lowest_vout() {
        val txA = "aa".repeat(32)
        val txB = "bb".repeat(32)
        val labels =
            firstUtxoLabelByTxid(
                mapOf(
                    outpointKey(txA, 1) to "later",
                    outpointKey(txA, 0) to "  first  ",
                    outpointKey(txB, 2) to "only",
                    outpointKey(txB, 1) to "   ",
                    "not-an-outpoint" to "skip",
                ),
            )
        assertEquals(mapOf(txA to "first", txB to "only"), labels)
    }

    @Test
    fun first_address_label_takes_lowest_labelled_vout() {
        val scripts = (1..4).map { p2wpkh(it) }
        val opReturn = byteArrayOf(0x6a, 0x01, 0x00)
        val tx = txPaying(listOf(scripts[0], opReturn, scripts[1], scripts[2], scripts[3]))
        val labels =
            mapOf(
                address(scripts[1]) to "   ",
                address(scripts[2]) to "  b  ",
                address(scripts[3]) to "a",
            )
        assertEquals("b", firstAddressLabel(tx, labels))
        assertNull(firstAddressLabel(tx, mapOf(address(p2wpkh(9)) to "other")))
        assertNull(firstAddressLabel(byteArrayOf(0x00, 0x01), labels))
    }

    @Test
    fun spend_without_a_stored_parent_uses_the_native_p2wpkh_input() {
        val pubkey = watchKey0()
        val spend = witnessSpend(pubkey.value.toByteArray())
        val labels = mapOf(addressFromOutputScript(p2wpkhScript(pubkey))!! to "savings")
        val raw = Transaction.write(spend)
        assertEquals("savings", firstAddressLabel(raw, labels))
        val touches = txTouchAddresses(listOf(spend.txid.toString() to raw))
        assertEquals("savings", touchLabel(touches.getValue(spend.txid.toString()), labels))
    }

    @Test
    fun nested_p2sh_p2wpkh_spend_does_not_use_the_native_address() {
        val pubkey = watchKey0()
        val spend =
            Transaction(
                2L,
                listOf(
                    TxIn(
                        OutPoint(TxHash(ByteArray(32) { 1 }), 0L),
                        ByteVector(compilePushes(Script.write(Script.pay2wpkh(pubkey)))),
                        0xffffffffL,
                        ScriptWitness(listOf(ByteVector(ByteArray(64)), ByteVector(pubkey.value.toByteArray()))),
                    ),
                ),
                listOf(TxOut(Satoshi(900), unrelatedScript())),
                0L,
            )
        val p2sh = addressFromOutputScript(p2shP2wpkhScript(pubkey))!!
        val native = addressFromOutputScript(p2wpkhScript(pubkey))!!
        val touches = txTouchAddresses(listOf(spend.txid.toString() to Transaction.write(spend)))
        val addrs = touches.getValue(spend.txid.toString())
        assertTrue(p2sh in addrs)
        assertFalse(native in addrs)
        assertEquals("savings", touchLabel(addrs, mapOf(p2sh to "savings", native to "payroll")))
        assertNull(firstAddressLabel(Transaction.write(witnessSpend(pubkey.value.toByteArray())), mapOf(p2sh to "savings")))
    }

    @Test
    fun first_address_label_matches_an_uppercase_bech32_key() {
        val pubkey = watchKey0()
        val spend = witnessSpend(pubkey.value.toByteArray())
        val addr = addressFromOutputScript(p2wpkhScript(pubkey))!!
        assertEquals("savings", firstAddressLabel(Transaction.write(spend), mapOf(addr.uppercase() to "savings")))
    }

    @Test
    fun unreadable_transactions_still_have_a_touch_cache_key() {
        val pubkey = watchKey0()
        val spend = witnessSpend(pubkey.value.toByteArray())
        val raw = Transaction.write(spend)
        val bad = byteArrayOf(0x00, 0x01)
        val touches = txTouchAddresses(listOf(spend.txid.toString() to raw, "dead" to bad))
        assertEquals("savings", touchLabel(touches.getValue(spend.txid.toString()), mapOf(addressFromOutputScript(p2wpkhScript(pubkey))!! to "savings")))
        assertEquals(emptyList(), touches.getValue("dead"))
        assertNull(firstAddressLabel(bad, mapOf(addressFromOutputScript(p2wpkhScript(pubkey))!! to "savings")))
    }

    @Test
    fun taproot_script_path_control_block_is_not_treated_as_a_p2wpkh_spend() {
        val control = byteArrayOf(0xc0.toByte()) + ByteArray(32) { 1 }
        val two =
            TxIn(
                OutPoint(TxHash(ByteArray(32) { 1 }), 0L),
                ByteVector.empty,
                0xffffffffL,
                ScriptWitness(listOf(ByteVector(ByteArray(64)), ByteVector(control))),
            )
        val three =
            TxIn(
                OutPoint(TxHash(ByteArray(32) { 2 }), 0L),
                ByteVector.empty,
                0xffffffffL,
                ScriptWitness(listOf(ByteVector(ByteArray(64)), ByteVector(byteArrayOf(0x51)), ByteVector(control))),
            )
        assertNull(spentScriptFromInput(two))
        assertNull(spentScriptFromInput(three))
        val pubkey = watchKey0().value.toByteArray()
        val native =
            TxIn(
                OutPoint(TxHash(ByteArray(32) { 1 }), 0L),
                ByteVector.empty,
                0xffffffffL,
                ScriptWitness(listOf(ByteVector(ByteArray(64)), ByteVector(pubkey))),
            )
        assertEquals(p2wpkhScriptFromPubkey(pubkey).toList(), spentScriptFromInput(native)?.toList())
    }
}

private fun p2wpkh(seed: Int): ByteArray = byteArrayOf(0x00, 0x14) + ByteArray(20) { seed.toByte() }

private fun address(script: ByteArray): String = addressFromOutputScript(script)!!

private fun txPaying(scripts: List<ByteArray>): ByteArray =
    Transaction.write(
        Transaction(
            2L,
            listOf(TxIn(OutPoint(TxHash(ByteArray(32)), 0L), 0xffffffffL)),
            scripts.map { TxOut(Satoshi(1_000L), it) },
            0L,
        ),
    )
