package io.bluewallet.blueberry.broadcast

import io.bluewallet.bip324.ByteDuplex
import io.bluewallet.echalote.Abort
import io.bluewallet.echalote.Echalote
import io.bluewallet.echalote.ExitDialerOptions
import io.bluewallet.blueberry.peers.log
import io.bluewallet.blueberry.peers.logError
import kotlinx.coroutines.Job

interface TorByteDuplexDialer {
    suspend fun dial(host: String, port: Int, job: Job): ByteDuplex
    suspend fun dispose()
}

fun echaloteStreamToByteDuplex(
    outer: io.bluewallet.echalote.ByteDuplex,
    onClose: () -> Unit = {},
): ByteDuplex = object : ByteDuplex {
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

fun createTorByteDuplexDialer(
    options: ExitDialerOptions = ExitDialerOptions(),
): TorByteDuplexDialer {
    val dialer = Echalote.createExitDialer(options)
    return object : TorByteDuplexDialer {
        override suspend fun dial(host: String, port: Int, job: Job): ByteDuplex {
            val startedAt = nowMs()
            log("tor", "dial start $host:$port")
            val abort = Abort()
            val cancel = job.invokeOnCompletion {
                if (job.isCancelled) abort.abort(Exception("cancelled"))
            }
            try {
                val stream = dialer.dial(host, port, abort)
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
            dialer.dispose()
        }
    }
}

internal fun nowMs(): Long = io.bluewallet.blueberry.headers.nowMillis()
