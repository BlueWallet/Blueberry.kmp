package io.bluewallet.blueberry.boot

import io.bluewallet.blueberry.storage.DownloadedBlock
import io.bluewallet.blueberry.storage.MatchedBlock
import io.bluewallet.blueberry.storage.StoredTx
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.WALLET_SECRET_KEY
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClearStorageTest {
    @Test
    fun sqliteDatabaseBytes_sums_db_and_sidecars() {
        val path = tempSqlitePath()
        File(path).writeBytes(ByteArray(1000))
        File("$path-wal").writeBytes(ByteArray(200))
        File("$path-shm").writeBytes(ByteArray(30))
        File("$path-journal").writeBytes(ByteArray(4))

        assertEquals(1234L, sqliteDatabaseBytes(path))

        deleteSqliteDatabaseFiles(path)
    }

    @Test
    fun deleteSqliteDatabaseFiles_removes_db_and_sidecars() {
        val path = tempSqlitePath()
        File(path).writeText("db")
        File("$path-wal").writeText("wal")
        File("$path-shm").writeText("shm")
        File("$path-journal").writeText("journal")

        deleteSqliteDatabaseFiles(path)

        assertFalse(File(path).exists())
        assertFalse(File("$path-wal").exists())
        assertFalse(File("$path-shm").exists())
        assertFalse(File("$path-journal").exists())
    }

    @Test
    fun close_and_delete_leaves_a_fresh_database_on_reopen() {
        val path = tempSqlitePath()
        val first = createSqliteDatabase(path)
        first.keyValue.set(WALLET_SECRET_KEY, "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
        first.keyValue.set("sync_from_year", "2024")
        first.matchedBlocks.insert(MatchedBlock(10, "aa".repeat(32)))
        first.blocks.insert(DownloadedBlock(10, "aa".repeat(32), byteArrayOf(0x11)))
        first.parsedBlocks.mark(10)
        first.transactions.upsert(
            StoredTx("ab".repeat(32), 10, 0, "aa".repeat(32), byteArrayOf(0x22), 50),
        )
        first.utxoNames.upsert("ab".repeat(32) + ":0", "coffee")
        first.close()
        assertTrue(File(path).exists())

        deleteSqliteDatabaseFiles(path)
        assertFalse(File(path).exists())

        val second = createSqliteDatabase(path)
        assertNull(second.keyValue.get(WALLET_SECRET_KEY))
        assertNull(second.keyValue.get("sync_from_year"))
        assertEquals(0, second.matchedBlocks.count())
        assertEquals(0, second.blocks.count())
        assertEquals(0, second.parsedBlocks.count())
        assertEquals(0, second.transactions.count())
        assertEquals(emptyList(), second.utxoNames.list())
        second.close()
        deleteSqliteDatabaseFiles(path)
    }

    private fun tempSqlitePath(): String {
        val file = File.createTempFile("blueberry-clear", ".sqlite")
        file.delete()
        file.deleteOnExit()
        return file.absolutePath
    }
}
