# Tx list address label fallback

Date: 2026-10-19  
Status: approved (conversation)

## Goal

When a tx has no note and none of its outputs has a UTXO name, the tx list shows the label the user gave (on Receive) to one of our addresses that the tx pays.

## Context

- The tx list secondary line is `txListSecondary(shortTxid, note, utxoLabel)` (`TxListCaption.kt:3-10`). It shows the note, else `utxoLabel`, else the short txid in a muted caption (`txListSecondaryMuted`, `:12-15`). It is rendered in `HomeTxBubble.kt:45,71`.
- `utxoLabel` is the UTXO name of the lowest named vout of the tx. It comes from `firstUtxoLabelByTxid` (`parse/PaymentLabelResolve.kt:3-15`) and is read from `utxo_names`, so it survives spends.
- Rows are built in `snapshotFromDb` (`WalletTxsStore.kt:271-377`):
  - Confirmed rows use `db.transactions.list()`, which holds the raw tx bytes.
  - Pending sends use `txHex` from `db.sends` / `db.privateSends`.
  - It can run without a wallet (`PeersRuntime.kt:118`).
- `address_labels` holds only our own addresses, because Receive is its only writer.
- `addressFromOutputScript` (`wallet/IsAddressValid.kt:109`) maps an output script to an address, or returns `null`.
- Tx note and UTXO name saves rebuild the snapshot (`TxNotesActions.kt:23-24`, `UtxoNamesActions.kt:23-24`). Restore rebuilds too (`App.kt:131-134`). Receive Save does not rebuild today (`ReceiveScreen.kt:204-205`).

## Non-goals

- Tx details screen. The Note field still shows only the tx note.
- Coins screen titles.
- A different visual style for the address label fallback.
- Filtering by direction or excluding change outputs.

## Decisions

| Topic | Choice |
| --- | --- |
| Scope | Any tx (confirmed or pending send) with an output whose address has a non-blank label in `address_labels`. Includes outgoing txs and self-transfers. No netDelta filter. |
| Precedence | Tx note, then UTXO name (existing `firstUtxoLabelByTxid`), then address label, then muted short txid (unchanged). |
| Several labelled outputs | The lowest vout wins, mirroring `firstUtxoLabelByTxid`. |
| Style | Same as a note or UTXO name: not muted. |
| Source | Decode outputs from the stored tx bytes, in vout order, with `addressFromOutputScript`, and look each address up in `address_labels`. No wallet needed, so it works in the wallet-less first snapshot. Spent outputs count. |
| Helper | `firstAddressLabel(tx: ByteArray, labelByAddress: Map<String, String>): String?` in `parse/PaymentLabelResolve.kt`, next to `firstUtxoLabelByTxid`. It trims labels and skips blank ones. Unreadable tx bytes or a script with no address mean no match (`null`), never a crash. |
| Row model | `WalletTxRow` gains `addressLabel: String? = null`. `txListSecondary` / `txListSecondaryMuted` gain a trailing `addressLabel: String? = null` used after `utxoLabel`. Existing callers keep compiling. |
| Snapshot | `snapshotFromDb` reads `db.addressLabels.list()` once into a map. It skips the decode entirely when the map is empty. It fills `addressLabel` for confirmed rows (from `StoredTx.tx`) and pending rows (from `txHex`, decoded from hex; bad hex gives `null`). |
| Receive Save | Mirror `setTxNote`: `setAddressLabel(db, wallet, walletTxsStore, address, label)` rebuilds the snapshot with `snapshotFromDb(db, at, at, wallet)` after the write. Receive passes `runtime.wallet` and `runtime.walletTxsStore`, then calls `uploadLabels(scope, db)` as today. |
| Restore | No change. The existing post-restore `snapshotFromDb` call picks up restored address labels. |
| Address reuse | Every tx paying a labelled address shows that label. |

## Edge cases

- **Tx pays two labelled addresses:** the lower vout's label is shown.
- **UTXO name on one output:** it beats an address label on any output, including a lower vout. This matches the precedence above.
- **Outgoing tx whose destination or change is one of our labelled receive addresses:** the address label shows. A tx with a note still shows the note.
- **Label cleared on Receive:** the row is deleted, the snapshot is rebuilt, and the row falls back to the muted txid (or to a UTXO name or note, if one exists).
- **Unparseable tx bytes, or an output script we can't map to an address (OP_RETURN, bare multisig):** that output is skipped, or the tx yields no label.
- **Whitespace-only label in the table:** treated as absent (defensive; `setAddressLabel` already trims and deletes empty labels).

## Testing (TDD)

- **`parse` `PaymentLabelResolveTest`, `firstAddressLabel`:**
  - lowest vout wins across two labelled outputs;
  - an unlabelled output before a labelled one;
  - no match returns `null`;
  - a blank label is skipped;
  - an OP_RETURN output is skipped;
  - garbage bytes return `null`.
- **`TxListCaptionTest`:**
  - the address label shows when the note and UTXO name are absent;
  - the UTXO name beats the address label, and the note beats both;
  - the row is not muted when only an address label exists.
- **`snapshotFromDb` test:**
  - a confirmed tx paying a labelled address gets `addressLabel`, also without a wallet;
  - address reuse labels both txs;
  - a pending send from `txHex` gets it;
  - clearing the label removes it on the next snapshot.
- **`AddressLabelsActionsTest`:** `setAddressLabel` refreshes the store, so the row shows the label right after Save and drops it after clearing.
