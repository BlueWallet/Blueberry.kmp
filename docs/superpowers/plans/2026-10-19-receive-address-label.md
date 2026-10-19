# Receive address label Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Receive makes the user label an unlabelled address before showing it, then shows the label under the QR. Tapping the label edits it.

**Architecture:** `ReceiveScreen` pins the address (no `walletTxsStore` subscription) and keeps `label` / `editing` / `draft` keyed on the address. Two pure helpers decide sheet visibility and Save enablement. A new `ReceiveLabelSheet` composable is the bottom sheet. While the sheet is up the QR and address are not drawn.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform Material3, `:shared`, `:storage` (`db.addressLabels`, `setAddressLabel` from `9f8aa3e`).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-10-19-receive-address-label-design.md`
- Package `io.bluewallet.blueberry`. No new theme tokens. Reuse `PillButton`, `TextAction`, `OverlayBackEffects`, `BwColors`, `BwType`, `BwSpace`
- Do not call `uploadLabels`. No label-sync changes
- No Compose UI test infrastructure
- Env: `JAVA_HOME=/home/box/jdk/current ANDROID_HOME=/home/box/android-sdk`
- Pass `./gradlew ktlintCheck detekt :storage:jvmTest :shared:jvmTest :androidApp:assembleDebug :desktopApp:compileKotlin` before the last commit
- Commit as `git -c user.name=Overtorment -c user.email=overtorment@gmail.com commit`. Do not push
- Linux host: no iOS compile

## File structure

```
shared/src/commonMain/kotlin/io/bluewallet/blueberry/ReceiveScreen.kt      (modify: helpers, pinning, sheet wiring, label row)
shared/src/commonMain/kotlin/io/bluewallet/blueberry/ReceiveLabelSheet.kt  (create: bottom sheet)
shared/src/commonTest/kotlin/io/bluewallet/blueberry/ReceiveScreenTest.kt  (modify: helper tests)
```

---

### Task 1: Pure helpers

**Files:**
- Modify: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/ReceiveScreen.kt` (above `showsReceiveTypePicker`)
- Test: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/ReceiveScreenTest.kt`

**Interfaces:**
- Produces: `fun showsAddressLabelSheet(address: String?, label: String?, editing: Boolean): Boolean` and `fun canSaveAddressLabel(draft: String): Boolean`

- [ ] **Step 1: Write the failing tests** (append to `ReceiveScreenTest`)

```kotlin
    @Test
    fun label_sheet_shows_for_unlabelled_or_edited_address_only() {
        assertTrue(showsAddressLabelSheet(address = "bc1qa", label = null, editing = false))
        assertFalse(showsAddressLabelSheet(address = "bc1qa", label = "rent", editing = false))
        assertTrue(showsAddressLabelSheet(address = "bc1qa", label = "rent", editing = true))
        assertFalse(showsAddressLabelSheet(address = null, label = null, editing = false))
    }

    @Test
    fun label_save_needs_non_blank_draft() {
        assertFalse(canSaveAddressLabel(""))
        assertFalse(canSaveAddressLabel("  \t "))
        assertTrue(canSaveAddressLabel(" rent "))
    }
```

- [ ] **Step 2: Run, expect FAIL** (`Unresolved reference 'showsAddressLabelSheet'`)

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.ReceiveScreenTest`

- [ ] **Step 3: Implement**

```kotlin
fun showsAddressLabelSheet(
    address: String?,
    label: String?,
    editing: Boolean,
): Boolean = address != null && (label == null || editing)

fun canSaveAddressLabel(draft: String): Boolean = draft.isNotBlank()
```

- [ ] **Step 4: Run, expect PASS** (same command)

- [ ] **Step 5: Commit** `Add Receive address label helpers`

### Task 2: Sheet, cover, label row, pinning

**Files:**
- Create: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/ReceiveLabelSheet.kt`
- Modify: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/ReceiveScreen.kt` (`ReceiveScreen` body)

**Interfaces:**
- Consumes: Task 1 helpers. `db.addressLabels.get(address): String?` and `setAddressLabel(db, address, label)`. `internal fun OverlayBackEffects(enabled: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit)`
- Produces: `internal fun ReceiveLabelSheet(draft: String, onDraft: (String) -> Unit, dismissText: String, onSave: () -> Unit, onDismiss: () -> Unit)`

Behaviour notes:
- Cover = the real QR and address are not composed while the sheet is up. `ReceiveAddressPlaceholder()` takes their place: `ScannableQr("blueberry")` at 200dp and `•••• •••• •••• •••• ••••`, both `alpha(0.15f)`, not clickable, no label row. (Follow-up after the visual check.)
- `exit = ExitTransition.None`: the sheet vanishes at the same moment the QR appears, and its button text never flips mid-animation.
- A type switch changes `address`, which resets `label` / `editing` / `draft` through `remember(address)`.
- Edit mode: `OverlayBackEffects(enabled = editing)` makes system back, Esc and the iOS edge swipe close the sheet. In prompt mode the outer `ScreenPushOverlay` goes home.
- After Save, `label` is re-read from the DB, so it shows exactly what was stored (trimmed).

- [ ] **Step 1: Create `ReceiveLabelSheet.kt`**

```kotlin
package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.PillButton
import io.bluewallet.blueberry.ui.TextAction

/** Bottom sheet asking for a receive address label. Save stays disabled while the draft is blank. */
@Composable
internal fun ReceiveLabelSheet(
    draft: String,
    onDraft: (String) -> Unit,
    dismissText: String,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(BwColors.Card, shape)
                .navigationBarsPadding()
                .imePadding()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Label",
            color = BwColors.InkSecondary,
            fontFamily = BwFontFamily,
            fontSize = BwType.CaptionSize,
            fontWeight = BwType.Caption,
        )
        OutlinedTextField(
            value = draft,
            onValueChange = onDraft,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Savings") },
        )
        PillButton(
            text = "Save",
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            enabled = canSaveAddressLabel(draft),
        )
        TextAction(
            text = dismissText,
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}
```

- [ ] **Step 2: Replace `ReceiveScreen` and add its private parts.** Imports: add `AnimatedVisibility`, `ExitTransition`, `FastOutSlowInEasing`, `tween`, `fadeIn`, `slideInVertically`, `io.bluewallet.blueberry.ui.OverlayBackEffects`. Drop `DisposableEffect`, `rememberCoroutineScope`, `kotlinx.coroutines.launch`.

```kotlin
@Composable
fun ReceiveScreen(
    runtime: PeersRuntime,
    db: Database,
    onBack: () -> Unit,
) {
    // Pinned: computed on open and on type switch only, so an incoming payment never swaps it mid-view.
    var address by remember(runtime, db) { mutableStateOf(currentReceiveAddress(runtime, db)) }
    var label by remember(address) { mutableStateOf(address?.let { db.addressLabels.get(it) }) }
    var editing by remember(address) { mutableStateOf(false) }
    var draft by remember(address) { mutableStateOf("") }
    var receiveType by remember(db) { mutableStateOf(loadReceiveScriptType(db)) }
    var menuOpen by remember { mutableStateOf(false) }
    val sheetShown = showsAddressLabelSheet(address, label, editing)
    OverlayBackEffects(enabled = editing, onDismiss = { editing = false }) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(BwColors.Paper)
                        .safeDrawingPadding()
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
                                        address = currentReceiveAddress(runtime, db)
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
                        // Cover: while the sheet is up the QR and address are not drawn at all.
                        sheetShown -> Unit
                        else ->
                            ReceiveAddress(
                                address = shown,
                                label = label.orEmpty(),
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
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
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
                        setAddressLabel(db, target, draft)
                        label = db.addressLabels.get(target)
                        editing = false
                    },
                    onDismiss = { if (editing) editing = false else onBack() },
                )
            }
        }
    }
}

@Composable
private fun ReceiveAddress(
    address: String,
    label: String,
    onEditLabel: () -> Unit,
) {
    var copied by remember(address) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    ScannableQr(
        data = address,
        contentDescription = "Receive address QR code",
        modifier = Modifier.size(200.dp),
    )
    Text(
        text = label,
        color = BwColors.Link,
        fontFamily = BwFontFamily,
        fontSize = BwType.BodySize,
        fontWeight = BwType.Value,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEditLabel),
    )
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
private fun NoUnusedAddress() {
    Text(
        text = "No unused address",
        color = BwColors.InkMuted,
        fontFamily = BwFontFamily,
        fontSize = BwType.BodySize,
        fontWeight = BwType.Body,
    )
}
```

- [ ] **Step 3: Verify**

Run: `./gradlew ktlintCheck detekt :storage:jvmTest :shared:jvmTest :androidApp:assembleDebug :desktopApp:compileKotlin`
Expected: BUILD SUCCESSFUL, 0 test failures

- [ ] **Step 4: Commit** `Prompt for a label on the Receive address`
