# Receive address navigation

Date: 2026-10-20  
Status: approved (conversation)

## Goal

Receive gets left and right arrows beside the QR code. They step through the external (receive) addresses of the current script type. Each address shows its own label. A used address is marked with red "used" text. An address with no label opens the existing label prompt.

## Context

- Receive opens at `currentReceiveAddress` → `snapshotReceiveAddress` (`parse/UsedIndexes.kt:121-169`): the lowest unused external index. The address is pinned with `remember(runtime, db)` (`ReceiveScreen.kt:117`). The label, editing and draft state are keyed on the address (`:118-120`). The script picker re-pins the address (`:156-159`).
- Addresses are derived eagerly into a watch window: indices `0 until gaps.external` per script type (`wallet/Derive.kt:101-127`).
  - The window starts at 40 and grows by `GAP_LIMIT` (20) when a used index falls in its last 20 (`WatchGaps.kt:41-61`, `ParseBlocks.kt:145-172`).
  - Once growth settles, the window is at least highest used + 21. Indices outside it are not watched.
- `usedWatchIndexesFromTxs` (`UsedIndexes.kt:88-109`) marks an index used if any confirmed stored tx pays to it or spends from it.
- The label sheet shows when the label is blank or the user is editing (`showsAddressLabelSheet`, `ReceiveScreen.kt:98-102`). While it is up, a faded placeholder replaces the QR and address.
- Canvas arrows exist: `SheetBackArrow` (`CoinsUtxoSheet.kt:194-215`, a 36dp clickable "<").

## Non-goals

- Swipe gestures.
- Change (internal) addresses.
- Growing the watch window or queueing rescans from navigation.
- Remembering the position across visits.
- Live refresh of the shown address or its used flag while Receive is open.
- WIF and single-address wallets (no arrows).
- Showing or editing labels of change addresses, or any history view.

## Decisions

| Topic | Choice |
| --- | --- |
| Start | Receive opens at the first unused external address, as today (`snapshotReceiveAddress`). The arrows move from there. |
| Left | Previous external index for the current script type. Disabled at index 0. |
| Right | Next external index, up to `lastReceiveIndex`, then disabled. |
| Upper bound | `lastReceiveIndex = min((highestUsed ?: -1) + GAP_LIMIT, windowSize - 1)`. `highestUsed` is the highest used external index for the type; `windowSize` is the number of derived external addresses for the type. |
| Why that bound | The addresses after the last used one form at most `GAP_LIMIT` consecutive unused addresses. A BIP44 restore stops only after `GAP_LIMIT` consecutive unused addresses, so any payment within the bound is found on restore. Once growth settles the window is at least highest used + 21, so the bound sits inside it and payments are detected without growth or rescans. The `windowSize - 1` cap covers the moment mid-sync before the window has grown. The first unused index is at most highest used + 1, so it is always in range. |
| Index source | Indices are walked inside `wallet.snapshot().addresses`, filtered to `!change` and the current `resolvedScriptType()`, and sorted by index. The opening address's index is found by matching the address string. Nothing new is derived. |
| State | Receive holds `index`. `address` is derived from it. The label, editing and draft state stay keyed on `address`, so stepping resets edit mode and the draft automatically. |
| Prompt | Every unlabelled address (blank or missing label) shows the existing label sheet and the faded placeholder, used or not. The arrows, the path caption and the script picker stay usable while the prompt is up. "Back to home" is unchanged and leaves Receive. Save labels the address being shown. |
| Used | The used set is computed once per open and per script switch from `db.transactions` via `usedWatchIndexes`. It is not live: a payment arriving while Receive is open shows on the next open or switch. This matches the pinned address. |
| Used marker | When the address is labelled and used, red text `used` (`BwColors.Danger`, caption style) appears under the label. The QR and address still show. While the prompt is up, the placeholder hides everything as today, so `used` shows only after labelling. |
| Path caption | A small muted caption with the full derivation path of the shown address (for example `m/84'/0'/0'/0/3`, apostrophe as the hardened marker) under the QR row. While the address is covered (label prompt up, placeholder QR showing), the caption shows a faded dotted placeholder `•••• •••• ••••` in the same style as the dotted address placeholder (Ink at `PLACEHOLDER_ALPHA`, caption size, one line, so the row height stays the same); the real path shows only once the address is labelled. *(Changed 2026-10-20: reverses the earlier decision that the path also shows under the placeholder.)* It comes from `WatchAddress.path`, which the wallet builds as `<script type account path>/<chain>/<index>`, so the purpose follows the script type: 44 (Legacy), 49 (Nested SegWit), 84 (Native SegWit), 86 (Taproot). zpub imports are watched as BIP84 account 0 (`m/84'/0'/0'`), the account the wallet already assumes; the zpub itself doesn't record its origin path, so this is the wallet's assumption, not something read from the key. There are no custom account paths today. The caption is one line and ellipsizes if it can't fit. *(Changed 2026-10-20: replaces the earlier `#<index>` caption.)* |
| Arrows | A shared Canvas arrow (`ui/NavArrow.kt`) taking a direction and `enabled`. `SheetBackArrow` becomes its left-pointing use. Arrows sit left and right of the QR in a Row. A disabled arrow is drawn muted and ignores taps. |
| Keys | On desktop, the Left and Right arrow keys step the same way, disabled at the same bounds. They are ignored while the label text field has focus, so the cursor still moves. |
| Script switch | Resets to the new type's first unused address and recomputes the used set and bound. |
| Wallet kinds | Arrows and the caption appear only for HD wallets (mnemonic or zpub, `WatchWalletKind.BIP84`). WIF and single-address wallets keep today's screen. |
| Pinning | The shown address never changes because txs arrive. It changes only through the arrows or the script picker. |

## Helpers (pure, unit-tested)

- `lastReceiveIndex(usedExternal: List<Int>, windowSize: Int): Int`, per the bound above.
- `receiveIndexes(wallet: WatchWallet, type): List<WatchAddress>` (external, by index), or an inline equivalent if trivial.
- `canStepReceive(index, delta, lastIndex): Boolean`: false below 0 or above `lastIndex`.
- The used flag is `index in usedExternal`.
- The path caption is the shown entry's `WatchAddress.path`. `ReceiveChain.addresses` holds `WatchAddress`es rather than bare strings, so no separate helper is needed.

## Testing (TDD)

- **`lastReceiveIndex`:**
  - no used addresses → `GAP_LIMIT - 1`;
  - used `[0, 1, 5]` → `5 + GAP_LIMIT`;
  - the window cap applies when the window is smaller;
  - a hole below the highest used index doesn't change the bound.
- **`canStepReceive`:** left is disabled at 0, right is disabled at `lastIndex`, and both are enabled in between.
- **Used flag:** with a stored tx paying index 2 and another spending from index 1, `usedWatchIndexes` gives `[1, 2]`. The helper marks 1 and 2 used and 0 and 3 unused.
- **Path caption placeholder:** `receivePathCaption(path, hidden)` returns the path when shown and `PLACEHOLDER_PATH` (no letters or digits) when hidden.
- **Path caption:** a mnemonic chain at index 5 gives `m/44'/0'/0'/0/5`, `m/49'/…`, `m/84'/…` and `m/86'/…` for each script type; a zpub chain gives `m/84'/0'/0'/0/n`.
- **Label sheet with navigation:** `showsAddressLabelSheet` already covers blank and missing labels. Stepping to a labelled address shows no sheet, and stepping to an unlabelled one shows it. Covered through the helpers; the composable is checked visually.
- **Visual (desktop and Limrun Android):**
  - arrows at both bounds;
  - prompt on step;
  - `used` marker;
  - path caption placeholder while the prompt is up, the real path once labelled, and its purpose changing on a script switch;
  - script switch reset;
  - keyboard open with arrows still reachable.
