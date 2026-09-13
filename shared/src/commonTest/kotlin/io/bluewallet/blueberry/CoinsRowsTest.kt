package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals

class CoinsRowsTest {
    private fun row(
        key: String,
        valueSats: Long,
        name: String? = null,
        isChange: Boolean = false,
        ageLabel: String = "",
    ) = WalletUtxoRow(
        key = key,
        txid = key.substringBefore(':'),
        vout = key.substringAfter(':').toInt(),
        outpointShort = key.take(8),
        valueSats = valueSats,
        scriptPubKey = byteArrayOf(),
        amountLabel = "",
        height = 0,
        ageLabel = ageLabel,
        valueBar = "",
        name = name,
        isChange = isChange,
    )

    @Test
    fun empty_list_is_empty() {
        assertEquals(emptyList(), coinsRows(emptyList()))
    }

    @Test
    fun maps_circle_amount_name_and_relative_bar() {
        val a = row("abc:0", 100, "coffee")
        val b = row("abc:1", 50, null)
        val c = row("abc:2", 1, null)
        val rows = coinsRows(listOf(a, b, c))
        assertEquals(listOf("abc:0", "abc:1", "abc:2"), rows.map { it.key })
        assertEquals(listOf(100L, 50L, 1L), rows.map { it.valueSats })
        assertEquals(listOf("coffee", null, null), rows.map { it.name })
        assertEquals(utxoCircleArgb("abc:0"), rows[0].circleArgb)
        assertEquals(utxoCircleArgb("abc:1"), rows[1].circleArgb)
        assertEquals(100, rows[0].barPercent)
        assertEquals(50, rows[1].barPercent)
        assertEquals(1, rows[2].barPercent)
        assertEquals(listOf(false, false, false), rows.map { it.isChange })
    }

    @Test
    fun copies_isChange() {
        val rows = coinsRows(listOf(row("aa:0", 2, isChange = true), row("aa:1", 1, isChange = false)))
        assertEquals(listOf(true, false), rows.map { it.isChange })
    }

    @Test
    fun preserves_input_order() {
        val rows = coinsRows(listOf(row("zz:1", 1), row("aa:0", 9)))
        assertEquals(listOf("zz:1", "aa:0"), rows.map { it.key })
    }

    @Test
    fun copies_ageLabel() {
        val padded = "3 years ago".padEnd(16)
        val rows = coinsRows(
            listOf(
                row("aa:0", 2, ageLabel = padded),
                row("aa:1", 1, ageLabel = ""),
            ),
        )
        assertEquals(listOf(padded, ""), rows.map { it.ageLabel })
    }

    @Test
    fun caption_is_trimmed_age_then_name_like_send() {
        assertEquals(null, coinsRowCaption("", null))
        assertEquals(null, coinsRowCaption("   ", null))
        assertEquals("3 years ago", coinsRowCaption("3 years ago".padEnd(16), null))
        assertEquals("coffee", coinsRowCaption("", "coffee"))
        assertEquals("3 years ago  coffee", coinsRowCaption("3 years ago".padEnd(16), "coffee"))
    }
}
