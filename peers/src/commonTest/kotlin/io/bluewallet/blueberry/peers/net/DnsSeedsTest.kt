package io.bluewallet.blueberry.peers.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DnsSeedsTest {
    @Test
    fun resolveSeedPeers_returns_ipv4_before_ipv6_with_given_port() =
        runBlocking {
            val peers =
                resolveSeedPeers(
                    listOf("seed.example"),
                    port = 8333,
                    resolver =
                        object : DnsResolver {
                            override suspend fun resolve4(host: String) = listOf("10.0.0.1")

                            override suspend fun resolve6(host: String) = listOf("2001:db8::1")
                        },
                    random = { 0.0 },
                )
            assertEquals(listOf("10.0.0.1", "2001:db8::1"), peers.map { it.host })
            assertTrue(peers.all { it.port == 8333 && it.services == 0uL })
        }

    @Test
    fun skips_seeds_whose_resolver_throws() =
        runBlocking {
            val peers =
                resolveSeedPeers(
                    listOf("bad", "good"),
                    port = 8333,
                    resolver =
                        object : DnsResolver {
                            override suspend fun resolve4(host: String): List<String> {
                                if (host == "bad") error("fail")
                                return listOf("9.9.9.9")
                            }

                            override suspend fun resolve6(host: String) = emptyList<String>()
                        },
                )
            assertEquals(listOf("9.9.9.9"), peers.map { it.host })
        }

    @Test
    fun resolves_all_seeds_concurrently() =
        runBlocking {
            val started = mutableListOf<String>()
            val release = mutableMapOf<String, CompletableDeferred<List<String>>>()
            val pending =
                async {
                    resolveSeedPeers(
                        listOf("a", "b"),
                        port = 8333,
                        resolver =
                            object : DnsResolver {
                                override suspend fun resolve4(host: String): List<String> {
                                    started.add(host)
                                    val gate = CompletableDeferred<List<String>>()
                                    release[host] = gate
                                    return gate.await()
                                }

                                override suspend fun resolve6(host: String) = emptyList<String>()
                            },
                    )
                }
            while (started.size < 2) delay(5)
            release["a"]!!.complete(listOf("10.0.0.1"))
            release["b"]!!.complete(listOf("10.0.0.2"))
            val peers = pending.await()
            assertEquals(listOf("10.0.0.1", "10.0.0.2"), peers.map { it.host }.sorted())
        }

    @Test
    fun hanging_seed_does_not_block_other_seeds() =
        runBlocking {
            val peers =
                resolveSeedPeers(
                    listOf("hang", "ok"),
                    port = 8333,
                    resolver =
                        object : DnsResolver {
                            override suspend fun resolve4(host: String): List<String> {
                                if (host == "hang") {
                                    CompletableDeferred<List<String>>().await()
                                }
                                return listOf("10.0.0.2")
                            }

                            override suspend fun resolve6(host: String) = emptyList<String>()
                        },
                    timeoutMs = 40,
                )
            assertEquals(listOf("10.0.0.2"), peers.map { it.host })
        }

    @Test
    fun keeps_ipv4_when_ipv6_hangs() =
        runBlocking {
            val peers =
                resolveSeedPeers(
                    listOf("mixed"),
                    port = 8333,
                    resolver =
                        object : DnsResolver {
                            override suspend fun resolve4(host: String) = listOf("10.0.0.1")

                            override suspend fun resolve6(host: String): List<String> {
                                CompletableDeferred<List<String>>().await()
                                return emptyList()
                            }
                        },
                    timeoutMs = 40,
                )
            assertEquals(listOf("10.0.0.1"), peers.map { it.host })
        }

    @Test
    fun mainnet_dns_seeds_match_helix3_order() {
        assertEquals(
            listOf(
                "seed.bitcoin.sipa.be",
                "dnsseed.bluematt.me",
                "seed.bitcoin.jonasschnelli.ch",
                "seed.btc.petertodd.net",
                "seed.bitcoin.sprovoost.nl",
                "dnsseed.emzy.de",
                "seed.bitcoin.wiz.biz",
            ),
            MAINNET_DNS_SEEDS,
        )
    }
}
