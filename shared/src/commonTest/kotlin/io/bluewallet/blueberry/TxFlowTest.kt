package io.bluewallet.blueberry

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TxFlowTest {
    @Test
    fun two_inputs_meet_three_outputs_at_the_middle() {
        val model =
            txFlowModel(
                inputs = listOf(node(40_000, "Small"), node(60_000, "Large")),
                outputs =
                    listOf(
                        TxFlowNode(50_000, "To Rent", TxFlowRole.Payment),
                        TxFlowNode(40_000, "Your change", TxFlowRole.Change),
                        TxFlowNode(10_000, "Network fee", TxFlowRole.Fee),
                    ),
            )
        assertEquals(listOf(60_000L, 40_000L), model.inputs.map { it.node.sats })
        assertEquals(158f, model.heightDp)
        assertEquals(163f, model.merge.xDp)
        assertEquals(79f, model.merge.yDp)
        assertEquals(
            120f,
            model.curves
                .first()
                .start.xDp,
        )
        assertEquals(
            163f,
            model.curves
                .first()
                .end.xDp,
        )
        assertEquals(
            206f,
            model.curves
                .last()
                .end.xDp,
        )
        assertEquals("2 in · 3 out", model.countLabel)
        val fee = model.curves.last()
        assertTrue(fee.dotted)
        assertClose(1.5f, fee.widthDp)
        val widths = model.curves.dropLast(1).map { it.widthDp }
        assertTrue(widths.all { it in 2f..7f })
        assertTrue(widths.first() > widths[1])
        assertEquals(100_000L, model.outputs.sumOf { it.node.sats })
    }

    @Test
    fun extra_width_keeps_the_chips_and_recenters_the_merge() {
        val model =
            txFlowModel(
                inputs = listOf(node(1_000, "Coin")),
                outputs = listOf(TxFlowNode(1_000, "Network fee", TxFlowRole.Fee)),
                widthDp = 366f,
            )
        assertEquals(366f, model.widthDp)
        assertEquals(120f, model.inputs.single().widthDp)
        assertEquals(183f, model.merge.xDp)
        assertEquals(246f, model.outputs.single().xDp)
        assertEquals(
            120f,
            model.curves
                .first()
                .start.xDp,
        )
    }

    @Test
    fun chips_use_the_width_they_are_given() {
        val model =
            txFlowModel(
                inputs = listOf(node(2_000, "Wide"), node(1_000, "Narrow")),
                outputs =
                    listOf(
                        TxFlowNode(2_500, "To Rent", TxFlowRole.Payment),
                        TxFlowNode(500, "Network fee", TxFlowRole.Fee),
                    ),
                widthDp = 400f,
                chipWidth = { node ->
                    when (node.label) {
                        "Wide" -> 140f
                        "Narrow" -> 90f
                        "To Rent" -> 130f
                        else -> 80f
                    }
                },
            )
        assertEquals(listOf(140f, 90f), model.inputs.map { it.widthDp })
        assertEquals(
            140f,
            model.curves
                .first()
                .start.xDp,
        )
        assertEquals(270f, model.outputs.first().xDp)
        assertEquals(320f, model.outputs.last().xDp)
    }

    @Test
    fun the_canvas_grows_so_chips_keep_a_middle_gap() {
        val model =
            txFlowModel(
                inputs = listOf(node(1_000, "Coin")),
                outputs = listOf(TxFlowNode(1_000, "Network fee", TxFlowRole.Fee)),
                widthDp = 200f,
                chipWidth = { _ -> 120f },
            )
        assertEquals(276f, model.widthDp)
        assertEquals(120f, model.inputs.single().widthDp)
        assertEquals(156f, model.outputs.single().xDp)
        assertEquals(138f, model.merge.xDp)
    }

    @Test
    fun no_change_keeps_two_output_chips() {
        val model =
            txFlowModel(
                inputs = listOf(node(100_000, "Coin")),
                outputs =
                    listOf(
                        TxFlowNode(99_000, "To Rent", TxFlowRole.Payment),
                        TxFlowNode(1_000, "Network fee", TxFlowRole.Fee),
                    ),
            )
        assertEquals(listOf(TxFlowRole.Payment, TxFlowRole.Fee), model.outputs.map { it.node.role })
        assertEquals("1 in · 2 out", model.countLabel)
    }

    @Test
    fun a_seventh_input_folds_into_one_chip_without_changing_the_count() {
        val inputs = (1..7).map { node(it * 10_000L, "Coin $it") }
        val model =
            txFlowModel(
                inputs = inputs,
                outputs =
                    listOf(
                        TxFlowNode(200_000, "To Rent", TxFlowRole.Payment),
                        TxFlowNode(70_000, "Your change", TxFlowRole.Change),
                        TxFlowNode(10_000, "Network fee", TxFlowRole.Fee),
                    ),
            )
        assertEquals(6, model.inputs.size)
        assertEquals(
            70_000L,
            model.inputs
                .first()
                .node.sats,
        )
        assertEquals(
            "+2 more coins",
            model.inputs
                .last()
                .node.label,
        )
        assertEquals(
            10_000L + 20_000L,
            model.inputs
                .last()
                .node.sats,
        )
        assertEquals("7 in · 3 out", model.countLabel)
    }
}

private fun node(
    sats: Long,
    label: String,
) = TxFlowNode(sats, label, TxFlowRole.Input, colorArgb = 0xFF112233)

private fun assertClose(
    expected: Float,
    actual: Float,
) {
    assertTrue(abs(expected - actual) < 0.001f, "$actual != $expected")
}
