package io.bluewallet.blueberry.storage

import app.cash.sqldelight.db.SqlDriver

internal actual fun applyPragmas(driver: SqlDriver) {
    driver.execute(null, "PRAGMA journal_mode = WAL", 0)
    driver.execute(null, "PRAGMA synchronous = NORMAL", 0)
    driver.execute(null, "PRAGMA wal_autocheckpoint = 10000", 0)
    driver.execute(null, "PRAGMA busy_timeout = 5000", 0)
}
