package io.bluewallet.blueberry.peers.modules

import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.ModuleStatus
import io.bluewallet.blueberry.bus.ModuleStatusPayload
import io.bluewallet.blueberry.bus.createMessageBus
import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlin.test.Test
import kotlin.test.assertTrue

class DetachLoopTest {
    @Test
    fun emits_module_status_error_when_job_fails() =
        runBlocking {
            supervisorScope {
                val bus = createMessageBus()
                val db = createSqliteDatabase(":memory:")
                val seen = mutableListOf<ModuleStatusPayload>()
                bus.on(Event.ModuleStatus) { seen.add(it) }
                val job = launch { error("boom") }
                detachLoop(ModuleContext(bus, db), "peers-discovery", job)
                job.join()
                assertTrue(
                    seen.any {
                        it.module == "peers-discovery" &&
                            it.status == ModuleStatus.ERROR &&
                            (it.detail ?: "").contains("boom")
                    },
                )
                db.close()
            }
        }

    @Test
    fun cancellation_does_not_emit_error() =
        runBlocking {
            val bus = createMessageBus()
            val db = createSqliteDatabase(":memory:")
            val seen = mutableListOf<ModuleStatusPayload>()
            bus.on(Event.ModuleStatus) { seen.add(it) }
            val job = launch { kotlinx.coroutines.delay(60_000) }
            detachLoop(ModuleContext(bus, db), "peers-discovery", job)
            job.cancel()
            job.join()
            assertTrue(seen.none { it.status == ModuleStatus.ERROR })
            db.close()
        }
}
