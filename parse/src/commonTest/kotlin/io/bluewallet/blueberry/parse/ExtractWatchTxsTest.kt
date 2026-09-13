package io.bluewallet.blueberry.parse

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxHash
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import io.bluewallet.bip158.encodeCompactSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtractWatchTxsTest {
    @Test
    fun keeps_receive_ignores_unrelated_detects_witness_and_prior_utxo_spend() {
        val pubkey = watchKey0()
        val script = p2wpkhScript(pubkey)
        val receive = coinbaseLikeReceive(script, 1000)
        val unrelated = coinbaseLikeReceive(unrelatedScript(), 500, prevSalt = 1)

        val foundReceive =
            extractWatchTxs(
                decodeBlockTxs(wrapBlock(receive, unrelated)),
                listOf(script),
                mutableMapOf(),
            )
        assertEquals(listOf(receive.txid.toString()), foundReceive.map { it.txid })

        val witness = witnessSpend(pubkey.value.toByteArray())
        assertEquals(
            1,
            extractWatchTxs(decodeBlockTxs(wrapBlock(witness)), listOf(script), mutableMapOf()).size,
        )

        val prior =
            mutableMapOf(
                outpointKey(receive.txid.toString(), 0) to WatchUtxo(1000, script),
            )
        val knownSpend = knownOutpointSpend(receive.txid.toString())
        val foundSpend =
            extractWatchTxs(
                decodeBlockTxs(wrapBlock(knownSpend)),
                listOf(script),
                prior,
            )
        assertEquals(1, foundSpend.size)
        assertFalse(prior.containsKey(outpointKey(receive.txid.toString(), 0)))
    }

    @Test
    fun detects_p2pkh_spend_from_scriptSig_without_prior_utxo() {
        val pubkey = watchKey0()
        val script = p2pkhScript(pubkey)
        val spend =
            Transaction(
                2L,
                listOf(
                    TxIn(
                        OutPoint(TxHash(ByteArray(32) { 1 }), 0L),
                        ByteVector(compilePushes(ByteArray(71) { 2 }, pubkey.value.toByteArray())),
                        0xffffffffL,
                        ScriptWitness.empty,
                    ),
                ),
                listOf(TxOut(Satoshi(900), unrelatedScript())),
                0L,
            )
        assertEquals(
            1,
            extractWatchTxs(decodeBlockTxs(wrapBlock(spend)), listOf(script), mutableMapOf()).size,
        )
    }

    @Test
    fun detects_p2sh_p2wpkh_spend_from_witness_without_prior_utxo() {
        val pubkey = watchKey0()
        val script = p2shP2wpkhScript(pubkey)
        val spend = witnessSpend(pubkey.value.toByteArray())
        assertEquals(
            1,
            extractWatchTxs(decodeBlockTxs(wrapBlock(spend)), listOf(script), mutableMapOf()).size,
        )
    }

    @Test
    fun ignores_spends_unlocked_by_an_unrelated_pubkey() {
        val watched = p2pkhScript(watchKey0())
        val other = ByteArray(33) { 3 }
        other[0] = 0x02
        val spend =
            Transaction(
                2L,
                listOf(
                    TxIn(
                        OutPoint(TxHash(ByteArray(32) { 1 }), 0L),
                        ByteVector(compilePushes(ByteArray(71) { 2 }, other)),
                        0xffffffffL,
                        ScriptWitness(listOf(ByteVector(ByteArray(64)), ByteVector(other))),
                    ),
                ),
                listOf(TxOut(Satoshi(900), unrelatedScript())),
                0L,
            )
        assertTrue(
            extractWatchTxs(decodeBlockTxs(wrapBlock(spend)), listOf(watched), mutableMapOf()).isEmpty(),
        )
    }

    @Test
    fun decode_rejects_tx_count_that_cannot_fit_remaining_bytes() {
        val bytes = ByteArray(80) + encodeCompactSize(1_000_000)
        val err = assertFailsWith<IllegalArgumentException> { decodeBlockTxs(bytes) }
        assertTrue(err.message!!.contains("cannot fit"), err.message)
    }
}
