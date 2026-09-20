package io.bluewallet.blueberry.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivateSendsTest {
    @Test
    fun upsert_round_trips_partner_order_and_coins() {
        val db = createSqliteDatabase(":memory:")
        val txid = "aa".repeat(32)
        assertNull(db.privateSends.get(txid))
        assertEquals(emptyList(), db.privateSends.list())
        val row =
            PrivateSendRow(
                txid = txid,
                partner = "ROCKETX",
                orderId = "ord-1",
                txHex = "010203",
                destination = "bc1qdest",
                refundAddress = "bc1qref",
                coins =
                    listOf(
                        PrivateSendCoin(txid = "bb".repeat(32), vout = 1, valueSats = 50_000L),
                    ),
            )
        db.privateSends.upsert(row)
        val saved = db.privateSends.get(txid)!!
        assertEquals(row.txid, saved.txid)
        assertEquals(row.partner, saved.partner)
        assertEquals(row.orderId, saved.orderId)
        assertEquals(row.txHex, saved.txHex)
        assertEquals(row.destination, saved.destination)
        assertEquals(row.refundAddress, saved.refundAddress)
        assertEquals(row.coins, saved.coins)
        assertEquals(0L, saved.confirmedInBlock)
        assertTrue(saved.createdAt > 0L)
        assertEquals(listOf(saved), db.privateSends.list())
        db.close()
    }

    @Test
    fun coins_json_round_trip() {
        val coins =
            listOf(
                PrivateSendCoin("cc".repeat(32), 0, 1L),
                PrivateSendCoin("dd".repeat(32), 2, 99L),
            )
        assertEquals(coins, decodePrivateSendCoins(encodePrivateSendCoins(coins)))
        assertEquals(emptyList(), decodePrivateSendCoins("[]"))
    }

    @Test
    fun set_confirmed_in_block_keeps_other_fields() {
        val db = createSqliteDatabase(":memory:")
        val txid = "aa".repeat(32)
        db.privateSends.upsert(
            PrivateSendRow(
                txid = txid,
                partner = "ROCKETX",
                orderId = "ord-1",
                txHex = "010203",
                destination = "bc1qdest",
                refundAddress = "bc1qref",
                coins = emptyList(),
            ),
        )
        db.privateSends.setConfirmedInBlock(txid, 800_000)
        assertEquals(800_000L, db.privateSends.get(txid)!!.confirmedInBlock)
        db.close()
    }
}
