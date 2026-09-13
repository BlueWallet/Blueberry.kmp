package io.bluewallet.blueberry.peers.modules

import io.bluewallet.blueberry.bus.MessageBus
import io.bluewallet.blueberry.storage.Database

data class ModuleContext(
    val bus: MessageBus,
    val db: Database,
)

interface Module {
    val name: String

    suspend fun start()

    fun stop()
}
