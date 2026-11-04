@file:Suppress("TooManyFunctions")

package io.bluewallet.blueberry

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.Chevron
import io.bluewallet.blueberry.ui.LocalDarkPalette
import io.bluewallet.blueberry.ui.PlatformModalSheet
import io.bluewallet.blueberry.ui.PlatformSheetHeader
import io.bluewallet.blueberry.ui.Pressable
import io.bluewallet.blueberry.ui.ProminentButtonHeight
import io.bluewallet.blueberry.ui.SheetClose
import io.bluewallet.blueberry.ui.TintedPillButton

/** Height and fade. The row itself scales while pressed. */
internal val txAccordionEnter: EnterTransition =
    expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(160))

internal val txAccordionExit: ExitTransition =
    shrinkVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(120))

@Composable
internal fun TxFactsCard(
    details: TxDetailsModel,
    privateFacts: List<Pair<String, String>>,
    onCopy: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetSectionHeader("Details")
        SheetCard {
            FactAccordion("Transaction", details.transactionSummary) {
                TransactionBody(details, privateFacts, onCopy)
            }
            FactAccordion("Block", details.blockSummary) { BlockBody(details) }
        }
    }
}

@Composable
private fun FactAccordion(
    title: String,
    collapsed: String,
    body: @Composable () -> Unit,
) {
    var open by remember(title) { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 90f else 0f)
    Column {
        Pressable(
            onClick = { open = !open },
            role = Role.Button,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 66.dp)
                    .semantics { stateDescription = if (open) "Expanded" else "Collapsed" },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        color = BwColors.Ink,
                        fontFamily = BwFontFamily,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.45).sp,
                    )
                    Text(collapsed, color = BwColors.InkSecondary, fontFamily = BwFontFamily, fontSize = 12.sp)
                }
                Chevron(Modifier.rotate(turn))
            }
        }
        AnimatedVisibility(
            visible = open,
            enter = txAccordionEnter,
            exit = txAccordionExit,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { body() }
        }
    }
}

@Composable
private fun TransactionBody(
    details: TxDetailsModel,
    privateFacts: List<Pair<String, String>>,
    onCopy: () -> Unit,
) {
    Pressable(onClick = onCopy, role = Role.Button) {
        Text("Copy transaction ID", color = TxBlue, fontFamily = BwFontFamily, fontWeight = FontWeight.Bold)
    }
    GroupedHash(details.txid, 0)
    BlockSpaceBar(details)
    FactGrid(
        listOf(
            "Inputs" to details.inputCount.toString(),
            "Outputs" to details.outputCount.toString(),
            "Weight" to "${txGroupDigits(details.weightWU.toLong())} WU",
            "Raw size" to "${details.rawSizeBytes} B",
        ),
    )
    privateFacts.forEach { (label, value) ->
        Text(label, color = BwColors.InkSecondary, fontFamily = BwFontFamily, fontSize = 12.sp)
        Text(value, color = BwColors.Ink, fontFamily = BwFontFamily, fontSize = 14.sp)
    }
}

@Composable
private fun BlockSpaceBar(details: TxDetailsModel) {
    val witness = details.witnessBytes
    val baseFlex = (details.baseBytes * 4).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "Block space",
                color = BwColors.Ink,
                fontFamily = BwFontFamily,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.45).sp,
            )
            Text("${details.vsizeVB} vB", color = BwColors.InkSecondary, fontFamily = BwFontFamily)
        }
        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
            Box(Modifier.weight(baseFlex.toFloat()).fillMaxHeight().background(TxBlue))
            if (witness > 0) {
                Spacer(Modifier.width(2.dp))
                Box(Modifier.weight(witness.toFloat()).fillMaxHeight().background(Color(0xFFC9CED3)))
            }
        }
        Text(
            "Base ${details.baseBytes} B × 4",
            color = BwColors.InkSecondary,
            fontFamily = BwFontFamily,
            fontSize = 12.sp,
        )
        if (witness > 0) {
            Text("Signatures $witness B × 1", color = BwColors.InkSecondary, fontFamily = BwFontFamily, fontSize = 12.sp)
        }
        val note =
            if (witness == 0) {
                "No signature discount: legacy transactions pay for every byte."
            } else {
                "Signature data counts at a quarter of the weight, so ${details.rawSizeBytes} bytes take ${details.vsizeVB} vB of block space. Fees are priced per vB."
            }
        Text(note, color = BwColors.InkQuiet, fontFamily = BwFontFamily, fontSize = 13.sp)
    }
}

@Composable
private fun BlockBody(details: TxDetailsModel) {
    if (details.pendingBlock) {
        Text(
            "Waiting in the mempool. Height, position and block hash appear once a miner includes it.",
            color = BwColors.InkSecondary,
            fontFamily = BwFontFamily,
            fontSize = 13.sp,
        )
        return
    }
    FactGrid(
        listOf(
            "Height" to details.heightLabel.orEmpty(),
            "Fee rate" to details.feeRateLabel.orEmpty(),
        ),
    )
    val position = details.position
    if (position != null) {
        Text(
            position.label,
            color = BwColors.Ink,
            fontFamily = BwFontFamily,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.45).sp,
        )
        PositionGrid(position)
        Text(
            "Each square is about ${position.txPerCell} transactions. Miners roughly order a block by fee rate, highest first.",
            color = BwColors.InkSecondary,
            fontFamily = BwFontFamily,
            fontSize = 12.sp,
        )
    }
    val hash = details.blockHash
    if (hash != null) {
        DimmedHash(hash, details.blockHashLeadingZeros ?: 0)
    }
}

@Composable
private fun PositionGrid(position: TxPosition) {
    val shown = if (position.txCount < 320) position.txCount else 320
    val dark = LocalDarkPalette.current
    val earlier = if (dark) Color(0xFF363B41) else Color(0xFF5C636B)
    val later = if (dark) Color(0xFF2A2E33) else Color(0xFFE4E7EB)
    Column(Modifier.fillMaxWidth().semantics { contentDescription = position.label }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(10) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(32) { column ->
                    val index = row * 32 + column
                    val color =
                        when {
                            index >= shown -> Color.Transparent
                            index == position.cell -> TxBlue
                            index < position.cell -> earlier
                            else -> later
                        }
                    Box(
                        Modifier
                            .weight(1f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(color),
                    )
                }
            }
        }
    }
}

@Composable
private fun FactGrid(cells: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(txInsetFill())
                            .padding(12.dp),
                    ) {
                        Text(label, color = BwColors.InkSecondary, fontFamily = BwFontFamily, fontSize = 12.sp)
                        Text(value, color = BwColors.Ink, fontFamily = BwFontFamily, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun DimmedHash(
    hash: String,
    leadingZeros: Int,
) {
    val muted = BwColors.InkMuted
    val ink = BwColors.Ink
    val quiet = BwColors.InkQuiet
    val text =
        buildAnnotatedString {
            hash.forEachIndexed { index, char ->
                if (index > 0 && index % 4 == 0) append(' ')
                val color =
                    when {
                        index < leadingZeros -> muted
                        (index / 4) % 2 == 0 -> ink
                        else -> quiet
                    }
                withStyle(SpanStyle(color = color)) { append(char) }
            }
        }
    Text(text, fontFamily = BwFontFamily, fontSize = 14.sp, modifier = Modifier.semantics { contentDescription = hash })
    Text("The leading zeros are the proof of work.", color = BwColors.InkSecondary, fontFamily = BwFontFamily, fontSize = 12.sp)
}

@Composable
internal fun TxExplorerRow(onOpen: () -> Unit) {
    Pressable(onClick = onOpen, modifier = Modifier.fillMaxWidth(), role = Role.Button) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 66.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(txListCard())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Look up on a block explorer",
                    color = BwColors.Ink,
                    fontFamily = BwFontFamily,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.45).sp,
                )
                Text("Tells a third party you care about it", color = BwColors.InkSecondary, fontFamily = BwFontFamily, fontSize = 13.sp)
            }
            Chevron()
        }
    }
}

@Composable
internal fun TxExplorerSheet(
    txid: String,
    onDismiss: () -> Unit,
) {
    PlatformModalSheet(
        onDismiss = onDismiss,
        background = SheetPalette.sheet,
        dark = LocalDarkPalette.current,
    ) { close ->
        ExplorerBody(txid, close)
    }
}

@Composable
private fun ExplorerBody(
    txid: String,
    close: SheetClose,
) {
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        PlatformSheetHeader(title = "Block explorer", onClose = { close {} }, action = null)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Explorers see what you look up",
                color = SheetPalette.text,
                fontFamily = BwFontFamily,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.45).sp,
            )
            Text(
                "Whoever runs the explorer learns that someone at your IP address is interested in this transaction. That can tie you to it.",
                color = SheetPalette.secondary,
                fontFamily = BwFontFamily,
                fontSize = 15.sp,
                lineHeight = 20.sp,
            )
            ExplorerNote()
            TintedPillButton(
                text = "Copy transaction ID instead",
                onClick = { close { clipboard.setText(AnnotatedString(txid)) } },
                modifier = Modifier.fillMaxWidth(),
            )
            ExplorerOpenButton(onClick = { close { uri.openUri("https://mempool.space/tx/$txid") } })
        }
    }
}

@Composable
private fun ExplorerNote() {
    SheetCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Everything on this screen came from the block itself",
                color = SheetPalette.text,
                fontFamily = BwFontFamily,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.45).sp,
            )
            Text(
                "You usually don't need an explorer to check it.",
                color = SheetPalette.secondary,
                fontFamily = BwFontFamily,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun ExplorerOpenButton(onClick: () -> Unit) {
    Pressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(ProminentButtonHeight),
        role = Role.Button,
    ) {
        Box(
            Modifier.fillMaxSize().background(SheetPalette.amberFill, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Open normally",
                color = SheetPalette.amber,
                fontFamily = BwFontFamily,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.45).sp,
            )
        }
    }
}
