package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncMeshGradientTest {
    @Test
    fun dither_matches_exported_svg_cells() {
        assertTrue(shouldDrawSyncMeshDot(col = 0, row = 0, t = 3f / 187f))
        assertTrue(shouldDrawSyncMeshDot(col = 1, row = 0, t = 3f / 187f))
        assertFalse(shouldDrawSyncMeshDot(col = 0, row = 3, t = 21f / 187f))
        assertTrue(shouldDrawSyncMeshDot(col = 1, row = 3, t = 21f / 187f))
        assertFalse(shouldDrawSyncMeshDot(col = 0, row = 23, t = 141f / 187f))
        assertTrue(shouldDrawSyncMeshDot(col = 0, row = 28, t = 171f / 187f))
        assertFalse(shouldDrawSyncMeshDot(col = 1, row = 28, t = 171f / 187f))
    }
}
