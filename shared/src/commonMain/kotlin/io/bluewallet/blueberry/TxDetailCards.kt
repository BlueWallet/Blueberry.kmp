package io.bluewallet.blueberry

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluewallet.blueberry.ui.AddressGroups
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.Chevron
import io.bluewallet.blueberry.ui.Pressable
import io.bluewallet.blueberry.ui.keepAboveKeyboard

@Composable
internal fun TxLabelCard(
    detail: TxDetail,
    host: TxDetailHost,
) {
    val failed = detail.outcome != TxOutcome.None
    val typed =
        if (host.editing) {
            host.draft.trim()
        } else if (detail.label.hasLabel) {
            detail.label.title
        } else {
            ""
        }
    val turn by animateFloatAsState(if (host.editing) 90f else 0f, label = "label-chevron")
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(txListCard())
                .padding(16.dp),
    ) {
        Pressable(onClick = { if (!failed) host.onEdit() }, enabled = !failed, role = Role.Button, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(txInsetFill()),
                    contentAlignment = Alignment.Center,
                ) {
                    LabelTag()
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        typed.ifEmpty { "Add a label" },
                        color = if (typed.isEmpty()) TxBlue else BwColors.Ink,
                        fontFamily = BwFontFamily,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.45).sp,
                    )
                    Text(
                        if (typed.isEmpty()) detail.label.subtitle else "On this transaction and its coin",
                        color = BwColors.InkSecondary,
                        fontFamily = BwFontFamily,
                        fontSize = 13.sp,
                    )
                }
                Chevron(Modifier.rotate(turn))
            }
        }
        AnimatedVisibility(
            visible = host.editing,
            enter = txAccordionEnter,
            exit = txAccordionExit,
        ) {
            TxLabelEditor(detail, host)
        }
    }
}

@Composable
private fun LabelTag() {
    val color = TxBlue
    Canvas(Modifier.size(18.dp)) {
        val stroke =
            Stroke(
                width = 1.6.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
        val path =
            Path().apply {
                moveTo(size.width * 0.12f, size.height * 0.30f)
                lineTo(size.width * 0.52f, size.height * 0.30f)
                lineTo(size.width * 0.90f, size.height * 0.50f)
                lineTo(size.width * 0.52f, size.height * 0.70f)
                lineTo(size.width * 0.12f, size.height * 0.70f)
                close()
            }
        drawPath(path, color, style = stroke)
        drawCircle(color, radius = 1.3.dp.toPx(), center = Offset(size.width * 0.30f, size.height * 0.50f))
    }
}

@Composable
private fun TxLabelEditor(
    detail: TxDetail,
    host: TxDetailHost,
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TxLabelField(detail, host, requester)
        TxLabelSuggestions(host)
        Text(
            "Stays on this phone. Also applied to the coin this transaction created.",
            color = BwColors.InkSecondary,
            fontFamily = BwFontFamily,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun TxLabelField(
    detail: TxDetail,
    host: TxDetailHost,
    requester: FocusRequester,
) {
    val focus = LocalFocusManager.current
    BasicTextField(
        value = host.draft,
        onValueChange = host.onDraft,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        textStyle =
            androidx.compose.ui.text
                .TextStyle(color = BwColors.Ink, fontFamily = BwFontFamily, fontSize = 16.sp),
        cursorBrush = SolidColor(TxBlue),
        modifier =
            Modifier
                .focusRequester(requester)
                .keepAboveKeyboard()
                .fillMaxWidth()
                .height(50.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(txInsetFill())
                .border(1.dp, txInsetBorder(), RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 14.dp),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                if (host.draft.isEmpty()) {
                    Text(detail.label.placeholder, color = BwColors.InkMuted, fontFamily = BwFontFamily)
                }
                inner()
            }
        },
    )
}

@Composable
private fun TxLabelSuggestions(host: TxDetailHost) {
    if (host.suggestions.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        host.suggestions.forEach { suggestion ->
            Pressable(onClick = { host.onDraft(suggestion) }, role = Role.Button) {
                Text(
                    suggestion,
                    color = BwColors.Ink,
                    fontFamily = BwFontFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.45).sp,
                    maxLines = 1,
                    softWrap = false,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(17.dp))
                            .background(txInsetFill())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
internal fun TxFlowCard(
    detail: TxDetail,
    host: TxDetailHost,
) {
    var openKey by remember { mutableStateOf<String?>(null) }
    val flow = detail.flow
    val nodes = detailFlowNodes(flow)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(txRaisedCard())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "How the coins moved",
                color = BwColors.Ink,
                fontFamily = BwFontFamily,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.45).sp,
            )
            Text(flow.count, color = BwColors.InkSecondary, fontFamily = BwFontFamily, fontSize = 13.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(flow.fromColumn, color = BwColors.InkSecondary, fontFamily = BwFontFamily, fontSize = 12.sp)
            Text(flow.toColumn, color = BwColors.InkSecondary, fontFamily = BwFontFamily, fontSize = 12.sp)
        }
        TxFlowGraph(
            nodes.first,
            nodes.second,
            detailFlowDescription(flow),
            openCoin = openKey,
            onCoin = { key -> openKey = if (openKey == key) null else key },
        )
        val note = flow.note
        if (note != null) Text(note, color = BwColors.InkSecondary, fontFamily = BwFontFamily, fontSize = 12.sp)
        if (detail.coins.isNotEmpty()) {
            Column(Modifier.fillMaxWidth()) {
                detail.coins.forEach { coin ->
                    AnimatedVisibility(
                        visible = coin.outpoint == openKey,
                        enter = txAccordionEnter,
                        exit = txAccordionExit,
                    ) {
                        TxCoinBlock(coin, detail.outcome == TxOutcome.None, host)
                    }
                }
            }
        }
    }
}

@Composable
private fun TxCoinBlock(
    coin: TxCoinCard,
    enabled: Boolean,
    host: TxDetailHost,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(Color(utxoCircleArgb(coin.outpoint))))
            Text(
                coin.title,
                color = BwColors.Ink,
                fontFamily = BwFontFamily,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.45).sp,
                modifier = Modifier.weight(1f),
            )
            TxAmountLine(txAmountParts(coin.amountSats, received = true).copy(sign = ""), txAmountTone(TxDirection.Out, false), 14.sp, struck = false)
        }
        Text(coin.sub, color = if (coin.subTone == TxTone.Blue) TxFrozen else BwColors.InkSecondary, fontFamily = BwFontFamily, fontSize = 13.sp)
        val address = coin.address
        if (address != null) AddressGroups(address, Modifier.fillMaxWidth())
        Text(coin.addressNote, color = BwColors.InkSecondary, fontFamily = BwFontFamily, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        TxCoinActions(coin, enabled, host)
    }
}

@Composable
private fun TxCoinActions(
    coin: TxCoinCard,
    enabled: Boolean,
    host: TxDetailHost,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        coin.actions.forEach { action ->
            val freeze = action == "Freeze" || action == "Frozen"
            Pressable(
                onClick = {
                    if (!enabled) return@Pressable
                    when (action) {
                        "Open coin" -> host.onOpenCoin(coin.outpoint)
                        "Open transaction" -> coin.spentTxid?.let(host.onOpenTx)
                        "Freeze" -> host.onFreeze(coin.outpoint, true)
                        "Frozen" -> host.onFreeze(coin.outpoint, false)
                    }
                },
                enabled = enabled,
                role = Role.Button,
            ) {
                val link = action == "Open transaction"
                Text(
                    action,
                    color =
                        when {
                            link -> txStatusBlueFg()
                            freeze && coin.frozen -> TxFrozen
                            else -> BwColors.Ink
                        },
                    fontFamily = BwFontFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (link) txStatusBlueBg() else txCoinActionFill())
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
internal fun TxRevealCard(
    detail: TxDetail,
    host: TxDetailHost,
) {
    if (detail.reveals.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetSectionHeader("What this reveals")
        SheetCard {
            detail.reveals.forEachIndexed { index, reveal ->
                if (index > 0) SheetDivider()
                TxRevealRow(reveal, detail.outcome == TxOutcome.None, host)
            }
        }
    }
}

@Composable
private fun TxRevealRow(
    reveal: TxReveal,
    enabled: Boolean,
    host: TxDetailHost,
) {
    val (glyph, tint, fill) =
        when (reveal.tone) {
            TxTone.Green -> Triple(SheetGlyph.Check, SheetPalette.green, SheetPalette.greenFill)
            TxTone.Amber -> Triple(SheetGlyph.Warn, SheetPalette.amber, SheetPalette.amberFill)
            else -> Triple(SheetGlyph.Eye, SheetPalette.blue, SheetPalette.blueFill)
        }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SheetIconCircle(glyph, tint, fill, 30.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val titleColor = if (reveal.tone == TxTone.Amber) SheetPalette.amber else SheetPalette.text
            SheetText(reveal.title, 15.sp, titleColor, FontWeight.Bold)
            SheetText(reveal.sub, 13.sp)
            val action = reveal.action
            val outpoint = reveal.outpoint
            if (action != null && outpoint != null && enabled) {
                Pressable(
                    onClick = {
                        when (action) {
                            "Freeze this coin" -> host.onFreeze(outpoint, true)
                            "Unfreeze" -> host.onFreeze(outpoint, false)
                            "Label who sent it" -> host.onEdit()
                        }
                    },
                    role = Role.Button,
                ) {
                    SheetText(action, 13.sp, SheetPalette.blue, FontWeight.Bold)
                }
            }
        }
    }
}
