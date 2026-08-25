package io.bluewallet.blueberry.boot

fun blueberrySqlitePath(directory: String): String {
    val trimmed = directory.trimEnd('/', '\\')
    return "$trimmed/blueberry.sqlite"
}

internal val SQLITE_DATABASE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")

/** Deletes the SQLite file and WAL/SHM/journal sidecars. Used by debug Clear storage. */
expect fun deleteSqliteDatabaseFiles(path: String)

internal expect fun existingFileBytes(path: String): Long

fun sqliteDatabaseBytes(path: String): Long {
    if (isInMemorySqlitePath(path)) return 0L
    return SQLITE_DATABASE_SUFFIXES.sumOf { existingFileBytes(path + it) }
}

fun formatDatabaseGigabytes(bytes: Long): String {
    if (bytes == 0L) return "0 GB"
    val thousandths = kotlin.math.round(bytes.toDouble() / 1_000_000.0).toLong()
    val whole = thousandths / 1000
    val frac = (thousandths % 1000).toString().padStart(3, '0').trimEnd('0')
    return if (frac.isEmpty()) "$whole GB" else "$whole.$frac GB"
}

internal fun isInMemorySqlitePath(path: String): Boolean =
    path.isEmpty() ||
        path == ":memory:" ||
        (path.startsWith("file:") && path.contains("mode=memory"))
