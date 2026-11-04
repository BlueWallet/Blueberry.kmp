package io.bluewallet.blueberry

import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxHash
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.PrivateSendCoin
import io.bluewallet.blueberry.storage.SendRow
import io.bluewallet.blueberry.storage.StoredTx
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.AddressScriptType
import io.bluewallet.blueberry.wallet.Wallet
import io.bluewallet.blueberry.wallet.addressFromOutputScript
import io.bluewallet.blueberry.wallet.createWallet
import io.bluewallet.blueberry.wallet.deriveWatchWallet
import io.bluewallet.blueberry.wallet.hexFromBytes
import io.bluewallet.blueberry.wallet.saveWalletSecret
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The bridge from stored wallet rows to the display model. */
class TxWalletDisplayTest {
    private val db = createSqliteDatabase(":memory:")
    private val wallet = walletIn(db)
    private val receiveScript = deriveWatchWallet(WIF).addresses.first { it.scriptType == AddressScriptType.P2PKH }.scriptPubKey

    @AfterTest
    fun close() = db.close()

    @Test
    fun home_sections_put_the_pending_send_first_in_today() {
        val fund = storeFunding(9_664L)
        val send = storeSend(fund)
        val sections = txHomeSections(query())
        val items = sections.flatMap { it.items }
        assertEquals("Today", sections.first().title)
        assertEquals(send.txid.toString(), items.first().txid)
        assertTrue(items.first().pending)
        assertEquals(TxGlyph.Sent, items.first().row.glyph)
        val received = items.single { it.txid == fund.txid.toString() }
        assertEquals(TxGlyph.Received, received.row.glyph)
        assertFalse(received.pending)
    }

    @Test
    fun tor_reveal_shows_only_once_the_path_is_recorded() {
        val send = storeSend(storeFunding(9_664L)).txid.toString()
        assertFalse(TOR_REVEAL in revealTitles(send))
        db.broadcastVia.upsert(send, BROADCAST_VIA_TOR)
        assertTrue(TOR_REVEAL in revealTitles(send))
    }

    @Test
    fun dust_needs_a_saved_fee_rate_to_judge_spend_cost() {
        val fund = storeFunding(600L).txid.toString()
        assertFalse(DUST_REVEAL in revealTitles(fund))
        saveSendFeeRate(db, 10.0)
        assertTrue(DUST_REVEAL in revealTitles(fund))
    }

    @Test
    fun replaced_send_is_struck_and_titled_replaced() {
        val send = storeSend(storeFunding(9_664L)).txid.toString()
        val q = query()
        val row =
            q.snap.txs
                .single { it.txid == send }
                .copy(replaced = true, replacementTxid = "cd".repeat(32))
        val screen = txDetailOf(q, row, null)!!
        assertEquals("Replaced", screen.detail.status.title)
        assertEquals(TxListSubtitle.Status("Replaced"), screen.list.subtitle)
        assertTrue(screen.list.struck)
        assertEquals(TxGlyph.Failed, screen.list.glyph)
    }

    private fun query(): TxQuery {
        val now = nowMillis()
        return TxQuery(db, wallet, snapshotFromDb(db, now, now, wallet), tipHeight = FUND_HEIGHT, nowEpochS = now / 1000)
    }

    private fun revealTitles(txid: String): List<String> {
        val q = query()
        val row = q.snap.txs.single { it.txid == txid }
        return txDetailOf(q, row, null)!!.detail.reveals.map { it.title }
    }

    private fun storeFunding(sats: Long): Transaction {
        val fund =
            Transaction(
                2L,
                listOf(TxIn(OutPoint(TxHash(ByteArray(32) { 42 }), 0L), 0xffffffffL)),
                listOf(TxOut(Satoshi(sats), receiveScript)),
                0L,
            )
        db.transactions.upsert(StoredTx(fund.txid.toString(), FUND_HEIGHT, 0, "aa".repeat(32), Transaction.write(fund), sats))
        return fund
    }

    private fun storeSend(fund: Transaction): Transaction {
        val value = fund.txOut[0].amount.toLong()
        val send =
            Transaction(
                2L,
                listOf(TxIn(OutPoint(fund, 0L), 0xfffffffdL)),
                listOf(TxOut(Satoshi(value - 500L), PAYEE_SCRIPT)),
                0L,
            )
        db.sends.upsert(
            SendRow(
                txid = send.txid.toString(),
                txHex = hexFromBytes(Transaction.write(send)),
                destination = addressFromOutputScript(PAYEE_SCRIPT)!!,
                coins = listOf(PrivateSendCoin(fund.txid.toString(), 0, value)),
            ),
        )
        return send
    }
}

private const val WIF = "L4ccWrPMmFDZw4kzAKFqJNxgHANjdy6b7YKNXMwB4xac4FLF3Tov"
private const val FUND_HEIGHT = 800_000
private const val TOR_REVEAL = "Broadcast over Tor"
private const val DUST_REVEAL = "Looks like dusting"
private val PAYEE_SCRIPT = byteArrayOf(0x00, 0x14) + ByteArray(20) { 7 }

private fun walletIn(db: Database): Wallet {
    saveWalletSecret(db, WIF)
    return createWallet(db)
}
