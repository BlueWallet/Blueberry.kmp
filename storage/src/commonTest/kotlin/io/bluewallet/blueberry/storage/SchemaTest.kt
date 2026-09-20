package io.bluewallet.blueberry.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaTest {
    @Test
    fun tables_columns_and_indexes_match_helix3() {
        val driver = openSqliteDriver(":memory:")
        applySchema(driver)
        applyPragmas(driver)
        try {
            assertEquals(
                listOf(
                    "blocks",
                    "filter_headers",
                    "filters",
                    "filters_unscanned",
                    "headers",
                    "key_value",
                    "matched_blocks",
                    "parsed_blocks",
                    "peers",
                    "private_sends",
                    "sends",
                    "transactions",
                    "tx_payment_labels",
                    "utxo_names",
                ),
                tableNames(driver),
            )
            assertEquals(
                listOf(
                    "host",
                    "port",
                    "services",
                    "alive",
                    "used_for_blocks",
                    "last_probed_at",
                    "created_at",
                    "updated_at",
                ),
                columnNames(driver, "peers"),
            )
            assertEquals(
                listOf("height", "hash_internal_hex", "header", "cumulative_work"),
                columnNames(driver, "headers"),
            )
            assertEquals(listOf("height", "header"), columnNames(driver, "filter_headers"))
            assertEquals(
                listOf("height", "block_hash_internal_hex", "filter"),
                columnNames(driver, "filters"),
            )
            assertEquals(listOf("height"), columnNames(driver, "filters_unscanned"))
            assertEquals(
                listOf("height", "block_hash_internal_hex"),
                columnNames(driver, "matched_blocks"),
            )
            assertEquals(
                listOf("height", "block_hash_internal_hex", "block"),
                columnNames(driver, "blocks"),
            )
            assertEquals(listOf("height"), columnNames(driver, "parsed_blocks"))
            assertEquals(
                listOf(
                    "txid",
                    "height",
                    "tx_index",
                    "block_hash_internal_hex",
                    "tx",
                    "net_delta_sats",
                ),
                columnNames(driver, "transactions"),
            )
            assertEquals(listOf("key", "value"), columnNames(driver, "key_value"))
            assertEquals(listOf("outpoint", "name"), columnNames(driver, "utxo_names"))
            assertEquals(listOf("txid", "label"), columnNames(driver, "tx_payment_labels"))
            assertEquals(
                listOf(
                    "txid",
                    "partner",
                    "order_id",
                    "tx_hex",
                    "destination",
                    "refund_address",
                    "utxos",
                    "confirmed_in_block",
                    "created_at",
                ),
                columnNames(driver, "private_sends"),
            )
            assertEquals(
                listOf(
                    "txid",
                    "tx_hex",
                    "destination",
                    "utxos",
                    "confirmed_in_block",
                    "created_at",
                ),
                columnNames(driver, "sends"),
            )
            assertEquals(
                listOf("height", "block_hash_internal_hex"),
                indexColumns(driver, "filters_height_hash"),
            )
            assertEquals(
                listOf("hash_internal_hex"),
                indexColumns(driver, "headers_hash_internal_hex"),
            )
            assertEquals(
                listOf("alive", "used_for_blocks"),
                indexColumns(driver, "peers_alive_used"),
            )
            assertEquals(
                listOf(
                    "alive",
                    "used_for_blocks",
                    "last_probed_at",
                    "<expression>",
                    "host",
                    "port",
                ),
                indexColumns(driver, "peers_dead_retry"),
            )
        } finally {
            driver.close()
        }
    }
}

private fun tableNames(driver: SqlDriver): List<String> =
    queryStrings(
        driver,
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' ORDER BY name",
    )

private fun columnNames(
    driver: SqlDriver,
    table: String,
): List<String> = queryStrings(driver, "SELECT name FROM pragma_table_info('$table') ORDER BY cid")

private fun indexColumns(
    driver: SqlDriver,
    index: String,
): List<String> =
    queryStrings(
        driver,
        "SELECT COALESCE(name, '<expression>') FROM pragma_index_info('$index') ORDER BY seqno",
    )

private fun queryStrings(
    driver: SqlDriver,
    sql: String,
): List<String> =
    driver
        .executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                val out = mutableListOf<String>()
                while (cursor.next().value) {
                    out.add(cursor.getString(0)!!)
                }
                QueryResult.Value(out)
            },
            parameters = 0,
        ).value
