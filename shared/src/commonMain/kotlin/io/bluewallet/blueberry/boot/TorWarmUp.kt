package io.bluewallet.blueberry.boot

import io.bluewallet.blueberry.TOR_CHECK_URL
import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.echalote.Echalote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

const val TOR_WARM_UP_KEY = "tor_warm_up"

/** Off unless the user turned it on: warming up connects to the Tor bridge at every launch. */
fun loadTorWarmUp(db: Database): Boolean = db.keyValue.get(TOR_WARM_UP_KEY) == "1"

fun saveTorWarmUp(
    db: Database,
    enabled: Boolean,
) {
    db.keyValue.set(TOR_WARM_UP_KEY, if (enabled) "1" else "0")
}

const val TOR_DIRECT_RELAY_KEY = "tor_direct_relay"

/**
 * Off unless the user turned it on: a direct relay connection is faster than the meek bridge
 * but shows anyone watching the network that this device uses Tor, and is easy to block.
 */
fun loadTorDirectRelay(db: Database): Boolean = db.keyValue.get(TOR_DIRECT_RELAY_KEY) == "1"

fun saveTorDirectRelay(
    db: Database,
    enabled: Boolean,
) {
    db.keyValue.set(TOR_DIRECT_RELAY_KEY, if (enabled) "1" else "0")
}

/** Applies the saved choice to echalote; it takes effect on the next Tor bootstrap. */
fun applyTorDirectRelay(enabled: Boolean) {
    Echalote.directRelay = enabled
}

private val warmUpScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

@Volatile private var warmUpJob: Job? = null

/**
 * Opens the Tor bootstrap, a circuit and the HTTPS session to [url] in the background so the
 * first real request only pays for itself. A request made while this runs joins it. Calling it
 * again while one is in flight does nothing.
 */
fun startTorWarmUp(
    url: String = TOR_CHECK_URL,
    warmUp: suspend (String) -> Unit = { Echalote.warmUp(it) },
): Job {
    warmUpJob?.takeIf { it.isActive }?.let { return it }
    val job =
        warmUpScope.launch {
            val started = nowMillis()
            println("TORWARMUP start at=$started url=$url")
            try {
                warmUp(url)
                println("TORWARMUP done ms=${nowMillis() - started}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                println("TORWARMUP fail ms=${nowMillis() - started} ${e.message}")
            }
        }
    warmUpJob = job
    return job
}
