package io.bluewallet.blueberry.boot

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

/*
 * Errors are thrown with the NSError text rather than swallowed: echalote's cache wrapper
 * catches them, logs `echalote.dircache read/write/delete failed key=... <message>` and carries
 * on without the cache.
 *
 * The cache lives in Application Support/tor-dir (not Caches): iOS may purge Caches under storage
 * pressure, and a purge right before a cold start is exactly when the cached consensus saves the
 * most time. The folder is excluded from iCloud/iTunes backup instead, since it is ~3.5 MB of
 * public, re-downloadable, re-verified data.
 */

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun readFileBytesOrNull(path: String): ByteArray? {
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
    val data =
        withNSError("read $path") { err ->
            NSData.dataWithContentsOfFile(path, options = 0u, error = err)
        }
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
    ensureCacheDirectory(directory)
    val data =
        bytes.usePinned { pinned ->
            NSData.create(bytes = if (bytes.isEmpty()) null else pinned.addressOf(0), length = bytes.size.toULong())
        }
    withNSError("write $path") { err ->
        data.writeToFile(path, options = NSDataWritingAtomic, error = err).takeIf { it }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun deleteFileIfExists(path: String) {
    val fm = NSFileManager.defaultManager
    if (fm.fileExistsAtPath(path)) {
        withNSError("delete $path") { err -> fm.removeItemAtPath(path, err).takeIf { it } }
    }
}

/** The directory already marked excluded from backup in this process (a racing double mark is harmless). */
@kotlin.concurrent.Volatile
private var markedDirectory: String? = null

/**
 * Creates [directory] if needed and marks it excluded from backup (once per process; the
 * flag sticks to the folder, and this also covers folders created by older builds).
 *
 * Two writers can race to create the folder (consensus and authority certificates are written
 * from different coroutines): createDirectory with intermediate directories succeeds when the
 * folder already exists, and if it still fails we re-check before reporting the error.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ensureCacheDirectory(directory: String) {
    val fm = NSFileManager.defaultManager
    if (!fm.fileExistsAtPath(directory)) {
        memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            val created = fm.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = err.ptr)
            check(created || fm.fileExistsAtPath(directory)) {
                "failed to create $directory: ${err.value?.localizedDescription ?: "unknown error"}"
            }
        }
    }
    if (markedDirectory != directory) {
        excludeFromBackup(directory)
        markedDirectory = directory
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun excludeFromBackup(directory: String) {
    memScoped {
        val err = alloc<ObjCObjectVar<NSError?>>()
        val ok =
            NSURL
                .fileURLWithPath(directory, isDirectory = true)
                .setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = err.ptr)
        // Not fatal: the cache still works, it is just included in backups.
        if (!ok) println("TORDIR exclude-from-backup failed dir=$directory ${err.value?.localizedDescription}")
    }
}

/** Runs [block] with an NSError out-pointer; a null result throws with the error's description. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private inline fun <T : Any> withNSError(
    what: String,
    block: (kotlinx.cinterop.CPointer<ObjCObjectVar<NSError?>>) -> T?,
): T =
    memScoped {
        val err = alloc<ObjCObjectVar<NSError?>>()
        block(err.ptr) ?: error("failed to $what: ${err.value?.localizedDescription ?: "unknown error"}")
    }
