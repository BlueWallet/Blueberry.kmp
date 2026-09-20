package io.bluewallet.blueberry.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        assertEquals(row, db.privateSends.get(txid))
        assertEquals(listOf(row), db.privateSends.list())
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
}
