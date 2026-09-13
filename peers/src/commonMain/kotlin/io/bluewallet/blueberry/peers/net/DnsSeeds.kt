package io.bluewallet.blueberry.peers.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlin.math.floor

val MAINNET_DNS_SEEDS: List<String> =
    listOf(
        "seed.bitcoin.sipa.be",
        "dnsseed.bluematt.me",
        "seed.bitcoin.jonasschnelli.ch",
        "seed.btc.petertodd.net",
        "seed.bitcoin.sprovoost.nl",
        "dnsseed.emzy.de",
        "seed.bitcoin.wiz.biz",
    )

private fun <T> shuffleInPlace(
    items: MutableList<T>,
    random: () -> Double,
): MutableList<T> {
    for (i in items.lastIndex downTo 1) {
        val j = floor(random() * (i + 1)).toInt().coerceIn(0, i)
        val tmp = items[i]
        items[i] = items[j]
        items[j] = tmp
    }
    return items
}

private suspend fun resolveFamily(
    timeoutMs: Long,
    task: suspend () -> List<String>,
): List<String> {
    val run =
        suspend {
            try {
                task()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
        }
    if (timeoutMs <= 0L) return run()
    return try {
        withTimeout(timeoutMs) { run() }
    } catch (_: TimeoutCancellationException) {
        emptyList()
    }
}

suspend fun resolveSeedPeers(
    seeds: List<String>,
    port: Int,
    resolver: DnsResolver,
    random: () -> Double = { kotlin.random.Random.nextDouble() },
    timeoutMs: Long = 3_000,
): List<PeerCandidate> =
    coroutineScope {
        val resolved =
            seeds
                .map { seed ->
                    async {
                        val v4 = async { resolveFamily(timeoutMs) { resolver.resolve4(seed) } }
                        val v6 = async { resolveFamily(timeoutMs) { resolver.resolve6(seed) } }
                        v4.await() to v6.await()
                    }
                }.awaitAll()
        val v4 = mutableListOf<PeerCandidate>()
        val v6 = mutableListOf<PeerCandidate>()
        for ((a, b) in resolved) {
            for (host in a) v4.add(PeerCandidate(host, port, 0uL))
            for (host in b) v6.add(PeerCandidate(host, port, 0uL))
        }
        shuffleInPlace(v4, random) + shuffleInPlace(v6, random)
    }
