package io.bluewallet.blueberry.peers.net

import io.bluewallet.bip324.ByteDuplex

fun stubDuplex(): ByteDuplex =
    object : ByteDuplex {
        override suspend fun read(n: Int): ByteArray = ByteArray(0)

        override suspend fun write(bytes: ByteArray) {}

        override suspend fun close() {}
    }

fun stubPlatformNet(): PlatformNet =
    PlatformNet(
        connect = { _, _ -> error("stub PlatformNet.connect unused") },
        dns =
            object : DnsResolver {
                override suspend fun resolve4(host: String) = emptyList<String>()

                override suspend fun resolve6(host: String) = emptyList<String>()
            },
    )
