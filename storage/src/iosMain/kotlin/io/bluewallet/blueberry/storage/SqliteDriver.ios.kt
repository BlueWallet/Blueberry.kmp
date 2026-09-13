package io.bluewallet.blueberry.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration

private var memorySeq = 0

internal actual fun openSqliteDriver(path: String): SqlDriver {
    val memory = path == ":memory:"
    val (name, basePath) =
        if (memory) {
            "blueberry-memory-${memorySeq++}.sqlite" to null
        } else {
            val lastSlash = path.lastIndexOf('/')
            if (lastSlash < 0) {
                path to null
            } else {
                path.substring(lastSlash + 1) to path.substring(0, lastSlash)
            }
        }
    return NativeSqliteDriver(
        DatabaseConfiguration(
            name = name,
            version = 1,
            create = { },
            upgrade = { _, _, _ -> },
            inMemory = memory,
            extendedConfig = DatabaseConfiguration.Extended(basePath = basePath),
        ),
    )
}
