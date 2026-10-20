package io.bluewallet.blueberry.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UtxoFrozenTest {
    @Test
    fun freeze_unfreeze_list_by_outpoint() {
        val db = createSqliteDatabase(":memory:")
        val out = "aa".repeat(32) + ":0"
        assertFalse(db.utxoFrozen.isFrozen(out))
        assertEquals(emptyList(), db.utxoFrozen.list())
        db.utxoFrozen.freeze(out)
        assertTrue(db.utxoFrozen.isFrozen(out))
        assertEquals(listOf(out), db.utxoFrozen.list())
        db.utxoFrozen.freeze(out)
        assertEquals(listOf(out), db.utxoFrozen.list())
        db.utxoFrozen.unfreeze(out)
        assertFalse(db.utxoFrozen.isFrozen(out))
        assertEquals(emptyList(), db.utxoFrozen.list())
        db.close()
    }
}
