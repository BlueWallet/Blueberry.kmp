package io.bluewallet.blueberry

import io.bluewallet.blueberry.wallet.outputScriptFromAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SendFormatTest {
    @Test
    fun short_address_keeps_both_ends() {
        assertEquals("bc1qcr…6fyu", shortAddress("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"))
        assertEquals("bc1qshort", shortAddress(" bc1qshort "))
        assertEquals("bc1q…6fyu", shortAddress("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", head = 4, tail = 4))
    }

    @Test
    fun flow_chip_shortens_only_addresses() {
        val address = "bc1qhezl2peu0uv6qxjh0lmznp7vq8htm8gwryfcr9"
        assertEquals("bc1q…fcr9", flowChipLabel(address))
        assertEquals("bc1q…fcr9", flowChipLabel("bc1qhezl…gwryfcr9"))
        assertEquals("To bc1q…fcr9", flowChipLabel("To $address"))
        assertEquals("19GU…UHLE", flowChipLabel("19GUye5w7vYqR7W58BUdprd8RqRHs5UHLE"))
        assertEquals("Salary", flowChipLabel("Salary"))
        assertEquals("To Rent", flowChipLabel("To Rent"))
        assertEquals("Your change", flowChipLabel("Your change"))
        assertEquals("+2 more coins", flowChipLabel("+2 more coins"))
    }

    @Test
    fun address_groups_are_four_wide() {
        assertEquals(listOf("bc1q", "cr8t", "e4"), addressGroups("bc1qcr8te4"))
    }

    @Test
    fun address_type_label_names_standard_scripts() {
        assertEquals("Native SegWit", addressTypeLabel(outputScriptFromAddress("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu")))
        assertEquals(
            "Taproot",
            addressTypeLabel(outputScriptFromAddress("bc1p5d7rjq7g6rdk2yhzks9smlaqtedr4dekq08ge8ztwac72sfr9rusxg3297")),
        )
        assertEquals("Legacy", addressTypeLabel(outputScriptFromAddress("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2")))
        assertEquals("Script hash", addressTypeLabel(outputScriptFromAddress("3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy")))
        assertNull(addressTypeLabel(byteArrayOf()))
    }
}
