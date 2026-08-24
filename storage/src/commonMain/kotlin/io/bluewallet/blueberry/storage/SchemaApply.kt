package io.bluewallet.blueberry.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

internal fun applySchema(driver: SqlDriver) {
    val exists = driver.executeQuery(
        identifier = null,
        sql = "SELECT 1 FROM sqlite_master WHERE type='table' AND name='peers' LIMIT 1",
        mapper = { cursor -> QueryResult.Value(cursor.next().value) },
        parameters = 0,
    ).value
    if (!exists) {
        StorageDb.Schema.create(driver)
    }
    driver.execute(
        identifier = null,
        sql = """
            CREATE TABLE IF NOT EXISTS tx_payment_labels (
              txid TEXT PRIMARY KEY,
              label TEXT NOT NULL
            )
        """.trimIndent(),
        parameters = 0,
    )
}

internal expect fun applyPragmas(driver: SqlDriver)

internal fun queryPragmaValue(driver: SqlDriver, pragma: String): String {
    return driver.executeQuery(
        identifier = null,
        sql = "PRAGMA $pragma",
        mapper = { cursor ->
            QueryResult.Value(
                if (cursor.next().value) cursor.getString(0).orEmpty() else "",
            )
        },
        parameters = 0,
    ).value
}
