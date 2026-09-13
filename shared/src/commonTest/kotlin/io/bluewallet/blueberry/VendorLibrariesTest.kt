package io.bluewallet.blueberry

import io.bluewallet.bip157.NODE_COMPACT_FILTERS
import io.bluewallet.bip158.hexToBytes
import io.bluewallet.bip324.Networks
import io.bluewallet.echalote.Echalote
import io.bluewallet.headers.MAINNET_HEADER_CONSENSUS
import kotlin.test.Test
import kotlin.test.assertEquals

class VendorLibrariesTest {
    @Test
    fun vendorLibraryStatus_is_ok_when_every_unit_passes() {
        assertEquals(listOf("ok"), vendorLibraryStatus())
    }

    @Test
    fun vendorLibraries_are_wired() {
        assertEquals(665280, MAINNET_HEADER_CONSENSUS.checkpoint.height)
        assertEquals(8333, Networks.mainnet.defaultPort)
        assertEquals(64, NODE_COMPACT_FILTERS)
        assertEquals(1, hexToBytes("00").size)
        assertEquals("https://1603026938.rsc.cdn77.org/", Echalote.DEFAULT_MEEK_URL)
    }

    @Test
    fun vendorStatusLine_prints_unit_and_exact_error_when_unit_fails() {
        assertEquals(
            "headers: boom",
            vendorStatusLine("headers") { throw Exception("boom") },
        )
    }
}
