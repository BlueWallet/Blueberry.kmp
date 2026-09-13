package io.bluewallet.blueberry.peers.net

import io.bluewallet.bip324.ByteDuplex

data class PeerCandidate(
    val host: String,
    val port: Int,
    val services: ULong,
)

interface DnsResolver {
    suspend fun resolve4(host: String): List<String>

    suspend fun resolve6(host: String): List<String>
}

typealias TcpConnect = suspend (host: String, port: Int) -> ByteDuplex

data class PlatformNet(
    val connect: TcpConnect,
    val dns: DnsResolver,
)
