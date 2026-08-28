package io.bluewallet.blueberry.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

internal fun applySchema(driver: SqlDriver) {
    StorageDb.Schema.create(driver)
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
