package io.bluewallet.blueberry

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.FiltersProgressPayload
import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.parse.ReceiveSnapshot
import io.bluewallet.blueberry.parse.snapshotReceive
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.OverlayBackEffects
import io.bluewallet.blueberry.ui.ScannableQr
import io.bluewallet.blueberry.ui.ScreenHeader
import io.bluewallet.blueberry.wallet.HD_SCRIPT_TYPES
import io.bluewallet.blueberry.wallet.WalletSecretKind
import io.bluewallet.blueberry.wallet.compactFilterFrom
import io.bluewallet.blueberry.wallet.createWallet
import io.bluewallet.blueberry.wallet.loadReceiveScriptType
import io.bluewallet.blueberry.wallet.loadWalletSecret
import io.bluewallet.blueberry.wallet.parseWalletSecret
import io.bluewallet.blueberry.wallet.receiveLabel
import io.bluewallet.blueberry.wallet.saveReceiveScriptType
import kotlin.math.max

/** Placeholder drawn while the label sheet is up; deliberately not a bitcoin address. */
private const val PLACEHOLDER_QR_DATA = "blueberry"
private const val PLACEHOLDER_ADDRESS = "•••• •••• •••• •••• ••••"
internal const val PLACEHOLDER_ALPHA = 0.15f

fun currentReceiveAddress(
    runtime: PeersRuntime,
    db: Database,
): String? = currentReceive(runtime, db)?.address

private fun currentReceive(
    runtime: PeersRuntime,
    db: Database,
): ReceiveSnapshot? {
    val wallet = runtime.wallet ?: runCatching { createWallet(db) }.getOrNull() ?: return null
    val before = wallet.gaps()
    val snapshot = snapshotReceive(db, wallet)
    val after = wallet.gaps()
    if (after != before) {
        val downloaded = db.filters.count()
        val filterFrom = compactFilterFrom(db)
        val tip = db.headers.tip()
        val total =
            if (tip != null && filterFrom != null) {
                max(0, tip.height - filterFrom + 1)
            } else {
                downloaded
            }
        runtime.bus.emit(
            Event.FiltersProgress,
            FiltersProgressPayload(nowMillis(), minOf(downloaded, total), total),
        )
    }
    return snapshot
}

fun showsAddressLabelSheet(
    address: String?,
    label: String?,
    editing: Boolean,
): Boolean = address != null && (label.isNullOrBlank() || editing)

fun canSaveAddressLabel(draft: String): Boolean = draft.isNotBlank()

fun showsReceiveTypePicker(db: Database): Boolean =
    runCatching { parseWalletSecret(loadWalletSecret(db)).kind == WalletSecretKind.MNEMONIC }
        .getOrDefault(false)

@Composable
fun ReceiveScreen(
    runtime: PeersRuntime,
    db: Database,
    onBack: () -> Unit,
) {
    // Pinned: computed on open and on type switch only, so an incoming payment never swaps it mid-view.
    var snapshot by remember(runtime, db) { mutableStateOf(currentReceive(runtime, db)) }
    var receiveType by remember(db) { mutableStateOf(loadReceiveScriptType(db)) }
    val chain = snapshot?.chain
    var index by remember(chain) { mutableStateOf(chain?.start ?: 0) }
    val address = chain?.addresses?.getOrNull(index)?.address ?: snapshot?.address
    val nav = receiveNav(chain, index) { index = it }
    var label by remember(address) { mutableStateOf(address?.let { db.addressLabels.get(it) }) }
    var editing by remember(address) { mutableStateOf(false) }
    var draft by remember(address) { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sheetShown = showsAddressLabelSheet(address, label, editing)
    val keyFocus = remember { FocusRequester() }
    // Re-take key focus whenever the sheet comes or goes, so desktop arrow keys keep stepping.
    LaunchedEffect(sheetShown) { runCatching { keyFocus.requestFocus() } }
    OverlayBackEffects(enabled = editing, onDismiss = { editing = false }) {
        // Sheet sits below the content (not over it), so the placeholder centres in the space left above it.
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(BwColors.Paper)
                    .receiveArrowKeys(nav, keyFocus),
        ) {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .padding(horizontal = BwSpace.ScreenX, vertical = BwSpace.ScreenY),
                verticalArrangement = Arrangement.spacedBy(BwSpace.Gap),
            ) {
                ScreenHeader(title = "Receive", onBack = onBack)
                if (showsReceiveTypePicker(db)) {
                    Box {
                        Text(
                            text = receiveType.receiveLabel(),
                            color = BwColors.Link,
                            fontFamily = BwFontFamily,
                            fontSize = BwType.BodySize,
                            fontWeight = BwType.Body,
                            modifier = Modifier.clickable { menuOpen = true },
                        )
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            for (type in HD_SCRIPT_TYPES) {
                                DropdownMenuItem(
                                    text = { Text(type.receiveLabel()) },
                                    onClick = {
                                        saveReceiveScriptType(db, type)
                                        receiveType = type
                                        menuOpen = false
                                        snapshot = currentReceive(runtime, db)
                                    },
                                )
                            }
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(BwSpace.Gap, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val shown = address
                    when {
                        shown == null -> NoUnusedAddress()
                        // Cover: while the sheet is up the real QR and address are never composed.
                        sheetShown -> ReceiveAddressPlaceholder(nav)
                        else ->
                            ReceiveAddress(
                                address = shown,
                                label = label.orEmpty(),
                                nav = nav,
                                onEditLabel = {
                                    draft = label.orEmpty()
                                    editing = true
                                },
                            )
                    }
                }
            }
            AnimatedVisibility(
                visible = sheetShown,
                modifier = Modifier.fillMaxWidth(),
                enter =
                    slideInVertically(
                        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                        initialOffsetY = { it },
                    ) + fadeIn(animationSpec = tween(200)),
                exit = ExitTransition.None,
            ) {
                ReceiveLabelSheet(
                    draft = draft,
                    onDraft = { draft = it },
                    dismissText = if (editing) "Cancel" else "Back to home",
                    onSave = {
                        val target = address ?: return@ReceiveLabelSheet
                        setAddressLabel(db, runtime.wallet, runtime.walletTxsStore, target, draft)
                        uploadLabels(scope, db)
                        label = db.addressLabels.get(target)
                        editing = false
                    },
                    onDismiss = { if (editing) editing = false else onBack() },
                )
            }
            // The sheet pads itself for the nav bar and keyboard; otherwise keep the bottom safe inset.
            if (!sheetShown) Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
        }
    }
}

@Composable
private fun ReceiveAddress(
    address: String,
    label: String,
    nav: ReceiveNav?,
    onEditLabel: () -> Unit,
) {
    var copied by remember(address) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    ReceiveQrRow(nav, hidden = false) {
        ScannableQr(
            data = address,
            contentDescription = "Receive address QR code",
            modifier = Modifier.size(200.dp),
        )
    }
    Text(
        text = label,
        color = BwColors.Link,
        fontFamily = BwFontFamily,
        fontSize = BwType.BodySize,
        fontWeight = BwType.Value,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEditLabel),
    )
    if (nav?.used == true) {
        Text(
            text = "used",
            color = BwColors.Danger,
            fontFamily = BwFontFamily,
            fontSize = BwType.CaptionSize,
            fontWeight = BwType.Caption,
        )
    }
    Text(
        text = address,
        color = BwColors.Ink,
        fontFamily = BwFontFamily,
        fontSize = BwType.BodySize,
        fontWeight = BwType.Body,
        textAlign = TextAlign.Center,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable {
                    clipboard.setText(AnnotatedString(address))
                    copied = true
                },
    )
    if (copied) {
        Text(
            text = "Copied",
            color = BwColors.Success,
            fontFamily = BwFontFamily,
            fontSize = BwType.CaptionSize,
            fontWeight = BwType.Caption,
        )
    }
}

@Composable
private fun ColumnScope.ReceiveAddressPlaceholder(nav: ReceiveNav?) {
    // Shrinks below 200dp when little room is left (small screen, keyboard open).
    ReceiveQrRow(nav, hidden = true, Modifier.weight(1f, fill = false)) {
        ScannableQr(
            data = PLACEHOLDER_QR_DATA,
            contentDescription = "Receive address QR code placeholder",
            modifier =
                Modifier
                    .sizeIn(maxWidth = 200.dp, maxHeight = 200.dp)
                    .aspectRatio(1f)
                    .alpha(PLACEHOLDER_ALPHA),
        )
    }
    Text(
        text = PLACEHOLDER_ADDRESS,
        color = BwColors.Ink,
        fontFamily = BwFontFamily,
        fontSize = BwType.BodySize,
        fontWeight = BwType.Body,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().alpha(PLACEHOLDER_ALPHA),
    )
}

@Composable
private fun NoUnusedAddress() {
    Text(
        text = "No unused address",
        color = BwColors.InkMuted,
        fontFamily = BwFontFamily,
        fontSize = BwType.BodySize,
        fontWeight = BwType.Body,
    )
}
