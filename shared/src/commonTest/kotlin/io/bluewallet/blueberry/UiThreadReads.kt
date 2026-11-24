package io.bluewallet.blueberry

import io.bluewallet.blueberry.storage.BroadcastViaRepository
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.PrivateSendRow
import io.bluewallet.blueberry.storage.PrivateSendsRepository
import io.bluewallet.blueberry.storage.SendRow
import io.bluewallet.blueberry.storage.SendsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/**
 * Stands in for the UI dispatcher: it runs on the test's event loop and knows while one of its tasks runs, so a
 * database read made then is a read on the UI thread.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class UiDispatcher(
    private val loop: CoroutineDispatcher,
) : CoroutineDispatcher() {
    private val running = AtomicInt(0)

    val onUi: Boolean get() = running.load() > 0

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) = loop.dispatch(
        context,
        Runnable {
            running.incrementAndFetch()
            try {
                block.run()
            } finally {
                running.decrementAndFetch()
            }
        },
    )
}

/** Runs [block] on a [UiDispatcher] over the caller's event loop. */
internal suspend fun <T> onUiDispatcher(block: suspend (UiDispatcher) -> T): T {
    val ui = UiDispatcher(currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher)
    return withContext(ui) { block(ui) }
}

/** [db] whose payment and Tor-note reads are counted in [uiReads] when they run while [ui] says so. */
internal class UiReadSpy(
    db: Database,
    private val ui: () -> UiDispatcher?,
) {
    val uiReads = mutableListOf<String>()

    private fun read(what: String) {
        if (ui()?.onUi == true) uiReads += what
    }

    val store: Database =
        object : Database by db {
            override val sends =
                object : SendsRepository by db.sends {
                    override fun get(txid: String): SendRow? = db.sends.get(txid).also { read("sends.get") }

                    override fun list(): List<SendRow> = db.sends.list().also { read("sends.list") }
                }
            override val privateSends =
                object : PrivateSendsRepository by db.privateSends {
                    override fun get(txid: String): PrivateSendRow? = db.privateSends.get(txid).also { read("privateSends.get") }

                    override fun list(): List<PrivateSendRow> = db.privateSends.list().also { read("privateSends.list") }
                }
            override val broadcastVia =
                object : BroadcastViaRepository by db.broadcastVia {
                    override fun get(txid: String): String? = db.broadcastVia.get(txid).also { read("broadcastVia.get") }
                }
        }
}
