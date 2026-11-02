package io.bluewallet.blueberry.boot

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        temp.delete()
    }
}

internal actual fun deleteFileIfExists(path: String) {
    Files.deleteIfExists(File(path).toPath())
}
