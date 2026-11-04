@file:OptIn(ExperimentalLayoutApi::class)

package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluewallet.blueberry.ui.BtcAmountText
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.LocalSheetScrollEnabled
import io.bluewallet.blueberry.ui.PlatformSheetHeader

/** What the sheet can ask its host to do. */
class CoinSheetActions(
    val onClose: () -> Unit,
    val onCopy: (String) -> Unit,
    val label: CoinLabelActions,
    val onFreeze: (Boolean) -> Unit,
    val onSend: () -> Unit,
    val onOpenTx: (String) -> Unit,
)

class CoinLabelActions(
    val onSave: (String) -> Unit,
    /** The trimmed label while it differs from the saved one, else null. Saved when the sheet goes away. */
    val onDraft: (String?) -> Unit = {},
)

/** Header only, while the model loads; a short note if the coin is gone. */
@Composable
internal fun CoinSheetPending(
    actions: CoinSheetActions,
    missing: Boolean,
) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        SheetHeader(actions)
        Box(Modifier.fillMaxWidth().heightIn(min = 240.dp).padding(24.dp), contentAlignment = Alignment.TopCenter) {
            if (missing) SheetText("This coin is no longer in this wallet.", 15.sp)
        }
    }
}

/** The coin sheet body. Renders a prepared [CoinSheetModel]; all copy and layout decisions live in the model. */
@Composable
internal fun CoinSheet(
    model: CoinSheetModel,
    actions: CoinSheetActions,
    modifier: Modifier = Modifier,
) {
    var selected by remember(model.outpoint) { mutableStateOf<String?>(null) }
    val activeLink = selected?.takeIf { key -> model.links.any { it.other == key } }
    val focus = LocalFocusManager.current
    val scroll = rememberScrollState()
    val scrollEnabled = LocalSheetScrollEnabled.current
    LaunchedEffect(scroll, scrollEnabled) {
        if (!scrollEnabled) {
            snapshotFlow { scroll.value }.collect { offset ->
                if (offset != 0) scroll.scrollTo(0)
            }
        }
    }
    val dismissKeyboardOnScroll =
        remember(focus) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source == NestedScrollSource.UserInput) focus.clearFocus()
                    return Offset.Zero
                }
            }
        }
    Column(
        modifier
            .fillMaxWidth()
            .pointerInput(focus) { detectTapGestures { focus.clearFocus() } },
    ) {
        SheetHeader(actions)
        Column(
            modifier =
                Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .imePadding()
                    .nestedScroll(dismissKeyboardOnScroll)
                    .verticalScroll(scroll)
                    .navigationBarsPadding()
                    .padding(top = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SheetBody(model, actions, activeLink) { key -> selected = if (selected == key) null else key }
        }
    }
}

@Composable
private fun SheetBody(
    model: CoinSheetModel,
    actions: CoinSheetActions,
    activeLink: String?,
    onSelectLink: (String) -> Unit,
) {
    Hero(model)
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LabelField(model, actions.label)
        model.spentBanner?.let { banner -> SpentBanner(banner, model.spentTxid?.let { txid -> { actions.onOpenTx(txid) } }) }
        if (model.spentBanner == null) {
            LinkedCoinsCard(model, activeLink, onSelectLink)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { SpendCard(model.spendRows) }
        }
        ActionsCard(model, actions.onFreeze, actions.onSend)
        DetailsCard(model.address, model.origin, actions.onCopy, actions.onOpenTx)
    }
}

/** Close only. This sheet has no title. */
@Composable
private fun SheetHeader(actions: CoinSheetActions) {
    PlatformSheetHeader(title = "", onClose = actions.onClose, action = null)
}

@Composable
private fun Hero(model: CoinSheetModel) {
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val ring = if (model.frozen) Modifier.border(2.dp, SheetPalette.blue, CircleShape).padding(5.dp) else Modifier
            Box(ring.size(52.dp).background(Color(model.colorArgb), CircleShape))
            BtcAmountText(
                sats = model.valueSats,
                color = SheetPalette.text,
                trailingColor = SheetPalette.secondary,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (model.chips.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                model.chips.forEach { SheetChipView(it) }
            }
        }
    }
}

@Composable
private fun LabelField(
    model: CoinSheetModel,
    actions: CoinLabelActions,
) {
    var draft by remember(model.outpoint) { mutableStateOf(model.label) }
    var lastSaved by remember(model.outpoint) { mutableStateOf<String?>(null) }
    val dirty = draft.trim().let { it != model.label && it != lastSaved }
    SideEffect { actions.onDraft(if (dirty) draft.trim() else null) }
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    val style = TextStyle(color = SheetPalette.text, fontFamily = BwFontFamily, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SheetText("Label", 13.sp)
        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(SheetPalette.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier =
                Modifier.onFocusChanged { state ->
                    if (focused && !state.isFocused && dirty) {
                        lastSaved = draft.trim()
                        actions.onSave(draft.trim())
                    }
                    focused = state.isFocused
                },
            decorationBox = { inner ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 54.dp)
                            .background(SheetPalette.card, shape)
                            .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (draft.isEmpty()) SheetText(model.labelPlaceholder, 16.sp)
                        inner()
                    }
                }
            },
        )
    }
}
