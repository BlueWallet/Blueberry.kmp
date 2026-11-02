package io.bluewallet.blueberry.boot

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSFileManager
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
internal actual fun readFileBytesOrNull(path: String): ByteArray? {
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
    val data = NSData.dataWithContentsOfFile(path) ?: return null
    val size = data.length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    return out
}

/** NSDataWritingAtomic writes to a temp file and renames it over [path]. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun writeFileBytesAtomically(
    directory: String,
    path: String,
    bytes: ByteArray,
) {
    val fm = NSFileManager.defaultManager
    if (!fm.fileExistsAtPath(directory)) {
        check(fm.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)) {
            "failed to create $directory"
        }
    }
    val data =
        bytes.usePinned { pinned ->
            NSData.create(bytes = if (bytes.isEmpty()) null else pinned.addressOf(0), length = bytes.size.toULong())
        }
    check(data.writeToFile(path, options = NSDataWritingAtomic, error = null)) { "failed to write $path" }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun deleteFileIfExists(path: String) {
    val fm = NSFileManager.defaultManager
    if (fm.fileExistsAtPath(path)) check(fm.removeItemAtPath(path, null)) { "failed to delete $path" }
}
