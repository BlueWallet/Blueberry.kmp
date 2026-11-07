package io.bluewallet.blueberry

import io.bluewallet.blueberry.peers.BLOCKS_PER_PEER_KEY
import io.bluewallet.blueberry.peers.loadBlocksPerPeer
import io.bluewallet.blueberry.peers.saveBlocksPerPeer
import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class BlocksPerPeerTest {
    @Test
    fun defaults_to_one_and_round_trips_without_cached_state() {
        val db = createSqliteDatabase(":memory:")
        try {
            assertEquals(1, loadBlocksPerPeer(db))
            saveBlocksPerPeer(db, 3)
            assertEquals("3", db.keyValue.get(BLOCKS_PER_PEER_KEY))
            assertEquals(3, loadBlocksPerPeer(db))
            saveBlocksPerPeer(db, 1)
            assertEquals(1, loadBlocksPerPeer(db))
        } finally {
            db.close()
        }
    }

    @Test
    fun invalid_stored_values_fall_back_to_the_private_default() {
        val db = createSqliteDatabase(":memory:")
        try {
            for (value in listOf("", "no", "0", "-1", "11", "999999999999999999")) {
                db.keyValue.set(BLOCKS_PER_PEER_KEY, value)
                assertEquals(1, loadBlocksPerPeer(db), value)
            }
            saveBlocksPerPeer(db, 0)
            assertEquals(1, loadBlocksPerPeer(db))
            saveBlocksPerPeer(db, 11)
            assertEquals(10, loadBlocksPerPeer(db))
        } finally {
            db.close()
        }
    }
}
