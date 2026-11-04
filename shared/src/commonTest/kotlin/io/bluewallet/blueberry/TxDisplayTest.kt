package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TxDisplayTest {
    @Test
    @Suppress("LongMethod")
    fun received_fixture_matches_the_spec() {
        val screen = txScreen(received())
        val detail = screen.detail
        assertEquals("Received", detail.header.title)
        assertEquals("Today · 17:38", detail.header.subtitle)
        assertEquals("", detail.hero.amount.sign)
        assertEquals("0.001", detail.hero.amount.significant)
        assertEquals("00000", detail.hero.amount.trailingZeros)
        assertEquals("Confirmed", detail.status.title)
        assertEquals("12 confirmations", detail.status.sub)
        assertEquals("Settled", detail.status.badge)
        assertEquals("Your block · 972,347", detail.status.left)
        assertEquals("Latest · 972,358", detail.status.right)
        assertEquals(
            "11 blocks have been built on top of yours. Six is the usual bar for final.",
            detail.status.note,
        )
        assertEquals(List(12) { if (it == 0) TxStripCell.Block else TxStripCell.Confirmed }, detail.status.cells)
        assertEquals("Add a label", detail.label.title)
        assertEquals("1 in · 2 out", detail.flow.count)
        assertEquals(
            "Sender",
            detail.flow.inputs
                .single()
                .title,
        )
        assertEquals(
            "1 coin",
            detail.flow.inputs
                .single()
                .sub,
        )
        assertEquals(listOf("you", "notYours"), detail.flow.outputs.map { it.kind })
        assertEquals(listOf(100_000L, 438_211L), detail.flow.outputs.map { it.amount })
        assertEquals(detail.coins.single().outpoint, detail.flow.outputs[0].coinKey)
        assertNull(detail.flow.outputs[1].coinKey)
        assertEquals(
            "The sender paid the fee. Transactions don't record what the sender's coin held, so Blueberry can't show it.",
            detail.flow.note,
        )
        val coin = detail.coins.single()
        assertEquals("New coin", coin.title)
        assertEquals("Not linked to your other coins", coin.sub)
        assertEquals("Receive address #14 · first payment to it", coin.addressNote)
        assertEquals(listOf("Freeze", "Open coin"), coin.actions)
        assertEquals(
            listOf("Paid to a fresh address", "The sender can follow this coin", "Found without asking a server"),
            detail.reveals.map { it.title },
        )
        assertEquals("Label who sent it", detail.reveals[1].action)
        assertEquals("3e483d22…07e712a6 · 165 vB", detail.details.transactionSummary)
        assertEquals("972,347 · #838 in block", detail.details.blockSummary)
        assertEquals(246, detail.details.rawSizeBytes)
        assertEquals(657, detail.details.weightWU)
        assertEquals(165, detail.details.vsizeVB)
        assertEquals(83, detail.details.position?.cell)
        assertEquals(10, detail.details.position?.txPerCell)
        assertEquals("#838 of 3,214", detail.details.position?.label)
        assertEquals(19, detail.details.blockHashLeadingZeros)
        assertEquals(TxGlyph.Received, screen.list.glyph)
        assertEquals(TxBadge.None, screen.list.badge)
        assertEquals(TxListSubtitle.Noted("17:38", TxListLeadTone.Secondary, "No label"), screen.list.subtitle)
    }

    @Test
    @Suppress("LongMethod")
    fun dust_fixture_matches_the_spec_and_freezing_replaces_the_warning() {
        val open = txScreen(dust(frozen = false))
        assertEquals("Sep 10 · 40 days ago", open.detail.header.subtitle)
        assertEquals("0.00000292", open.detail.hero.amount.significant)
        assertEquals("", open.detail.hero.amount.trailingZeros)
        assertEquals("5,763 confirmations", open.detail.status.sub)
        assertEquals("+5,751 more on top", open.detail.status.right)
        assertEquals("Buried under 5,762 newer blocks.", open.detail.status.note)
        assertEquals("1 in · 9 out", open.detail.flow.count)
        assertEquals(
            listOf("you", "others", "notYours"),
            open.detail.flow.outputs
                .map { it.kind },
        )
        assertEquals(
            "7 others",
            open.detail.flow.outputs[1]
                .title,
        )
        assertEquals(
            2_044L,
            open.detail.flow.outputs[1]
                .amount,
        )
        assertEquals(
            104_310L,
            open.detail.flow.outputs[2]
                .amount,
        )
        assertEquals(
            "8 outputs of exactly 292 sats went to 8 different addresses in one transaction.",
            open.detail.flow.note,
        )
        assertEquals(
            "Unsolicited coin",
            open.detail.coins
                .single()
                .title,
        )
        assertEquals(
            "Same address as 3 earlier payments",
            open.detail.coins
                .single()
                .sub,
        )
        assertEquals(
            "Receive address #3 · received 4 payments",
            open.detail.coins
                .single()
                .addressNote,
        )
        assertEquals("Looks like dusting", open.detail.reveals[1].title)
        assertEquals("Freeze this coin", open.detail.reveals[1].action)
        assertEquals(386, open.detail.details.vsizeVB)
        assertEquals(1_541, open.detail.details.weightWU)
        assertEquals(
            274,
            open.detail.details.position
                ?.cell,
        )
        assertEquals(
            11,
            open.detail.details.position
                ?.txPerCell,
        )
        assertEquals(TxBadge.Warning, open.list.badge)
        assertEquals(
            TxListSubtitle.Noted("Looks like dusting", TxListLeadTone.Amber, "Sep 10"),
            open.list.subtitle,
        )

        val frozen = txScreen(dust(frozen = true))
        assertEquals("Frozen, so it can't link anything", frozen.detail.reveals[1].title)
        assertEquals("Unfreeze", frozen.detail.reveals[1].action)
        assertEquals(
            "Frozen · never picked when you send",
            frozen.detail.coins
                .single()
                .sub,
        )
        assertEquals(
            "Frozen",
            frozen.detail.coins
                .single()
                .actions
                .first(),
        )
        assertEquals(TxBadge.Frozen, frozen.list.badge)
        assertEquals(TxListSubtitle.Noted("Frozen", TxListLeadTone.Frozen, "Sep 10"), frozen.list.subtitle)
    }

    @Test
    fun pending_send_fixture_matches_the_spec() {
        val screen = txScreen(pendingSend())
        val detail = screen.detail
        assertEquals("Sending", detail.header.title)
        assertEquals("Today · 19:35", detail.header.subtitle)
        assertEquals("\u2212", detail.hero.amount.sign)
        assertEquals("0.00090423", detail.hero.amount.significant)
        assertEquals("", detail.hero.amount.trailingZeros)
        assertEquals("0.00090423", screen.list.amount.significant)
        assertEquals("Concert tickets", detail.hero.toLabel)
        assertEquals("Waiting for a block", detail.status.title)
        assertEquals("Broadcast 3 min ago · 3 sat/vB", detail.status.sub)
        assertEquals("Pending", detail.status.badge)
        assertNull(detail.status.right)
        assertEquals("1 in · 2 out", detail.flow.count)
        assertEquals(listOf("to", "change", "fee"), detail.flow.outputs.map { it.kind })
        assertEquals(listOf(90_000L, 21_977L, 423L), detail.flow.outputs.map { it.amount })
        assertNull(detail.flow.outputs[1].coinKey)
        assertTrue(detail.coins.isEmpty())
        assertEquals(
            listOf("No new links", "₿0.00021977 came back as change", "Broadcast over Tor"),
            detail.reveals.map { it.title },
        )
        assertEquals(
            "Spent only from Salary group, which is already linked on-chain.",
            detail.reveals[0].sub,
        )
        assertEquals("Not in a block yet", detail.details.blockSummary)
        assertEquals(141, detail.details.vsizeVB)
        assertEquals(561, detail.details.weightWU)
        assertEquals(222, detail.details.rawSizeBytes)
        assertEquals(TxGlyph.Sent, screen.list.glyph)
        assertTrue(screen.list.pendingGlyph)
        assertEquals(TxListSubtitle.Strip(pending = true, confirmations = 0, label = "Pending"), screen.list.subtitle)
    }

    @Test
    fun list_subtitle_priority_confirmations_moved_and_replaced() {
        val one = txScreen(pendingSend().copy(block = block(972_358, now(), 1, 100), tipHeight = 972_358))
        assertFalse(one.list.pendingGlyph)
        assertEquals(TxListSubtitle.Strip(false, 1, "1 of 6"), one.list.subtitle)
        val six = txScreen(pendingSend().copy(block = block(972_353, now(), 1, 100), tipHeight = 972_358))
        assertEquals(TxListSubtitle.DateOnly("19:38"), six.list.subtitle)

        val moved = txScreen(moved())
        assertEquals(
            "Your change",
            moved.detail.coins
                .single()
                .title,
        )
        assertEquals("Self", moved.detail.header.title)
        assertEquals("Moved between your coins", moved.detail.flow.note)
        assertEquals(423L, moved.detail.amountSats)
        assertEquals("Network fee", moved.list.amountSub)
        assertEquals(TxGlyph.Moved, moved.list.glyph)

        val replaced = txScreen(pendingSend().copy(outcome = TxOutcome.Replaced, replacementTxid = "aa".repeat(32)))
        assertEquals("Replaced", replaced.detail.header.title)
        assertEquals("Sending", txScreen(pendingSend().copy(broadcastAt = null)).detail.header.title)
        assertTrue(replaced.detail.status.failed)
        assertEquals(TxGlyph.Failed, replaced.list.glyph)
        assertEquals(TxBadge.None, replaced.list.badge)
        assertTrue(replaced.list.struck)
        assertEquals(TxListSubtitle.Status("Replaced"), replaced.list.subtitle)

        val unlabeled =
            txScreen(
                pendingSend().copy(
                    label = "",
                    block = block(966_000, TxCivilTime(2026, 9, 15, 12, 0), 1, 400),
                    tipHeight = 972_358,
                    walletInputs = listOf(TxDisplayInputCoin("only", 112_400L)),
                ),
            )
        assertEquals(TxListSubtitle.Noted("Sep 15", TxListLeadTone.Secondary, "No label"), unlabeled.list.subtitle)
    }

    @Test
    fun sections_put_pending_first_inside_today() {
        val now = now()
        val pending =
            TxListItem("p", pending = true, whenCivil = TxCivilTime(2026, 10, 19, 9, 0), epochS = 20, row = txScreen(pendingSend()).list)
        val today =
            TxListItem("t", pending = false, whenCivil = now, epochS = 50, row = txScreen(received()).list)
        val older =
            TxListItem(
                "o",
                pending = false,
                whenCivil = TxCivilTime(2025, 9, 10, 8, 0),
                epochS = 1,
                row = txScreen(dust(false)).list,
            )
        val sections = txListSections(listOf(older, today, pending), now)
        assertEquals(listOf("Today", "September 2025"), sections.map { it.title })
        assertEquals(listOf("p", "t"), sections.first().items.map { it.txid })
        val olderOnly = txListSections(listOf(older), now)
        assertEquals(listOf("Today", "September 2025"), sectionsWithLiveBroadcasts(olderOnly, 1).map { it.title })
        assertEquals(olderOnly, sectionsWithLiveBroadcasts(olderOnly, 0))
        assertEquals(sections, sectionsWithLiveBroadcasts(sections, 2))
    }

    @Test
    fun label_suggestions_and_coin_name_targets() {
        assertEquals(listOf("Rent", "Coffee"), labelSuggestions(listOf("Rent", "Coffee", "Rent", "  ")))
        assertEquals(
            listOf("a:0", "b:1"),
            coinNamesToUpdate(mapOf("a:0" to null, "b:1" to "Rent", "c:2" to "Keep"), "Rent"),
        )
    }

    @Test
    fun detail_flow_reuses_roles_without_changing_send_review_nodes() {
        val (inputs, outputs) = detailFlowNodes(txScreen(received()).detail.flow)
        assertEquals("Sender", inputs.single().headline)
        assertTrue(inputs.single().ring)
        assertEquals(TxFlowRole.Payment, outputs.first().role)
        assertNull(outputs.first().headline)
        val review = TxFlowNode(1_000L, "To Rent", TxFlowRole.Payment)
        assertNull(review.headline)
        assertFalse(review.ring)
    }
}

private fun now() = TxCivilTime(2026, 10, 20, 19, 38)

private fun block(
    height: Int,
    mined: TxCivilTime,
    index: Int,
    txCount: Int,
    hash: String = "00000000000000000001f079aa38e67da9994f2bea042d347fb67c5c0a6ea453",
) = TxDisplayBlock(height, hash, mined, index, txCount)

private fun received(): TxDisplayInput =
    TxDisplayInput(
        txid = "3e483d227a744b157c456d73b261656ab26d18462996480a893e527b07e712a6",
        direction = TxDirection.In,
        inputCount = 1,
        outputs =
            listOf(
                TxDisplayOutput(
                    vout = 0,
                    valueSat = 100_000L,
                    mine = true,
                    inWallet = true,
                    address = "bc1qw8k3m5h2v9xg4n7qz0r6tlp3c8ys5ufd2ja7x2",
                    addressIndex = 14,
                ),
                TxDisplayOutput(vout = 1, valueSat = 438_211L, mine = false),
            ),
        baseBytes = 137,
        witnessBytes = 109,
        block = block(972_347, TxCivilTime(2026, 10, 20, 17, 38), 838, 3_214),
        now = now(),
        nowEpochS = 1_000_000L,
        tipHeight = 972_358,
    )

private fun dust(frozen: Boolean): TxDisplayInput =
    TxDisplayInput(
        txid = "b71c0e94d2a85f3360e1c7b4a9f02d58e6c13a7f94b20d5e81c6a3f7d09b4e25",
        direction = TxDirection.In,
        label = "Unsolicited",
        inputCount = 1,
        outputs =
            listOf(0, 1).map { TxDisplayOutput(it, 292L, mine = false) } +
                TxDisplayOutput(2, 104_310L, mine = false) +
                TxDisplayOutput(
                    vout = 3,
                    valueSat = 292L,
                    mine = true,
                    inWallet = true,
                    addressIndex = 3,
                    addressUseCount = 4,
                    label = "Unsolicited",
                    frozen = frozen,
                ) +
                (4..8).map { TxDisplayOutput(it, 292L, mine = false) },
        baseBytes = 358,
        witnessBytes = 109,
        block =
            block(
                966_596,
                TxCivilTime(2026, 9, 10, 21, 4),
                2_911,
                3_388,
                "000000000000000000027c4ea1b9d03f58e6a2c7104bd95e3f80a6c21d7e94b3",
            ),
        now = now(),
        nowEpochS = 1_000_000L,
        tipHeight = 972_358,
    )

private fun pendingSend(): TxDisplayInput =
    TxDisplayInput(
        txid = "7d2f91c0a4e8b36152f0c9d7e3a1b8406f5c2e9d71a0b4c38e6f2d9a15c07b84",
        direction = TxDirection.Out,
        label = "Concert tickets",
        inputCount = 1,
        walletInputs =
            listOf(
                TxDisplayInputCoin(
                    outpoint = "324b41ec:0",
                    valueSat = 112_400L,
                    label = "Salary Sept",
                    groupName = "Salary group",
                    groupSize = 3,
                ),
            ),
        outputs =
            listOf(
                TxDisplayOutput(
                    vout = 0,
                    valueSat = 90_000L,
                    mine = false,
                    address = "bc1q2ddyzvdtk6trj6kvx53m25wvqymhj7jd9ewelh",
                ),
                TxDisplayOutput(
                    vout = 1,
                    valueSat = 21_977L,
                    mine = true,
                    change = true,
                    addressIndex = 22,
                    groupName = "Salary group",
                    groupSize = 3,
                ),
            ),
        baseBytes = 113,
        witnessBytes = 109,
        broadcastAt = TxCivilTime(2026, 10, 20, 19, 35),
        broadcastEpochS = 1_000_000L - 180L,
        now = now(),
        nowEpochS = 1_000_000L,
        tipHeight = 972_358,
        broadcast = TxBroadcast.Tor,
        ownSend = true,
    )

private fun moved(): TxDisplayInput =
    pendingSend().copy(
        direction = TxDirection.Moved,
        label = "send to self",
        outputs =
            listOf(
                TxDisplayOutput(
                    vout = 0,
                    valueSat = 111_977L,
                    mine = true,
                    inWallet = true,
                    change = true,
                    groupName = "Salary group",
                    groupSize = 3,
                ),
            ),
        block = block(972_300, TxCivilTime(2026, 10, 20, 18, 2), 10, 500),
    )
