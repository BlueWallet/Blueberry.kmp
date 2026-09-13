package io.bluewallet.blueberry.headers

import io.bluewallet.blueberry.peers.net.DnsResolver
import io.bluewallet.blueberry.peers.net.PlatformNet

fun stubPlatformNet(): PlatformNet =
    PlatformNet(
        connect = { _, _ -> error("stub PlatformNet.connect unused") },
        dns =
            object : DnsResolver {
                override suspend fun resolve4(host: String) = emptyList<String>()

                override suspend fun resolve6(host: String) = emptyList<String>()
            },
    )
