package io.bluewallet.blueberry.boot

import java.io.File

actual fun deleteSqliteDatabaseFiles(path: String) {
    if (isInMemorySqlitePath(path)) return
    val main = File(path)
    repeat(5) { attempt ->
        SQLITE_DATABASE_SUFFIXES.forEach { suffix ->
            File(path + suffix).delete()
        }
        if (!main.exists()) return
        if (attempt < 4) Thread.sleep(20L shl attempt)
    }
    if (main.exists()) {
        error("failed to delete SQLite database: $path")
    }
}
