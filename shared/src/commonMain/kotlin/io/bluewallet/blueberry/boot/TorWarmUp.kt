package io.bluewallet.blueberry.boot

import io.bluewallet.blueberry.TOR_CHECK_URL
import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.echalote.Echalote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/**
 * Applies the saved choice to echalote right away: if it changes the route in use, echalote
 * drops the current Tor connection and HTTPS sessions so the next request reconnects the new
 * way (requests in flight are retried on the new connection).
 *
 * Runs to completion even if the caller is cancelled (e.g. the startup `LaunchedEffect` leaving
 * composition): echalote records the new mode before it closes the old connection and HTTPS
 * sessions, so stopping halfway could leave kept-alive sessions on the old route.
 */
suspend fun applyTorDirectRelay(
    enabled: Boolean,
    apply: suspend (Boolean) -> Unit = { Echalote.setDirectRelay(it) },
) {
    withContext(NonCancellable) { apply(enabled) }
    // NonCancellable also hides a cancellation from the caller: rethrow it here so the caller
    // stops instead of carrying on (e.g. reading a database that was closed meanwhile).
    currentCoroutineContext().ensureActive()
}

private val directRelayScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * [applyTorDirectRelay] from a non-suspending caller such as a switch. The call starts in the
 * caller's thread, so quick on/off/on taps reach echalote's (fair) lock in tap order.
 *
 * With [warmUpAfter] (the user has "warm up Tor" on) the new route is warmed up once applied:
 * changing the route drops the warmed connection, and fails a warm-up still running on the old
 * route, so without this the first request after the switch pays for the whole bootstrap.
 */
fun launchTorDirectRelay(
    enabled: Boolean,
    warmUpAfter: Boolean = false,
    warmUp: suspend (String) -> Unit = { Echalote.warmUp(it) },
    apply: suspend (Boolean) -> Unit = { Echalote.setDirectRelay(it) },
): Job =
    directRelayScope.launch(start = CoroutineStart.UNDISPATCHED) {
        applyTorDirectRelay(enabled, apply)
        if (warmUpAfter) {
            // Let a warm-up on the old route finish failing first: while it is active,
            // startTorWarmUp would hand back that dying job instead of starting a new one.
            warmUpJob?.join()
            startTorWarmUp(warmUp = warmUp).join()
        }
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
            } catch (e: CancellationException) {
                throw e
            } catch (expected: Exception) {
                // Best effort: any network or Tor failure here just means the first real request
                // bootstraps instead. Errors (OOM etc.) are not caught.
                println("TORWARMUP fail ms=${nowMillis() - started} ${expected.message}")
            }
        }
    warmUpJob = job
    return job
}
