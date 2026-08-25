package io.bluewallet.blueberry.boot

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber

@OptIn(ExperimentalForeignApi::class)
internal actual fun existingFileBytes(path: String): Long {
    val fm = NSFileManager.defaultManager
    if (!fm.fileExistsAtPath(path)) return 0L
    val attrs = fm.attributesOfItemAtPath(path, null) ?: return 0L
    return (attrs[NSFileSize] as? NSNumber)?.longLongValue ?: 0L
}

@OptIn(ExperimentalForeignApi::class)
actual fun deleteSqliteDatabaseFiles(path: String) {
    if (isInMemorySqlitePath(path)) return
    val fm = NSFileManager.defaultManager
    SQLITE_DATABASE_SUFFIXES.forEach { suffix ->
        val file = path + suffix
        if (fm.fileExistsAtPath(file)) {
            fm.removeItemAtPath(file, null)
        }
    }
}
