package io.bluewallet.blueberry

import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.echalote.Abort
import io.bluewallet.echalote.Echalote
import io.bluewallet.echalote.StreamFetchInit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.math.min

const val TOR_CHECK_HOST = "check.torproject.org"
const val TOR_CHECK_URL = "https://check.torproject.org/api/ip"

internal fun formatTorCheckElapsed(ms: Long): String {
    val tenths = (ms.coerceAtLeast(0L) + 50) / 100
    val sec = tenths / 10
    val frac = (tenths % 10).toInt()
    return if (frac == 0) "${sec}s" else "$sec.${frac}s"
}

data class TorCheckIp(val isTor: Boolean, val ip: String)

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
    val budget = min(120_000L, remainingMs.coerceAtLeast(60_000L))
    return coroutineScope {
        val abort = Abort()
        val timer = launch {
            delay(budget)
            abort.abort(Exception("timed out"))
        }
        val dialer = Echalote.createExitDialer()
        try {
            withTimeout(budget) {
                val tcp = dialer.dial(TOR_CHECK_HOST, 443, abort)
                try {
                    val tls = Echalote.wrapTls(tcp.outer, TOR_CHECK_HOST, abort)
                    val res = Echalote.streamFetch(TOR_CHECK_URL, StreamFetchInit(tls, abort))
                    if (!res.ok) throw Exception("HTTP ${res.status}")
                    val json = res.jsonObject()
                    val isTor = json["IsTor"] == "true"
                    val ip = json["IP"].orEmpty()
                    if (ip.isEmpty()) throw Exception("unexpected body: ${res.text()}")
                    TorCheckIp(isTor = isTor, ip = ip)
                } finally {
                    try {
                        tcp.close()
                    } catch (_: Throwable) {
                    }
                }
            }
        } finally {
            timer.cancel()
            abort.abort(Exception("timed out"))
            dialer.dispose()
        }
    }
}
