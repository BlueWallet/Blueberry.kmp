package io.bluewallet.blueberry.boot

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager

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
