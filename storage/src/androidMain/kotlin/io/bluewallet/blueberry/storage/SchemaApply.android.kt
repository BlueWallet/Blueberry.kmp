package io.bluewallet.blueberry.storage

import app.cash.sqldelight.db.SqlDriver

internal actual fun applyPragmas(driver: SqlDriver) {
    queryPragmaValue(driver, "journal_mode = WAL")
    queryPragmaValue(driver, "synchronous = NORMAL")
    queryPragmaValue(driver, "wal_autocheckpoint = 10000")
    queryPragmaValue(driver, "busy_timeout = 5000")
}
