package io.bluewallet.blueberry.storage

import app.cash.sqldelight.db.SqlDriver

/**
 * The real Android framework driver (AndroidSqliteDriver) must keep using [queryPragmaValue]
 * so on-device WAL/pragma behavior matches pre-change Android exactly. Only the JDBC fallback
 * used by host tests (no Android framework SQLite available) switches to `driver.execute`.
 */
internal actual fun applyPragmas(driver: SqlDriver) {
    if (driver.isJdbcFallback()) {
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
