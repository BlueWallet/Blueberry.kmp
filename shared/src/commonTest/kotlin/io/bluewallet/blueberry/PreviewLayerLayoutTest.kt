package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals

class PreviewLayerLayoutTest {
    @Test
    fun preview_layer_fills_host_at_local_origin() {
        val frame = previewLayerLocalFrame(hostWidth = 320.0, hostHeight = 568.0)
        assertEquals(0.0, frame.x)
        assertEquals(0.0, frame.y)
        assertEquals(320.0, frame.width)
        assertEquals(568.0, frame.height)
    }
}
