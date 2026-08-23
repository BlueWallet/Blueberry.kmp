package io.bluewallet.blueberry.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties

internal actual fun openSqliteDriver(path: String): SqlDriver {
    if (path == ":memory:") {
        val properties = Properties().apply {
            setProperty("journal_mode", "WAL")
            setProperty("synchronous", "NORMAL")
            setProperty("wal_autocheckpoint", "10000")
            setProperty("busy_timeout", "5000")
        }
        return JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, properties)
    }
    return JdbcSqliteDriver(
        "jdbc:sqlite:$path?journal_mode=WAL&synchronous=NORMAL&wal_autocheckpoint=10000&busy_timeout=5000",
    )
}
