package io.bluewallet.blueberry.broadcast

import io.bluewallet.bip324.ByteDuplex
import io.bluewallet.blueberry.peers.log
import io.bluewallet.blueberry.peers.logError
import io.bluewallet.echalote.Abort
import io.bluewallet.echalote.Echalote
import io.bluewallet.echalote.ExitDialerOptions
import io.bluewallet.echalote.FetchProgressListener
import kotlinx.coroutines.Job

interface TorByteDuplexDialer {
    suspend fun dial(
        host: String,
        port: Int,
        job: Job,
        onProgress: FetchProgressListener? = null,
    ): ByteDuplex

    suspend fun dispose()
}

fun echaloteStreamToByteDuplex(
    outer: io.bluewallet.echalote.ByteDuplex,
    onClose: () -> Unit = {},
): ByteDuplex =
    object : ByteDuplex {
        override suspend fun read(n: Int): ByteArray = outer.read(n)

        override suspend fun write(bytes: ByteArray) = outer.write(bytes)

        override suspend fun close() {
            try {
                outer.close()
            } catch (_: Throwable) {
            }
            try {
                onClose()
            } catch (_: Throwable) {
            }
        }
    }

fun createTorByteDuplexDialer(options: ExitDialerOptions = ExitDialerOptions()): TorByteDuplexDialer {
    val isolated = options != ExitDialerOptions()
    val owned = if (isolated) Echalote.createExitDialer(options) else null
    return object : TorByteDuplexDialer {
        override suspend fun dial(
            host: String,
            port: Int,
            job: Job,
            onProgress: FetchProgressListener?,
        ): ByteDuplex {
            val startedAt = nowMs()
            log("tor", "dial start $host:$port")
            val abort = Abort()
            val cancel =
                job.invokeOnCompletion {
                    if (job.isCancelled) abort.abort(Exception("cancelled"))
                }
            try {
                val stream =
                    if (owned != null) {
                        owned.dial(host, port, abort, onProgress)
                    } else {
                        Echalote.dial(host, port, abort, onProgress)
                    }
                log("tor", "dial ok $host:$port elapsedMs=${nowMs() - startedAt}")
                return echaloteStreamToByteDuplex(stream.outer) { stream.close() }
            } catch (err: Throwable) {
                logError("tor", "dial fail $host:$port elapsedMs=${nowMs() - startedAt}", err)
                throw err
            } finally {
                cancel.dispose()
            }
        }

        override suspend fun dispose() {
            log("tor", "dialer dispose")
            owned?.dispose()
        }
    }
}

internal fun nowMs(): Long =
    io.bluewallet.blueberry.headers
        .nowMillis()
