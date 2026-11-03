# UTXO default label from its address label — design

## Problem

A label on one of our addresses already shows as the default caption of every
transaction that pays to it. Coins (UTXOs) on that address still show as
unlabeled on Coin control and on Send Review, so the user cannot tell them apart.

## How the tx default works today

- `WalletTxRow.addressLabel` is filled at snapshot build time
  (`snapshotFromDb` → `TxRowLabels.address` → `parse/firstAddressLabel`) from
  `db.addressLabels`. Nothing is written to the tx note store.
- Display picks the first non-blank of `note` (explicit tx label),
  `utxoLabel` (first explicit coin name of the tx), `addressLabel`
  (`txListSecondary` / `txListSecondaryMuted`).
- The tx Note field and its edit draft use the explicit note only
  (`TxDetailFields`, `TxDetailsScreen`), so saving never copies the default in.
- BIP329 export (`PublishWalletLabels`) writes only the stored tables, so the
  derived default is never exported as an explicit label.
- `setAddressLabel` rebuilds the snapshot, so the caption follows edits.

## Decision

Mirror that: a display-time fallback, nothing persisted, no migration.

- `WalletUtxoRow` gains `addressLabel: String?`, filled in `walletCoins` from
  `db.addressLabels` by the coin's own address. `withStoredUtxoMeta` refreshes
  it too, so a rebuild that started before an address edit cannot install a
  stale value.
- `WalletUtxoRow.label` (computed) = trimmed explicit `name`, else trimmed
  `addressLabel`, else null. An explicit coin name always wins.
- Display sites use `label`: Coin control (`CoinControlCoin.label` → row
  subtitle, privacy state Labeled/Unlabeled and its filter chip counts,
  "<label> group" titles) and Send Review inputs (`utxoRowTitle`).
- Edit sites keep the explicit `name`: the coin sheet draft
  (`CoinsRowModel.name`) and the "did the draft change" check. Opening and
  saving a coin with only a default does not persist it. The sheet shows the
  address label as the field placeholder so the default is visible.
- BIP329 export/import unchanged: derived defaults are never written.

Alternatives rejected: persisting the address label into `utxoNames` on
receive (needs migration, goes stale when the address label changes, would
leak into BIP329 export as explicit `output` labels); resolving at each UI site
(duplicated lookups, easy to miss a site).

## Testing

- `WalletUtxoRow.label` precedence (explicit wins, blank explicit falls back,
  blanks → null).
- `setAddressLabel` with a wallet: the coin on that address gets the label;
  clearing removes it; an explicit coin name still wins.
- `buildCoinControlSnapshot`: coin with only an address label is Labeled and
  shows it as the subtitle.
