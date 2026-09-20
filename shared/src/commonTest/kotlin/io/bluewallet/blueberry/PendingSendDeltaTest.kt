package io.bluewallet.blueberry

import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxHash
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import io.bluewallet.blueberry.parse.inferPendingSendNetDelta
import io.bluewallet.blueberry.storage.PrivateSendCoin
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.createWallet
import io.bluewallet.blueberry.wallet.hexFromBytes
import io.bluewallet.blueberry.wallet.saveWalletSecret
import kotlin.test.Test
import kotlin.test.assertEquals

class PendingSendDeltaTest {
    @Test
    fun net_delta_is_change_minus_spent_coins() {
        val db = createSqliteDatabase(":memory:")
        saveWalletSecret(
            db,
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        )
        val wallet = createWallet(db)
        val dest = wallet.snapshot().addresses.first { !it.change && it.index == 1 }
        val change = wallet.snapshot().addresses.first { it.change && it.index == 0 }
        val tx =
            Transaction(
                2L,
                listOf(TxIn(OutPoint(TxHash(ByteArray(32).also { it[0] = 1 }), 0L), 0xffffffffL)),
                listOf(
                    TxOut(Satoshi(40_000L), dest.scriptPubKey),
                    TxOut(Satoshi(9_000L), change.scriptPubKey),
                ),
                0L,
            )
        assertEquals(
            -41_000L,
            inferPendingSendNetDelta(
                coins = listOf(PrivateSendCoin("aa".repeat(32), 0, 50_000L)),
                destination = dest.address,
                txHex = hexFromBytes(Transaction.write(tx)),
            ),
        )
        db.close()
    }

    @Test
    fun private_send_without_dest_output_keeps_refund_as_change() {
        val db = createSqliteDatabase(":memory:")
        saveWalletSecret(
            db,
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        )
        val wallet = createWallet(db)
        val dest = wallet.snapshot().addresses.first { !it.change && it.index == 1 }
        val refund = wallet.snapshot().addresses.first { it.change && it.index == 0 }
        val rocket = wallet.snapshot().addresses.first { !it.change && it.index == 2 }
        val tx =
            Transaction(
                2L,
                listOf(TxIn(OutPoint(TxHash(ByteArray(32).also { it[0] = 2 }), 0L), 0xffffffffL)),
                listOf(
                    TxOut(Satoshi(40_000L), rocket.scriptPubKey),
                    TxOut(Satoshi(9_000L), refund.scriptPubKey),
                ),
                0L,
            )
        assertEquals(
            -41_000L,
            inferPendingSendNetDelta(
                coins = listOf(PrivateSendCoin("aa".repeat(32), 0, 50_000L)),
                destination = dest.address,
                txHex = hexFromBytes(Transaction.write(tx)),
                keepAddresses = listOf(refund.address),
            ),
        )
        db.close()
    }
}
