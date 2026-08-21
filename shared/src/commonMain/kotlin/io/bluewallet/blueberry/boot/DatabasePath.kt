package io.bluewallet.blueberry.boot

fun blueberrySqlitePath(directory: String): String {
    val trimmed = directory.trimEnd('/', '\\')
    return "$trimmed/blueberry.sqlite"
}

internal val SQLITE_DATABASE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")

/** Deletes the SQLite file and WAL/SHM/journal sidecars. Used by debug Clear storage. */
expect fun deleteSqliteDatabaseFiles(path: String)

internal fun isInMemorySqlitePath(path: String): Boolean =
    path.isEmpty() ||
        path == ":memory:" ||
        (path.startsWith("file:") && path.contains("mode=memory"))
