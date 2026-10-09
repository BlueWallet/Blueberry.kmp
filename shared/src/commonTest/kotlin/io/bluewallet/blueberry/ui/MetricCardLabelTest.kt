package io.bluewallet.blueberry.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetricCardLabelTest {
    @Test
    fun blank_label_is_not_drawn() {
        assertNull(metricCardLabelText(""))
        assertNull(metricCardLabelText(" "))
        assertNull(metricCardLabelText("\n\t"))
    }

    @Test
    fun non_blank_label_is_kept() {
        assertEquals("Theme", metricCardLabelText("Theme"))
        assertEquals("Secret", metricCardLabelText("Secret"))
        assertEquals("Always show sync progress", metricCardLabelText("Always show sync progress"))
    }
}
