# Receive address label

Date: 2026-10-19  
Status: approved (conversation)

## Goal

Every receive address gets a label before the user can see or share it. Receive asks for a label when the shown address has none. It then shows the label under the QR, and the user can tap the label to change it.

## Context

The `address_labels` table and `setAddressLabel(db, address, label)` exist (commit `9f8aa3e`). `setAddressLabel` trims the label and deletes the row when the label is empty. It does not use `editLabels`: address labels are not synced, and a dirty flag with no upload would make the next cold start push stale labels over the remote ones.

## Non-goals

- Address labels in the encrypted BIP-329 label sync.
- Address labels anywhere other than Receive (tx list, coins, send).
- Deleting a label from the UI.
- Compose UI test infrastructure.

## Decisions

| Topic | Choice |
| --- | --- |
| Shown address | Pinned. Computed on open and on each script-type switch. `walletTxsStore` updates do not change it while the screen is open. Remove the subscription. |
| Label lookup | `db.addressLabels.get(address)`, `remember(address)`. |
| No label | The sheet opens. An opaque cover hides the QR and the address. |
| Cover | Shown whenever the sheet is open, in both prompt and edit mode. The real QR and address are never composed. In their place goes a placeholder at 15% alpha, not clickable: `ScannableQr` (200dp) of the fixed non-address `"blueberry"`, then `•••• •••• •••• •••• ••••` in the address text style. No label row. The sheet sits below the content instead of over it, so the placeholder is centred in the space above the sheet. The placeholder QR shrinks below 200dp when space is short, e.g. with the keyboard open. |
| Above the cover | `ScreenHeader` and the script-type picker stay visible and usable. |
| Sheet | `ReceiveLabelSheet`, modelled on `CoinsUtxoSheet`: Card colour, 28dp rounded top, `imePadding` + `navigationBarsPadding`, `AnimatedVisibility` slide up. Content: "Label" caption, single-line `OutlinedTextField`, full-width "Save" `PillButton`, one `TextAction`. |
| Save | Enabled only while `draft.trim()` is non-empty. Calls `setAddressLabel`, which trims. Then the sheet hides. Empty labels are never saved, so the UI cannot delete a label. |
| Labelled view | QR, then the label right below it (`BwColors.Link`, tap to edit), then the address (tap to copy, unchanged). |
| Prompt mode | The sheet opens because the address has no label. The `TextAction` reads "Back to home" and calls `onBack`. |
| Edit mode | Opens when the user taps the label. The draft is prefilled with the current label. The `TextAction` reads "Cancel": it closes the sheet and keeps the old label. |
| Back, prompt mode | Header Back, system back, Esc and the iOS edge swipe go home. This is the existing `ScreenPushOverlay` behaviour; no new handler. |
| Back, edit mode | Same as Cancel: closes the sheet. Use `OverlayBackEffects(enabled = editing, onDismiss = cancel)` so Android back, desktop Esc (when `BackHandler` delivers it) and the iOS edge swipe all behave the same way. |
| Type switch | Re-pins the address and resets edit mode. If the new address has no label, the sheet stays open or reopens for it, with an empty draft. |
| Wallet kinds | All kinds that show Receive: seed, zpub, single address, WIF. A single-address wallet is prompted once. |
| No address | "No unused address" stays as it is. No cover, no sheet. |
| Label sync | Out of scope here. Superseded by `2026-10-19-address-label-sync-design.md`: saving marks labels dirty and calls `uploadLabels`. |

## Code shape

- `ReceiveScreen.kt`: pin `address` (drop the `walletTxsStore` `DisposableEffect`). Add `label`, `editing` and `draft` state keyed on `address`. Draw the cover over the QR and address column, and the label row.
- `ReceiveLabelSheet` (private composable, in `ReceiveScreen.kt` or `ReceiveLabelSheet.kt`): `draft`, `onDraft`, `dismissText`, `onSave`, `onDismiss`.
- Pure helpers (top level, `ReceiveScreen.kt`):
  - `showsAddressLabelSheet(address: String?, label: String?, editing: Boolean): Boolean` returns `address != null && (label == null || editing)`.
  - `canSaveAddressLabel(draft: String): Boolean` returns `draft.isNotBlank()`.
- Reuse `PillButton`, `TextAction`, `OverlayBackEffects`, `BwColors`, `BwType`, `BwSpace`. No new theme tokens.

## Testing

- TDD on the two helpers in `shared/src/commonTest/.../ReceiveScreenTest.kt`. Cases: unlabelled address, labelled address, labelled address in edit mode, null address. Blank, whitespace-only and non-empty drafts.
- Pinning is the removal of a subscription. It has no unit test and is covered by the visual check.
- Verify: `ktlintCheck detekt :storage:jvmTest :shared:jvmTest :androidApp:assembleDebug`.
- Visual check in the desktop app, done separately: new wallet → prompt → save → label under QR → edit → cancel → type switch → prompt again.
