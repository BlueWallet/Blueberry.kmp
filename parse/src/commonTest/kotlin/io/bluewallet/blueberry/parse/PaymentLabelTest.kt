package io.bluewallet.blueberry.parse

import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.AddressScriptType
import io.bluewallet.blueberry.wallet.SendAmount
import io.bluewallet.blueberry.wallet.SendInputUtxo
import io.bluewallet.blueberry.wallet.WatchGaps
import io.bluewallet.blueberry.wallet.buildSignedSendTx
import io.bluewallet.blueberry.wallet.deriveWatchWallet
import io.bluewallet.blueberry.wallet.hd
import io.bluewallet.blueberry.wallet.outpointKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private const val BLUE_EXTERNAL_1 = "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g"
private const val BLUE_INTERNAL_0 = "bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el"

class PaymentLabelTest {
    @Test
    fun trims_label_and_names_change_outpoints() {
        val db = createSqliteDatabase(":memory:")
        val wallet = deriveWatchWallet(ABANDON_MNEMONIC, WatchGaps(2, 2))
        val recv = wallet.hd(AddressScriptType.P2WPKH, 0)
        val built =
            buildSignedSendTx(
                io.bluewallet.blueberry.wallet.BuildSendTxParams(
                    secret = ABANDON_MNEMONIC,
                    wallet = wallet,
                    utxos = listOf(SendInputUtxo("11".repeat(32), 0, 100_000L, recv.scriptPubKey)),
                    toAddress = BLUE_EXTERNAL_1,
                    amountSats = SendAmount.Exact(50_000L),
                    feeRateSatPerVb = 1.0,
                    changeAddress = BLUE_INTERNAL_0,
                ),
            )
        savePaymentLabel(db, built.txid, "  groceries  ", built.changeVouts)
        val row = db.txPaymentLabels.get(built.txid)
        assertEquals("groceries", row?.label)
        assertEquals(
            "change from: groceries",
            db.utxoNames.get(outpointKey(built.txid, built.changeVouts[0])),
        )
        db.close()
    }

    @Test
    fun send_max_stores_label_without_utxo_name() {
        val db = createSqliteDatabase(":memory:")
        val wallet = deriveWatchWallet(ABANDON_MNEMONIC, WatchGaps(2, 2))
        val recv = wallet.hd(AddressScriptType.P2WPKH, 0)
        val built =
            buildSignedSendTx(
                io.bluewallet.blueberry.wallet.BuildSendTxParams(
                    secret = ABANDON_MNEMONIC,
                    wallet = wallet,
                    utxos = listOf(SendInputUtxo("11".repeat(32), 0, 100_000L, recv.scriptPubKey)),
                    toAddress = BLUE_EXTERNAL_1,
                    amountSats = SendAmount.Max,
                    feeRateSatPerVb = 1.0,
                    changeAddress = BLUE_INTERNAL_0,
                ),
            )
        savePaymentLabel(db, built.txid, "empty wallet", built.changeVouts)
        assertEquals("empty wallet", db.txPaymentLabels.get(built.txid)?.label)
        assertEquals(emptyList(), db.utxoNames.list())
        db.close()
    }

    @Test
    fun rejects_a_blank_label() {
        val db = createSqliteDatabase(":memory:")
        val error =
            assertFailsWith<IllegalArgumentException> {
                savePaymentLabel(db, "aa".repeat(32), "   ", emptyList())
            }
        assertEquals("payment label is required", error.message)
        assertNull(db.txPaymentLabels.get("aa".repeat(32)))
        db.close()
    }
}
