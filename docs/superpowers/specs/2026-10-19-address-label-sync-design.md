# Address label sync

Date: 2026-10-19  
Status: approved (conversation)

## Goal

Receive address labels sync through the existing encrypted label backup, the same way tx notes and UTXO names do.

## Context

- The `address_labels` table, `setAddressLabel` and the Receive label prompt exist on `feature/address-labels`.
- `647b77e` kept `setAddressLabel` out of `editLabels` because address labels were not synced. This spec reverses that.
- Sync today (`PublishWalletLabels.kt`, `labels/WalletLabels.kt`) works like this:
  - It covers seed-phrase wallets only.
  - It uploads one BIP-329 JSONL file per seed, sealed with SLIP-0015, as a full snapshot right after each edit.
  - On cold start, if local labels are clean and the remote sequence is ahead, it replaces all local labels with the remote file.

## Non-goals

- A separate file, merging, or tombstones.
- BIP-329 `origin` / `spendable` fields.
- Sync for watch-only (zpub, single address) or WIF wallets.
- Showing address labels anywhere other than Receive.
- Live refresh of an already open Receive screen after a restore.

## Decisions

| Topic | Choice |
| --- | --- |
| File | The same labels file as `tx` and `output` records. No new key, sequence number or dirty flag. |
| Record | `{"type":"addr","ref":"<address>","label":"<label>"}`. No `origin` or `spendable`. |
| Order | Existing groups first (`output`, then `tx`, each sorted by ref), then `addr` sorted by address. |
| Which rows | Every `address_labels` row. No ownership filter: only Receive writes the table. |
| Model | `WalletLabels` gains `addressLabels: List<AddressLabel>` with `data class AddressLabel(address, label)`. Decode collects `addr` lines into it. When the same ref appears twice, the last line wins, as for the other types. |
| Edit | `setAddressLabel` runs inside `editLabels` again. It sets `labels_dirty`, bumps the change counter, trims, and deletes the row when the label is empty. |
| Upload | Receive Save calls `uploadLabels(scope, db)` right after `setAddressLabel`, like tx notes and UTXO names. The upload snapshot includes `db.addressLabels.list()`. |
| Restore | Full replace, now including `address_labels`: delete all three tables and insert the remote rows, in the same `db.transaction` and under the same clean-and-unchanged guard. Deletions propagate by absence. The last uploaded snapshot wins. |
| Valid label file | `storedWalletLabels` accepts a blank document or any document with at least one known record (`output`, `tx` or `addr`). An `addr`-only file is valid. A non-blank file with no known records still returns `null` and does not wipe anything. |
| Unknown types | Still skipped line by line. |
| Old builds | Accepted risk: a build without `addr` support drops `addr` lines on read and wipes them from the server on its next upload. Label sync has not shipped to real users. |
| Cached tx list | Not affected. `onRestored` keeps refreshing the wallet tx snapshot, which does not read address labels, so nothing extra is needed. |
| Receive during restore | Receive reads the label when it opens and when the address changes (`remember(address)`). A restore that lands while Receive is open shows on the next open, not live. Restore starts at app launch, so this window is short. If the user saves a label while a download is in flight, the edit marks labels dirty and bumps the counter, so the restore skips the replace and the local edit is uploaded (existing guard). |

## Code shape

- `labels/.../WalletLabels.kt`:
  - `AddressLabel` and the `addressLabels` field.
  - `addr` in encode and decode.
  - `addr` in the known types, and in the `hasRow` check of `storedWalletLabels`.
- `shared/.../PublishWalletLabels.kt`: the upload snapshot adds address labels. The restore transaction clears and inserts `db.addressLabels`.
- `shared/.../AddressLabelsActions.kt`: wrap the write in `editLabels`, and update the KDoc.
- `shared/.../ReceiveScreen.kt`: in `onSave`, call `uploadLabels(scope, db)` with `rememberCoroutineScope()`.
- Callers that build `WalletLabels`, including tests, pass `addressLabels`. Give it a default of `emptyList()` to keep those changes small.

## Testing

TDD. Each test fails first, then passes.

- `WalletLabelsTest`:
  - `addr` rows encode as `{"type":"addr","ref","label"}` after `output` and `tx`, sorted.
  - `addr` lines decode into `addressLabels`.
  - An `addr`-only document is a valid stored label set.
  - The existing tests stay green. A non-blank file with no known rows is still rejected.
- `PublishWalletLabelsTest`: the upload contains the `addr` row for a stored address label.
- `RestoreWalletLabelsTest`:
  - Remote ahead replaces local address labels: a local-only one is deleted and the remote one is inserted.
  - The existing `non_bip329_blob_does_not_wipe_local_labels` test also asserts that local address labels survive.
- `AddressLabelsActionsTest`: `setAddressLabel` sets `labels_dirty` to `"1"`. This replaces the current "stays clean" assertion.
- Verify: `ktlintCheck detekt :labels:jvmTest :storage:jvmTest :shared:jvmTest :androidApp:assembleDebug :desktopApp:compileKotlin`.
