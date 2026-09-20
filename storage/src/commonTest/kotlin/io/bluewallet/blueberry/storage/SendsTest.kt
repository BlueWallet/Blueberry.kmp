package io.bluewallet.blueberry.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendsTest {
    @Test
    fun upsert_round_trips_hex_destination_and_coins() {
        val db = createSqliteDatabase(":memory:")
        val txid = "aa".repeat(32)
        assertNull(db.sends.get(txid))
        assertEquals(emptyList(), db.sends.list())
        val row =
            SendRow(
                txid = txid,
                txHex = "010203",
                destination = "bc1qdest",
                coins =
                    listOf(
                        PrivateSendCoin(txid = "bb".repeat(32), vout = 1, valueSats = 50_000L),
                    ),
            )
        db.sends.upsert(row)
        val saved = db.sends.get(txid)!!
        assertEquals(row.txid, saved.txid)
        assertEquals(row.txHex, saved.txHex)
        assertEquals(row.destination, saved.destination)
        assertEquals(row.coins, saved.coins)
        assertEquals(0L, saved.confirmedInBlock)
        assertTrue(saved.createdAt > 0L)
        assertEquals(listOf(saved), db.sends.list())
        db.close()
    }

    @Test
    fun set_confirmed_in_block_keeps_other_fields() {
        val db = createSqliteDatabase(":memory:")
        val txid = "aa".repeat(32)
        val row =
            SendRow(
                txid = txid,
                txHex = "010203",
                destination = "bc1qdest",
                coins = emptyList(),
            )
        db.sends.upsert(row)
        db.sends.setConfirmedInBlock(txid, 800_000)
        val saved = db.sends.get(txid)!!
        assertEquals(800_000L, saved.confirmedInBlock)
        assertEquals("010203", saved.txHex)
        db.close()
    }
}
