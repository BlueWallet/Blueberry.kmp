package io.bluewallet.blueberry.parse

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxHash
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import io.bluewallet.blueberry.wallet.AddressScriptType
import io.bluewallet.blueberry.wallet.WatchAddress
import io.bluewallet.blueberry.wallet.WatchWallet
import io.bluewallet.blueberry.wallet.WatchWalletKind
import io.bluewallet.blueberry.wallet.deriveWatchWallet
import io.bluewallet.blueberry.wallet.hd
import kotlin.test.Test
import kotlin.test.assertEquals

class UsedWatchIndexesTest {
    @Test
    fun detects_external_receive_and_internal_change() {
        val wallet = deriveWatchWallet(ABANDON_MNEMONIC, 5)
        val ext = wallet.hd(AddressScriptType.P2WPKH, 2)
        val intern = wallet.hd(AddressScriptType.P2WPKH, 1, change = true)
        val receive = coinbaseLikeReceive(ext.scriptPubKey, 1000)
        val change = coinbaseLikeReceive(intern.scriptPubKey, 500, prevSalt = 1)
        val used =
            usedWatchIndexes(
                listOf(Transaction.write(receive), Transaction.write(change)),
                wallet,
            )
        assertEquals(listOf(2), used.get(AddressScriptType.P2WPKH).external)
        assertEquals(listOf(1), used.get(AddressScriptType.P2WPKH).internal)
    }

    @Test
    fun used_index_on_bip44_does_not_mark_bip84() {
        val wallet = deriveWatchWallet(ABANDON_MNEMONIC, 3)
        val bip44 = wallet.hd(AddressScriptType.P2PKH, 0)
        val receive = coinbaseLikeReceive(bip44.scriptPubKey, 1000)
        val used = usedWatchIndexes(listOf(Transaction.write(receive)), wallet)
        assertEquals(listOf(0), used.get(AddressScriptType.P2PKH).external)
        assertEquals(emptyList(), used.get(AddressScriptType.P2WPKH).external)
    }

    @Test
    fun detects_p2wpkh_spend_via_witness() {
        val wallet =
            deriveWatchWallet(
                ABANDON_MNEMONIC,
                io.bluewallet.blueberry.wallet
                    .WatchGaps(3, 1),
            )
        val pubkey = watchPubkey0("m/84'/0'/0'/0/2")
        val spend = witnessSpend(pubkey, valueSats = 1)
        val used = usedWatchIndexes(listOf(Transaction.write(spend)), wallet)
        assertEquals(listOf(2), used.get(AddressScriptType.P2WPKH).external)
    }

    @Test
    fun detects_p2pkh_spend_via_scriptSig_without_prior_receive() {
        val pubkey = watchKey0()
        val script = p2pkhScript(pubkey)
        val wallet =
            WatchWallet(
                kind = WatchWalletKind.ADDRESS,
                secret = "addr",
                addresses =
                    listOf(
                        WatchAddress(
                            path = "address/0",
                            index = 0,
                            change = false,
                            address = "addr",
                            scriptPubKey = script,
                            scriptType = AddressScriptType.P2PKH,
                        ),
                    ),
                scripts = listOf(script),
            )
        val spend =
            Transaction(
                2L,
                listOf(
                    TxIn(
                        OutPoint(TxHash(ByteArray(32) { 1 }), 0L),
                        ByteVector(compilePushes(ByteArray(71) { 2 }, pubkey.value.toByteArray())),
                        0xffffffffL,
                        fr.acinq.bitcoin.ScriptWitness.empty,
                    ),
                ),
                listOf(TxOut(Satoshi(1), unrelatedScript())),
                0L,
            )
        val used = usedWatchIndexes(listOf(Transaction.write(spend)), wallet)
        assertEquals(listOf(0), used.get(AddressScriptType.P2PKH).external)
    }

    @Test
    fun detects_spend_via_prevout_in_same_batch_without_witness() {
        val wallet =
            deriveWatchWallet(
                ABANDON_MNEMONIC,
                io.bluewallet.blueberry.wallet
                    .WatchGaps(3, 1),
            )
        val ext = wallet.hd(AddressScriptType.P2WPKH, 2)
        val receive = coinbaseLikeReceive(ext.scriptPubKey, 1000)
        val spend = knownOutpointSpend(receive.txid.toString())
        val used =
            usedWatchIndexes(
                listOf(Transaction.write(receive), Transaction.write(spend)),
                wallet,
            )
        assertEquals(listOf(2), used.get(AddressScriptType.P2WPKH).external)
    }
}
