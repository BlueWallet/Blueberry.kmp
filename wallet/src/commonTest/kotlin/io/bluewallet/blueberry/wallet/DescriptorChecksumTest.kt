package io.bluewallet.blueberry.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DescriptorChecksumTest {
    @Test
    fun bip380_vector() {
        assertEquals("89f8spxm", descriptorChecksum("raw(deadbeef)"))
    }

    @Test
    fun matches_reference_checksums() {
        assertEquals(TR_ABANDON_DESCRIPTOR.substringAfter('#'), descriptorChecksum(TR_ABANDON_DESCRIPTOR.substringBefore('#')))
        assertEquals(
            WPKH_ABANDON_RECEIVE_DESCRIPTOR.substringAfter('#'),
            descriptorChecksum(WPKH_ABANDON_RECEIVE_DESCRIPTOR.substringBefore('#')),
        )
    }

    @Test
    fun rejects_characters_outside_the_charset() {
        assertNull(descriptorChecksum("wpkh(é)"))
    }
}
