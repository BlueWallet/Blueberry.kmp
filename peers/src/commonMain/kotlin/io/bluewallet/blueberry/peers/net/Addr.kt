package io.bluewallet.blueberry.peers.net

import io.bluewallet.bip324.NetworkAddressV2
import io.bluewallet.bip324.TimedNetworkAddress

fun ipv4BytesToHost(bytes: ByteArray): String = "${bytes[0].toUByte()}.${bytes[1].toUByte()}.${bytes[2].toUByte()}.${bytes[3].toUByte()}"

fun ipv6BytesToHost(bytes: ByteArray): String {
    val groups = ArrayList<Int>(8)
    var i = 0
    while (i < 16) {
        groups.add(((bytes[i].toInt() and 0xff) shl 8) or (bytes[i + 1].toInt() and 0xff))
        i += 2
    }
    return groups.joinToString(":") { it.toString(16) }
}

fun addrV2ToCandidate(address: NetworkAddressV2): PeerCandidate? {
    if (address.port <= 0 || address.port > 65535) return null
    if (address.networkId == 1 && address.address.size == 4) {
        return PeerCandidate(
            host = ipv4BytesToHost(address.address),
            port = address.port,
            services = address.services,
        )
    }
    if (address.networkId == 2 && address.address.size == 16) {
        val host = ipv6BytesToHost(address.address)
        if (host == "0:0:0:0:0:0:0:0") return null
        return PeerCandidate(host = host, port = address.port, services = address.services)
    }
    return null
}

fun legacyAddrToCandidate(address: TimedNetworkAddress): PeerCandidate? {
    if (address.port <= 0 || address.port > 65535) return null
    val ip = address.ip
    val mapped =
        ip.size >= 16 &&
            ip.take(12).withIndex().all { (i, b) ->
                b == if (i < 10) 0.toByte() else 0xff.toByte()
            }
    if (mapped) {
        return PeerCandidate(
            host = ipv4BytesToHost(ip.copyOfRange(12, 16)),
            port = address.port,
            services = address.services,
        )
    }
    if (ip.all { it == 0.toByte() }) return null
    return PeerCandidate(
        host = ipv6BytesToHost(ip),
        port = address.port,
        services = address.services,
    )
}
