package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VendorLibrariesTest {

    @Test
    fun vendorLibraryStatus_returns_vendor_lines_and_storage_kv() {
        val lines = vendorLibraryStatus()
        assertEquals(
            listOf(
                "headers: checkpoint 665280",
                "bip324: mainnet port 8333",
                "bip157: NODE_COMPACT_FILTERS 64",
                "bip158: hex 00 size 1",
                "echalote: meek https://1603026938.rsc.cdn77.org/",
            ),
            lines.take(5),
        )
        assertEquals(7, lines.size)
        assertTrue(lines[5].startsWith("storage:"), lines[5])
        assertEquals("qr: ImageTooSmallException", lines[6])
    }

    @Test
    fun vendorStatusLine_uses_error_prefix_when_block_throws() {
        assertEquals(
            "headers: error boom",
            vendorStatusLine("headers") { throw Exception("boom") },
        )
    }
}
