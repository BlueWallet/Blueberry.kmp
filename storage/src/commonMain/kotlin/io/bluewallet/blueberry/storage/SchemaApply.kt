package io.bluewallet.blueberry.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

internal fun applySchema(driver: SqlDriver) {
    StorageDb.Schema.create(driver)
    ensureColumn(driver, "private_sends", "confirmed_in_block", "INTEGER NOT NULL DEFAULT 0")
    ensureColumn(driver, "private_sends", "created_at", "INTEGER NOT NULL DEFAULT 0")
    ensureColumn(driver, "sends", "confirmed_in_block", "INTEGER NOT NULL DEFAULT 0")
    ensureColumn(driver, "sends", "created_at", "INTEGER NOT NULL DEFAULT 0")
}

private fun ensureColumn(
    driver: SqlDriver,
    table: String,
    column: String,
    definition: String,
) {
    if (column in tableColumns(driver, table)) return
    driver.execute(null, "ALTER TABLE $table ADD COLUMN $column $definition", 0)
}

private fun tableColumns(
    driver: SqlDriver,
    table: String,
): Set<String> =
    driver
        .executeQuery(
            identifier = null,
            sql = "SELECT name FROM pragma_table_info('$table')",
            mapper = { cursor ->
                val out = mutableSetOf<String>()
                while (cursor.next().value) {
                    out.add(cursor.getString(0)!!)
                }
                QueryResult.Value(out)
            },
            parameters = 0,
        ).value

internal expect fun applyPragmas(driver: SqlDriver)

internal fun queryPragmaValue(
    driver: SqlDriver,
    pragma: String,
): String =
    driver
        .executeQuery(
            identifier = null,
            sql = "PRAGMA $pragma",
            mapper = { cursor ->
                QueryResult.Value(
                    if (cursor.next().value) cursor.getString(0).orEmpty() else "",
                )
            },
            parameters = 0,
        ).value
