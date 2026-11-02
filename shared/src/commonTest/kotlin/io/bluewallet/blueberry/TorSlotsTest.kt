package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorSlotsTest {
    // Captions are one line with a switch beside them; this one is known to fit on a phone.
    private val fits = alwaysShowSyncSlots().caption.length

    @Test
    fun direct_relay_caption_is_short_and_keeps_the_privacy_warning() {
        val slots = torDirectRelaySlots()
        assertEquals("Connect to Tor directly", slots.value)
        assertEquals("Faster, but your network sees Tor", slots.caption)
        assertTrue(slots.caption.length <= fits, "caption would be cut off")
        assertFalse(slots.caption.contains("restart", ignoreCase = true))
    }

    @Test
    fun warm_up_caption_is_short() {
        val slots = torWarmUpSlots()
        assertEquals("Connects at launch, faster requests", slots.caption)
        assertTrue(slots.caption.length <= fits, "caption would be cut off")
    }
}
