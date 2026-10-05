package io.bluewallet.blueberry

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Starts peers immediately. Label restore runs alongside that and must not delay it.
 * When restore replaces local rows, [onRestored] runs after peers have started.
 */
internal suspend fun startPeersThenRestoreLabels(
    startPeers: suspend () -> Unit,
    restoreLabels: suspend () -> Boolean,
    onRestored: () -> Unit,
) {
    coroutineScope {
        val restore =
            async {
                try {
                    restoreLabels()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    false
                }
            }
        startPeers()
        if (restore.await()) onRestored()
    }
}
