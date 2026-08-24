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
                    "transactions",
                    "tx_payment_labels",
                    "utxo_names",
                ),
                tableNames(driver),
            )
            assertEquals(
                listOf(
                    "host", "port", "services", "alive", "used_for_blocks",
                    "last_probed_at", "created_at", "updated_at",
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
                    "txid", "height", "tx_index", "block_hash_internal_hex",
                    "tx", "net_delta_sats",
                ),
                columnNames(driver, "transactions"),
            )
            assertEquals(listOf("key", "value"), columnNames(driver, "key_value"))
            assertEquals(listOf("outpoint", "name"), columnNames(driver, "utxo_names"))
            assertEquals(listOf("txid", "label"), columnNames(driver, "tx_payment_labels"))
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
        } finally {
            driver.close()
        }
    }

    @Test
    fun applySchema_adds_tx_payment_labels_to_existing_db() {
        val driver = openSqliteDriver(":memory:")
        try {
            driver.execute(
                identifier = null,
                sql = """
                    CREATE TABLE peers (
                      host TEXT NOT NULL,
                      port INTEGER NOT NULL,
                      services INTEGER NOT NULL DEFAULT 0,
                      alive INTEGER NOT NULL DEFAULT 0,
                      used_for_blocks INTEGER NOT NULL DEFAULT 0,
                      last_probed_at INTEGER,
                      created_at INTEGER NOT NULL,
                      updated_at INTEGER NOT NULL,
                      PRIMARY KEY (host, port)
                    )
                """.trimIndent(),
                parameters = 0,
            )
            applySchema(driver)
            assertEquals(listOf("txid", "label"), columnNames(driver, "tx_payment_labels"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun schema_sql_matches_helix3() {
        val driver = openSqliteDriver(":memory:")
        applySchema(driver)
        try {
            for ((name, expected) in helix3TableSql) {
                assertEquals(
                    normalizeSql(expected),
                    normalizeSql(masterSql(driver, "table", name)),
                    "table $name",
                )
            }
            for ((name, expected) in helix3IndexSql) {
                assertEquals(
                    normalizeSql(expected),
                    normalizeSql(masterSql(driver, "index", name)),
                    "index $name",
                )
            }
        } finally {
            driver.close()
        }
    }
}

private val helix3TableSql = mapOf(
    "peers" to """
        CREATE TABLE peers (
          host TEXT NOT NULL,
          port INTEGER NOT NULL,
          services INTEGER NOT NULL DEFAULT 0,
          alive INTEGER NOT NULL DEFAULT 0,
          used_for_blocks INTEGER NOT NULL DEFAULT 0,
          last_probed_at INTEGER,
          created_at INTEGER NOT NULL,
          updated_at INTEGER NOT NULL,
          PRIMARY KEY (host, port)
        )
    """.trimIndent(),
    "headers" to """
        CREATE TABLE headers (
          height INTEGER PRIMARY KEY,
          hash_internal_hex TEXT NOT NULL,
          header BLOB NOT NULL,
          cumulative_work TEXT NOT NULL DEFAULT '0'
        )
    """.trimIndent(),
    "filter_headers" to """
        CREATE TABLE filter_headers (
          height INTEGER PRIMARY KEY,
          header BLOB NOT NULL
        )
    """.trimIndent(),
    "filters" to """
        CREATE TABLE filters (
          height INTEGER PRIMARY KEY,
          block_hash_internal_hex TEXT NOT NULL,
          filter BLOB NOT NULL
        )
    """.trimIndent(),
    "filters_unscanned" to """
        CREATE TABLE filters_unscanned (
          height INTEGER PRIMARY KEY
        )
    """.trimIndent(),
    "matched_blocks" to """
        CREATE TABLE matched_blocks (
          height INTEGER PRIMARY KEY,
          block_hash_internal_hex TEXT NOT NULL
        )
    """.trimIndent(),
    "blocks" to """
        CREATE TABLE blocks (
          height INTEGER PRIMARY KEY,
          block_hash_internal_hex TEXT NOT NULL,
          block BLOB NOT NULL
        )
    """.trimIndent(),
    "parsed_blocks" to """
        CREATE TABLE parsed_blocks (
          height INTEGER PRIMARY KEY
        )
    """.trimIndent(),
    "transactions" to """
        CREATE TABLE transactions (
          txid TEXT PRIMARY KEY,
          height INTEGER NOT NULL,
          tx_index INTEGER NOT NULL,
          block_hash_internal_hex TEXT NOT NULL,
          tx BLOB NOT NULL,
          net_delta_sats INTEGER NOT NULL
        )
    """.trimIndent(),
    "key_value" to """
        CREATE TABLE key_value (
          key TEXT PRIMARY KEY,
          value TEXT NOT NULL
        )
    """.trimIndent(),
    "utxo_names" to """
        CREATE TABLE utxo_names (
          outpoint TEXT PRIMARY KEY,
          name TEXT NOT NULL
        )
    """.trimIndent(),
    "tx_payment_labels" to """
        CREATE TABLE tx_payment_labels (
          txid TEXT PRIMARY KEY,
          label TEXT NOT NULL
        )
    """.trimIndent(),
)

private val helix3IndexSql = mapOf(
    "headers_hash_internal_hex" to
        "CREATE INDEX headers_hash_internal_hex ON headers(hash_internal_hex)",
    "filters_height_hash" to
        "CREATE INDEX filters_height_hash ON filters(height, block_hash_internal_hex)",
    "peers_alive_used" to
        "CREATE INDEX peers_alive_used ON peers(alive, used_for_blocks)",
)

private fun normalizeSql(sql: String): String =
    sql.replace(Regex("(?i)\\bIF NOT EXISTS\\b\\s*"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .removeSuffix(";")

private fun masterSql(driver: SqlDriver, type: String, name: String): String {
    val sql = queryStrings(
        driver,
        "SELECT sql FROM sqlite_master WHERE type='$type' AND name='$name'",
    ).singleOrNull()
    return sql ?: error("missing $type $name in sqlite_master")
}

private fun tableNames(driver: SqlDriver): List<String> =
    queryStrings(
        driver,
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' ORDER BY name",
    )

private fun columnNames(driver: SqlDriver, table: String): List<String> =
    queryStrings(driver, "SELECT name FROM pragma_table_info('$table') ORDER BY cid")

private fun indexColumns(driver: SqlDriver, index: String): List<String> =
    queryStrings(driver, "SELECT name FROM pragma_index_info('$index') ORDER BY seqno")

private fun queryStrings(driver: SqlDriver, sql: String): List<String> {
    return driver.executeQuery(
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
}
