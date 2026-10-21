package io.bluewallet.blueberry

import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WalletTxsMetaTest {
    private val txid = "ab".repeat(32)
    private val key = "$txid:0"

    private fun snapshot() =
        WalletTxsSnapshot(
            at = 1,
            txs = listOf(WalletTxRow(txid, "ab", 1, "", 100, "")),
            utxos =
                listOf(
                    WalletUtxoRow(
                        key = key,
                        txid = txid,
                        vout = 0,
                        outpointShort = "ab:0",
                        valueSats = 100,
                        scriptPubKey = ByteArray(0),
                        amountLabel = "",
                        height = 1,
                        ageLabel = "",
                        valueBar = "",
                        name = null,
                        isChange = false,
                    ),
                ),
            utxosReady = true,
        )

    @Test
    fun rebuild_started_before_a_freeze_keeps_the_freeze() {
        val db = createSqliteDatabase(":memory:")
        val store = createWalletTxsStore()
        val stale = snapshot()
        db.utxoFrozen.freeze(key)
        store.applyFromDb(db, stale)
        assertTrue(
            store
                .get()
                .utxos
                .single()
                .isFrozen,
        )
        db.close()
    }

    @Test
    fun coin_rename_reaches_the_tx_caption() {
        val db = createSqliteDatabase(":memory:")
        val store = createWalletTxsStore()
        store.apply(snapshot())
        db.utxoNames.upsert(key, "rent")
        store.update { withStoredUtxoMeta(db, it) }
        assertEquals(
            "rent",
            store
                .get()
                .utxos
                .single()
                .name,
        )
        assertEquals(
            "rent",
            store
                .get()
                .txs
                .single()
                .utxoLabel,
        )
        db.close()
    }

    @Test
    fun stored_freeze_blocks_spend_even_if_row_is_stale() {
        val utxos = snapshot().utxos
        assertEquals(emptyList(), spendableSelectedKeys(utxos, setOf(key), storedFrozen = setOf(key)))
        assertEquals(listOf(key), spendableSelectedKeys(utxos, setOf(key)))
    }
}
