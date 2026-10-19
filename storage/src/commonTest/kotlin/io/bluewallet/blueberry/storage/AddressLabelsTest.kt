package io.bluewallet.blueberry.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AddressLabelsTest {
    @Test
    fun get_upsert_delete_list_by_address() {
        val db = createSqliteDatabase(":memory:")
        val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        assertNull(db.addressLabels.get(address))
        assertEquals(emptyList(), db.addressLabels.list())
        db.addressLabels.upsert(address, "donations")
        assertEquals("donations", db.addressLabels.get(address))
        assertEquals(listOf(AddressLabelRow(address, "donations")), db.addressLabels.list())
        db.addressLabels.upsert(address, "renamed")
        assertEquals("renamed", db.addressLabels.get(address))
        db.addressLabels.delete(address)
        assertNull(db.addressLabels.get(address))
        assertEquals(emptyList(), db.addressLabels.list())
        db.close()
    }
}
