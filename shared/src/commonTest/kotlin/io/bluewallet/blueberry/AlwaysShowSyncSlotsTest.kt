package io.bluewallet.blueberry

import io.bluewallet.blueberry.ui.metricCardLabelText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlwaysShowSyncSlotsTest {
    @Test
    fun phrase_is_the_large_value_not_the_small_label() {
        val slots = alwaysShowSyncSlots()
        assertEquals("Always show sync progress", slots.value)
        assertEquals("", slots.label)
        assertEquals("Keep Private Sync visible when idle", slots.caption)
        assertNull(metricCardLabelText(slots.label))
        assertTrue(slots.value != "On" && slots.value != "Off")
    }
}
