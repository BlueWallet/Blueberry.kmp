package io.bluewallet.blueberry

import io.bluewallet.blueberry.storage.BroadcastViaRepository
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.SendRow
import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The due set and the `broadcast_via` row move together, so a dropped note can't come back or be left behind. */
class DueTorNotesTest {
    private val txid = "ef".repeat(32)

    /** A store whose note writes and deletes fail while [upsertBroken] / [deleteBroken] are set. */
    private class Flaky(
        val db: Database,
    ) {
        var upsertBroken = false
        var deleteBroken = false
        var beforeUpsert: () -> Unit = {}

        val store =
            object : Database by db {
                override val broadcastVia =
                    object : BroadcastViaRepository by db.broadcastVia {
                        override fun upsert(
                            txid: String,
                            via: String,
                        ) {
                            beforeUpsert()
                            if (upsertBroken) error("database is locked")
                            db.broadcastVia.upsert(txid, via)
                        }

                        override fun delete(txid: String) {
                            if (deleteBroken) error("database is locked")
                            db.broadcastVia.delete(txid)
                        }
                    }
            }
    }

    private fun withOpenSend(body: (Database, Flaky) -> Unit) {
        val db = createSqliteDatabase(":memory:")
        try {
            db.sends.upsert(SendRow(txid, "00", ADDRESS, emptyList()))
            body(db, Flaky(db))
        } finally {
            db.close()
        }
    }

    /** A delete that throws keeps the note pending, and the next wallet refresh deletes it. */
    @Test
    fun a_dropped_note_whose_delete_fails_is_deleted_on_the_next_refresh() =
        withOpenSend { db, flaky ->
            val notes = DueTorNotes(flaky.store)
            notes.add(txid)
            assertEquals(BROADCAST_VIA_TOR, db.broadcastVia.get(txid))
            flaky.deleteBroken = true
            runCatching { notes.drop(txid) }
            assertEquals(BROADCAST_VIA_TOR, db.broadcastVia.get(txid))
            flaky.deleteBroken = false
            notes.write()
            assertNull(db.broadcastVia.get(txid), "the refresh retries the delete")
        }

    /** A refresh already writing a due note when the run is refused can't leave the note behind. */
    @Test
    fun a_refresh_racing_a_drop_does_not_write_the_note_back() =
        withOpenSend { db, flaky ->
            val notes = DueTorNotes(flaky.store)
            flaky.upsertBroken = true
            runCatching { notes.add(txid) }
            assertNull(db.broadcastVia.get(txid))
            flaky.upsertBroken = false
            var dropper: Job? = null
            flaky.beforeUpsert = {
                flaky.beforeUpsert = {}
                val job = CoroutineScope(Dispatchers.Default).launch { notes.drop(txid) }
                dropper = job
                runBlocking { withTimeoutOrNull(RACE_WAIT_MS) { job.join() } }
            }
            notes.write()
            runBlocking { dropper!!.join() }
            assertNull(db.broadcastVia.get(txid), "the refused run's note stays deleted")
            notes.write()
            assertNull(db.broadcastVia.get(txid), "and is no longer due")
        }

    /** A later run a node holds makes the note due again, so a pending delete from a refused run is cancelled. */
    @Test
    fun a_note_due_again_after_a_failed_delete_stays_written() =
        withOpenSend { db, flaky ->
            val notes = DueTorNotes(flaky.store)
            notes.add(txid)
            flaky.deleteBroken = true
            runCatching { notes.drop(txid) }
            notes.add(txid)
            flaky.deleteBroken = false
            notes.write()
            assertEquals(BROADCAST_VIA_TOR, db.broadcastVia.get(txid))
        }

    private companion object {
        const val ADDRESS = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
        const val RACE_WAIT_MS = 300L
    }
}
