package io.bluewallet.blueberry.boot

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

// java.nio.file needs API 26 (minSdk is 24); File.renameTo is rename(2), which atomically
// replaces the target on Android's filesystems.

internal actual fun readFileBytesOrNull(path: String): ByteArray? {
    val file = File(path)
    return if (file.isFile) file.readBytes() else null
}

internal actual fun writeFileBytesAtomically(
    directory: String,
    path: String,
    bytes: ByteArray,
) {
    val dir = File(directory)
    dir.mkdirs()
    val target = File(path)
    val temp = File.createTempFile(target.name + ".", ".tmp", dir)
    try {
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!temp.renameTo(target)) throw IOException("rename failed: $temp -> $target")
    } finally {
        temp.delete()
    }
}

internal actual fun deleteFileIfExists(path: String) {
    val file = File(path)
    if (file.exists() && !file.delete()) throw IOException("delete failed: $path")
}
