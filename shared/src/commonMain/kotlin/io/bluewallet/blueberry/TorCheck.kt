package io.bluewallet.blueberry

import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.echalote.Abort
import io.bluewallet.echalote.Echalote
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

const val TOR_CHECK_HOST = "api.rocketx.exchange"
const val TOR_CHECK_URL = "https://api.rocketx.exchange/v1/configs"

internal fun formatTorCheckElapsed(ms: Long): String {
    val tenths = (ms.coerceAtLeast(0L) + 50) / 100
    val sec = tenths / 10
    val frac = (tenths % 10).toInt()
    return if (frac == 0) "${sec}s" else "$sec.${frac}s"
}

data class TorCheckIp(
    val isTor: Boolean,
    val ip: String,
)

suspend fun checkTorExit(
    overallMs: Long = 180_000L,
    backoffMs: Long = 1_500L,
    nowMs: () -> Long = { nowMillis() },
    delayMs: suspend (Long) -> Unit = { delay(it) },
    fetchOnce: suspend (remainingMs: Long) -> TorCheckIp = { remaining ->
        fetchTorCheckIp(remaining)
    },
): List<String> {
    val started = nowMs()
    val deadline = started + overallMs
    var lastError: Throwable? = null
    var attempt = 0
    val seenIps = ArrayList<String>()
    while (attempt == 0 || nowMs() < deadline) {
        attempt++
        val remaining = (deadline - nowMs()).coerceAtLeast(0L)
        try {
            val got = fetchOnce(remaining)
            seenIps += got.ip
            if (!got.isTor) {
                throw Exception(
                    "IsTor=false IP=${got.ip} attempt=$attempt seen=${seenIps.joinToString(",")}",
                )
            }
            return listOf("ok", formatTorCheckElapsed(nowMs() - started))
        } catch (err: Throwable) {
            println("TORCHECK fail attempt=$attempt ${err.message}")
            lastError = err
            if (nowMs() >= deadline) break
            if (backoffMs > 0L) delayMs(backoffMs)
        }
    }
    val ips = if (seenIps.isEmpty()) "(none)" else seenIps.joinToString(",")
    val last = lastError?.message ?: lastError.toString()
    return listOf("integration failed after $attempt attempt(s); exitIPs=$ips; last=$last")
}

suspend fun fetchTorCheckIp(remainingMs: Long): TorCheckIp {
    val budget = min(120_000L, remainingMs.coerceAtLeast(1L))
    return coroutineScope {
        val abort = Abort()
        val timer =
            launch {
                delay(budget)
                abort.abort(Exception("timed out"))
            }
        try {
            println("TORCHECK fetch-start url=$TOR_CHECK_URL remaining=$remainingMs budget=$budget")
            val res = Echalote.fetch(TOR_CHECK_URL, abort)
            println("TORCHECK http status=${res.status} bytes=${res.body.size}")
            parseTorCheckResponse(res.status, res.body)
        } finally {
            timer.cancel()
            abort.abort(Exception("timed out"))
        }
    }
}

internal fun parseTorCheckResponse(
    status: Int,
    body: ByteArray,
): TorCheckIp {
    if (status !in 200..299) error("HTTP $status")
    if (body.isEmpty() || body[0] != '{'.code.toByte()) {
        error("unexpected body")
    }
    return TorCheckIp(isTor = true, ip = TOR_CHECK_HOST)
}
