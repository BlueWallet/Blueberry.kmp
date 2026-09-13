package io.bluewallet.blueberry.storage

import app.cash.sqldelight.db.SqlDriver

internal actual fun applyPragmas(driver: SqlDriver) {
    if (driver.isJdbcFallback()) {
        // sqlite-jdbc executeQuery rejects PRAGMA assignments.
        driver.execute(null, "PRAGMA journal_mode = WAL", 0)
        driver.execute(null, "PRAGMA synchronous = NORMAL", 0)
        driver.execute(null, "PRAGMA wal_autocheckpoint = 10000", 0)
        driver.execute(null, "PRAGMA busy_timeout = 5000", 0)
    } else {
        queryPragmaValue(driver, "journal_mode = WAL")
        queryPragmaValue(driver, "synchronous = NORMAL")
        queryPragmaValue(driver, "wal_autocheckpoint = 10000")
        queryPragmaValue(driver, "busy_timeout = 5000")
    }
}

private fun SqlDriver.isJdbcFallback(): Boolean = this::class.qualifiedName?.contains("Jdbc") == true
