package io.bluewallet.blueberry.peers.net

import io.bluewallet.bip324.NetworkAddressV2
import io.bluewallet.bip324.TimedNetworkAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AddrTest {
    @Test
    fun parses_ipv4_addrv2_and_legacy_mapped_ipv4_skips_onion_and_bad_port() {
        assertEquals(
            PeerCandidate("1.2.3.4", 8333, 1033uL),
            addrV2ToCandidate(
                NetworkAddressV2(
                    time = 1u,
                    services = 1033uL,
                    networkId = 1,
                    address = byteArrayOf(1, 2, 3, 4),
                    port = 8333,
                ),
            ),
        )

        assertNull(
            addrV2ToCandidate(
                NetworkAddressV2(
                    time = 1u,
                    services = 0uL,
                    networkId = 4,
                    address = ByteArray(32),
                    port = 8333,
                ),
            ),
        )

        assertNull(
            addrV2ToCandidate(
                NetworkAddressV2(
                    time = 1u,
                    services = 0uL,
                    networkId = 1,
                    address = byteArrayOf(1, 2, 3, 4),
                    port = 0,
                ),
            ),
        )

        val ip = ByteArray(16)
        ip[10] = 0xff.toByte()
        ip[11] = 0xff.toByte()
        ip[12] = 8
        ip[13] = 8
        ip[14] = 8
        ip[15] = 8
        assertEquals(
            PeerCandidate("8.8.8.8", 8333, 1uL),
            legacyAddrToCandidate(TimedNetworkAddress(1u, 1uL, ip, 8333)),
        )
    }
}
