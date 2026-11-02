package io.bluewallet.blueberry.boot

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FileTorDirCacheTest {
    @Test
    fun writesReadsReplacesAndDeletesWithoutLeavingTempFiles(): Unit =
        runBlocking {
            val root = Files.createTempDirectory("tor-dir-test").toFile()
            try {
                val dir = torDirCachePath(root.absolutePath + "/")
                assertEquals(root.absolutePath + "/tor-dir", dir)
                val cache = FileTorDirCache(dir)
                assertNull(cache.read("consensus-microdesc"))
                cache.delete("consensus-microdesc")
                cache.write("consensus-microdesc", "one".encodeToByteArray())
                cache.write("consensus-microdesc", "two!".encodeToByteArray())
                cache.write("authority-certs", ByteArray(0))
                assertContentEquals("two!".encodeToByteArray(), cache.read("consensus-microdesc"))
                assertContentEquals(ByteArray(0), cache.read("authority-certs"))
                assertEquals(setOf("consensus-microdesc", "authority-certs"), File(dir).list()!!.toSet())
                cache.delete("consensus-microdesc")
                assertNull(cache.read("consensus-microdesc"))
                assertFailsWith<IllegalArgumentException> { cache.read("../escape") }
            } finally {
                root.deleteRecursively()
            }
        }
}
