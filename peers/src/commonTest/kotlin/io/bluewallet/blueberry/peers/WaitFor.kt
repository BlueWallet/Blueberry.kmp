package io.bluewallet.blueberry.peers

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

suspend fun waitFor(
    timeoutMs: Long = 2000,
    predicate: () -> Boolean,
) {
    try {
        withTimeout(timeoutMs) {
            while (!predicate()) delay(10)
        }
    } catch (_: TimeoutCancellationException) {
        error("timeout waiting for condition")
    }
}
