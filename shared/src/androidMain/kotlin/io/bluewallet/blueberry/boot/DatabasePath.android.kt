package io.bluewallet.blueberry.boot

import android.database.sqlite.SQLiteDatabase
import java.io.File

internal actual fun existingFileBytes(path: String): Long {
    val file = File(path)
    return if (file.isFile) file.length() else 0L
}

actual fun deleteSqliteDatabaseFiles(path: String) {
    if (isInMemorySqlitePath(path)) return
    try {
        SQLiteDatabase.deleteDatabase(File(path))
    } catch (_: Throwable) {
        // Host tests ship a mocked android.database; fall through to File.delete.
    }
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
