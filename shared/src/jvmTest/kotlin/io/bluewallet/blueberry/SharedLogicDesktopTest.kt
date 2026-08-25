package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedLogicDesktopTest {

    @Test
    fun example() {
        assertEquals(3, 1 + 2)
    }

    @Test
    fun vendorLibraryStatus_storage_kv_is_ok() {
        assertEquals(listOf("ok"), vendorLibraryStatus())
    }
}