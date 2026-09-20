package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals

class SendUtxoCaptionTest {
    @Test
    fun shortens_address_like_elsewhere_not_outpoint() {
        assertEquals(
            "bc1qhezl…gwryfcr9",
            sendUtxoCaption("bc1qhezl2peu0uv6qxjh0lmznp7vq8htm8gwryfcr9", "", null),
        )
        assertEquals("bc1qabc", sendUtxoCaption("bc1qabc", "", null))
        assertEquals("19GUye5w…RHs5UHLE", sendUtxoCaption("19GUye5w7vYqR7W58BUdprd8RqRHs5UHLE", "", null))
    }

    @Test
    fun joins_short_address_age_and_name_with_two_spaces() {
        assertEquals(
            "bc1qhezl…gwryfcr9  3 years ago  coffee",
            sendUtxoCaption(
                "bc1qhezl2peu0uv6qxjh0lmznp7vq8htm8gwryfcr9",
                "3 years ago".padEnd(16),
                "coffee",
            ),
        )
    }

    @Test
    fun skips_blank_parts_and_does_not_fall_back_to_outpoint() {
        assertEquals("", sendUtxoCaption(null, "", null))
        assertEquals("", sendUtxoCaption("  ", "   ", "  "))
        assertEquals("9d ago", sendUtxoCaption(null, "9d ago".padEnd(16), null))
        assertEquals("bc1qabc  coffee", sendUtxoCaption("bc1qabc", "", "  coffee  "))
    }
}
