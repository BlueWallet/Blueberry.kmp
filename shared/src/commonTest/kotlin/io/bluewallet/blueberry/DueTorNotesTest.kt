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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    /** A drop whose delete fails is no Tor record, though its row is still there: no node holds the payment. */
    @Test
    fun a_pending_drop_reads_as_no_tor_record() =
        withOpenSend { db, flaky ->
            val notes = DueTorNotes(flaky.store)
            notes.add(txid)
            assertTrue(notes.recorded(txid))
            flaky.deleteBroken = true
            notes.drop(txid)
            assertEquals(BROADCAST_VIA_TOR, db.broadcastVia.get(txid))
            assertFalse(notes.recorded(txid))
        }

    /** A restart can't bring the dropped note back: the pending drop is stored, and the next refresh deletes it. */
    @Test
    fun a_pending_drop_survives_a_restart_until_its_delete_lands() =
        withOpenSend { db, flaky ->
            val before = DueTorNotes(flaky.store)
            before.add(txid)
            flaky.deleteBroken = true
            before.drop(txid)
            val restarted = DueTorNotes(flaky.store)
            assertFalse(restarted.recorded(txid), "the row still stands, but reads as no record")
            flaky.deleteBroken = false
            restarted.write()
            assertNull(db.broadcastVia.get(txid))
            db.broadcastVia.upsert(txid, BROADCAST_VIA_TOR)
            assertTrue(DueTorNotes(flaky.store).recorded(txid), "a landed delete leaves no stored drop behind")
        }

    /** A later run a node holds cancels the pending drop for good, so a restart doesn't hide its note. */
    @Test
    fun a_held_run_cancels_a_pending_drop_across_a_restart() =
        withOpenSend { db, flaky ->
            val before = DueTorNotes(flaky.store)
            before.add(txid)
            flaky.deleteBroken = true
            before.drop(txid)
            before.add(txid)
            assertEquals(BROADCAST_VIA_TOR, db.broadcastVia.get(txid))
            assertTrue(DueTorNotes(flaky.store).recorded(txid))
        }

    /** A due note is proof a node holds the payment: a restart keeps it due and recorded until its write lands. */
    @Test
    fun a_due_note_whose_write_fails_survives_a_restart_as_recorded() =
        withOpenSend { db, flaky ->
            flaky.upsertBroken = true
            val before = DueTorNotes(flaky.store)
            before.add(txid)
            assertNull(db.broadcastVia.get(txid))
            assertTrue(before.recorded(txid), "a due note is a record before its row lands")
            val restarted = DueTorNotes(flaky.store)
            assertTrue(restarted.recorded(txid), "and after a restart")
            flaky.upsertBroken = false
            restarted.write()
            assertEquals(BROADCAST_VIA_TOR, db.broadcastVia.get(txid))
            assertNull(db.keyValue.get(TOR_NOTE_DUE_KEY), "a landed write leaves no stored due note behind")
        }

    private companion object {
        const val ADDRESS = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
        const val RACE_WAIT_MS = 300L
    }
}
