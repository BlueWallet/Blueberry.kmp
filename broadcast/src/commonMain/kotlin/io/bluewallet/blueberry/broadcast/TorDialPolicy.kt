package io.bluewallet.blueberry.broadcast

import io.bluewallet.blueberry.peers.log
import io.bluewallet.blueberry.peers.logError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class TorDialRetryOptions(
    val attempts: Int = 3,
    val backoffMs: Long = 1_500,
)

suspend fun <T> withTorDialRetries(
    createDialer: () -> TorByteDuplexDialer,
    run: suspend (dial: TorByteDuplexDialer) -> T,
    options: TorDialRetryOptions = TorDialRetryOptions(),
): T {
    var lastError: Throwable? = null
    for (attempt in 1..options.attempts) {
        coroutineContext.ensureActive()
        log("tor", "dialer-cycle start $attempt/${options.attempts}")
        val dialer = createDialer()
        try {
            val result = run(dialer)
            log("tor", "dialer-cycle ok $attempt/${options.attempts}")
            return result
        } catch (err: CancellationException) {
            logError("tor", "dialer-cycle abort $attempt/${options.attempts}", err)
            throw err
        } catch (err: Throwable) {
            lastError = err
            logError("tor", "dialer-cycle fail $attempt/${options.attempts}", err)
        } finally {
            try {
                dialer.dispose()
            } catch (err: Throwable) {
                logError("tor", "dialer-cycle dispose-fail $attempt/${options.attempts}", err)
            }
            log("tor", "dialer-cycle dispose $attempt/${options.attempts}")
        }
        if (attempt < options.attempts) {
            log("tor", "dialer-cycle backoff ms=${options.backoffMs} next=${attempt + 1}/${options.attempts}")
            delay(options.backoffMs)
        }
    }
    throw lastError ?: IllegalStateException("tor dial retries exhausted")
}
