package io.bluewallet.blueberry.peers.modules

import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.ModuleStatus
import io.bluewallet.blueberry.bus.ModuleStatusPayload
import io.bluewallet.blueberry.peers.logError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

fun detachLoop(
    ctx: ModuleContext,
    module: String,
    task: Job,
) {
    task.invokeOnCompletion { err ->
        if (err == null || err is CancellationException) return@invokeOnCompletion
        val detail = err.message ?: err.toString()
        ctx.bus.emit(
            Event.ModuleStatus,
            ModuleStatusPayload(module = module, status = ModuleStatus.ERROR, detail = detail),
        )
        logError(module, "background loop failed", err)
    }
}
