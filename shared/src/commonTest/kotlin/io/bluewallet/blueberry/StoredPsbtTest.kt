package io.bluewallet.blueberry

import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxHash
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import io.bluewallet.blueberry.storage.PrivateSendCoin
import io.bluewallet.blueberry.storage.SendRow
import io.bluewallet.blueberry.storage.StoredTx
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.AddressScriptType
import io.bluewallet.blueberry.wallet.BuildSendTxParams
import io.bluewallet.blueberry.wallet.SendAmount
import io.bluewallet.blueberry.wallet.SendInputUtxo
import io.bluewallet.blueberry.wallet.buildUnsignedSendPsbt
import io.bluewallet.blueberry.wallet.createWallet
import io.bluewallet.blueberry.wallet.saveWalletSecret
import kotlin.test.Test
import kotlin.test.assertEquals

class StoredPsbtTest {
    @Test
    fun a_stored_psbt_is_rebuilt_from_its_payment_and_a_signed_one_is_not() {
        val db = createSqliteDatabase(":memory:")
        val secret = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        saveWalletSecret(db, secret)
        val wallet = createWallet(db)
        val recv = wallet.snapshot().addresses.first { it.scriptType == AddressScriptType.P2WPKH && !it.change && it.index == 0 }
        val dest = wallet.snapshot().addresses.first { it.scriptType == AddressScriptType.P2WPKH && !it.change && it.index == 1 }
        val change = wallet.snapshot().addresses.first { it.scriptType == AddressScriptType.P2WPKH && it.change && it.index == 0 }
        val parent =
            Transaction(
                2L,
                listOf(TxIn(OutPoint(TxHash(ByteArray(32) { 7 }), 0L), 0xffffffffL)),
                listOf(TxOut(Satoshi(100_000L), recv.scriptPubKey)),
                0L,
            )
        val parentTxid = parent.txid.toString()
        db.transactions.upsert(StoredTx(parentTxid, 100, 0, "11".repeat(32), Transaction.write(parent), 100_000L))
        val built =
            buildUnsignedSendPsbt(
                BuildSendTxParams(
                    secret = secret,
                    wallet = wallet.snapshot(),
                    utxos = listOf(SendInputUtxo(parentTxid, 0, 100_000L, recv.scriptPubKey)),
                    toAddress = dest.address,
                    amountSats = SendAmount.Exact(40_000L),
                    feeRateSatPerVb = 1.0,
                    changeAddress = change.address,
                ),
            )
        val coins = listOf(PrivateSendCoin(parentTxid, 0, 100_000L))
        db.sends.upsert(SendRow(built.txid, built.unsignedTxHex, dest.address, coins))

        assertEquals(true, storedSendIsPsbt(db, built.txid))
        assertEquals(built.psbtHex, (loadStoredPsbt(db, wallet, built.txid) as StoredPsbt.Ready).psbtHex)
        assertEquals(StoredPsbt.Failed(WALLET_NOT_LOADED), loadStoredPsbt(db, null, built.txid))
        assertEquals(StoredPsbt.Failed(NO_STORED_PSBT), loadStoredPsbt(db, wallet, "ff".repeat(32)))
        db.close()
    }
}
