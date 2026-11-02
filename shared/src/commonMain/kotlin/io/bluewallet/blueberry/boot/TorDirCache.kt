package io.bluewallet.blueberry.boot

import io.bluewallet.echalote.Echalote
import io.bluewallet.echalote.TorDirCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** Directory for the Tor directory cache, under the app's data [directory]. */
fun torDirCachePath(directory: String): String = directory.trimEnd('/', '\\') + "/tor-dir"

/**
 * Stores echalote's Tor directory documents (consensus, authority certificates) as one file per
 * key in [directory], so cold starts skip re-downloading them. echalote re-verifies everything
 * it reads back, so these files are untrusted public data. Writes go to a temp file that is then
 * renamed over the old one, so a crash never leaves a half-written document.
 */
class FileTorDirCache(
    val directory: String,
) : TorDirCache {
    private fun pathOf(key: String): String {
        require(key.isNotEmpty() && key.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "bad cache key: $key" }
        return "$directory/$key"
    }

    override suspend fun read(key: String): ByteArray? = withContext(Dispatchers.IO) { readFileBytesOrNull(pathOf(key)) }

    override suspend fun write(
        key: String,
        bytes: ByteArray,
    ) = withContext(Dispatchers.IO) { writeFileBytesAtomically(directory, pathOf(key), bytes) }

    override suspend fun delete(key: String) = withContext(Dispatchers.IO) { deleteFileIfExists(pathOf(key)) }

    override fun toString(): String = "FileTorDirCache($directory)"
}

/** Points echalote's process-wide Tor directory cache at `<dataDirectory>/tor-dir`. */
fun installTorDirCache(dataDirectory: String) {
    val directory = torDirCachePath(dataDirectory)
    val current = Echalote.dirCache
    // onCreate runs again on every Activity recreation; keep the installed instance.
    if (current is FileTorDirCache && current.directory == directory) return
    Echalote.dirCache = FileTorDirCache(directory)
}

/** The file's bytes, or null if it does not exist. */
internal expect fun readFileBytesOrNull(path: String): ByteArray?

/** Creates [directory] if needed and replaces [path] with [bytes] via a temp file and a rename. */
internal expect fun writeFileBytesAtomically(
    directory: String,
    path: String,
    bytes: ByteArray,
)

internal expect fun deleteFileIfExists(path: String)
