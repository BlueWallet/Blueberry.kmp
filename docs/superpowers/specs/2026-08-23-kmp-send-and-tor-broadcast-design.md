# KMP send + Tor broadcast

Date: 2026-08-23  
Status: approved (conversation; remaining section approvals skipped at user request)

## Goal

Port helix3 send to Blueberry.kmp with feature parity: UTXO selection, UTXO rename, required payment label, change UTXO naming, MAX send, fee-rate preview, signed-tx Tor broadcast via echalote, and watch-only BC-UR PSBT QR. Bump `vendor/echalote.kmp` to GitHub `master`.

## Non-goals

- Edit or clear a payment label after send
- Show unconfirmed txs in the tx list
- Clearnet (non-Tor) broadcast
- Automatic coinselect (caller still passes the selected UTXOs; builder spends all of them)
- helix3 TUI chrome / key hints
- Live Tor in default Gradle tests
- Cleanup of unused pending `tx_payment_labels` rows

## Decisions

| Topic | Choice |
|-------|--------|
| UI | Same 4 steps as helix3: UTXOs → details → fee rate → preview |
| Coin select | User picks UTXOs. `pickUtxosByKeys`. Builder spends all selected. |
| Payment label | Required, trimmed, persist on successful build |
| Change UTXO name | `change from: {label}` written immediately at build. Send-max: none |
| Schema | `tx_payment_labels(txid TEXT PRIMARY KEY, label TEXT NOT NULL)` — no `change_vouts` |
| Existing DBs | `applySchema` runs `CREATE TABLE IF NOT EXISTS` so reopen picks up the table |
| Broadcast | Tor-only. echalote exit → BIP-324 v2 `tx` to a random alive `NODE_NETWORK` peer |
| echalote | Pin submodule to `master` @ `3a3c850`. Public API unchanged |
| Send context | Explicit `db` + `wallet` args (no TUI globals) |
| Watch-only preview | Animated BC-UR `crypto-psbt` QR via existing `:wallet` encoder |

## helix3 map

| helix3 | Kotlin |
| --- | --- |
| `src/wallet/build-send-tx.ts` (`txid`, `changeVouts`) | `:wallet` `BuildSendTx.kt` |
| `src/tui/send-context.ts` | `:parse` `SendContext.kt` |
| `src/tui/payment-label-actions.ts` | `:shared` `PaymentLabelActions.kt` |
| `src/tui/utxo-names-actions.ts` | `:shared` `UtxoNamesActions.kt` |
| `src/tui/components/WalletModal.tsx` send steps | `:shared` `SendScreen.kt` |
| `src/tui/broadcast-store.ts` / `broadcast-actions.ts` | `:shared` `BroadcastStore.kt` |
| `src/modules/broadcast/` | new `:broadcast` |
| `src/db` `tx_payment_labels` | `:storage` |

`SendContext` lives in `:parse` because BIP84 change needs `usedWatchIndexes` and `:wallet` must not depend on `:parse`.

## Architecture

```
Send button → SendScreen (4 steps)
  UTXOs: WalletTxsStore.utxos, setUtxoName
  Details: address / amount|MAX / payment label
  Fee: sat/vB → buildActiveSendTx → savePaymentLabel
  Preview signed: BroadcastStore + bus broadcast:request
  Preview psbt: encodeCryptoPsbtUrFragments → animated QR

PeersRuntime starts createBroadcastModule
  production: echalote createExitDialer → ByteDuplex adapter → broadcastTxV2
  tests: injected connect
```

## Units

| Unit | Role |
| --- | --- |
| echalote pin | Submodule `master` @ `3a3c850` |
| `txPaymentLabels` | Repo upsert/get/list |
| `txid` + `changeVouts` | On every `BuildSend*` / `*SendResult` |
| `buildActiveSendTx` | Change address + attach `nonWitnessUtxo` from DB |
| `pickUtxosByKeys` | Selection vs current UTXO list |
| `savePaymentLabel` | Trim, store label, name change outpoints |
| `setUtxoName` | Trim/empty-clear, refresh snapshot |
| `createBroadcastModule` | Wait peers, retry, Tor, BIP-324 tx |
| `broadcastTxV2` | Handshake, send tx, ack rules |
| `SendScreen` | 4-step Compose flow |
| Snapshot / tx list | `paymentLabel` from `tx_payment_labels` |

## Data

```sql
CREATE TABLE tx_payment_labels (
  txid TEXT PRIMARY KEY,
  label TEXT NOT NULL
);
```

`changeVouts` is computed at build time, not stored.

`WalletTxRow.paymentLabel` is loaded from `txPaymentLabels.list()` by txid. Missing → `null`.

Change name: `change from: {trimmed label}` at `txid:vout` for each change vout. Send-max writes no UTXO name. Empty/whitespace label throws `payment label is required`.

Existing files: `applySchema` creates the full schema only when `peers` is missing. After that it always executes `CREATE TABLE IF NOT EXISTS tx_payment_labels (...)`.

## Send rules (unchanged builder + new ids)

`buildSend` still consumes every caller UTXO. New fields:

- `txid`: display-order txid of the unsigned/signed transaction
- `changeVouts`: output indexes whose script equals the change address. Send-max → `[]`. Self-send (dest === change) skips the output whose amount equals the payment amount

Change address (send-context):

- MAX → destination
- WIF → `preferredWifReceiveAddress`
- Address watch → the sole watched address
- BIP84 → `firstUnusedInternalAddress` from `usedWatchIndexes`; missing → `no unused change address in watch window`

Legacy inputs: if `nonWitnessUtxo` is missing, attach `db.transactions.get(txid).tx`.

## UI

Steps and validation match helix3 `WalletModal`:

1. **UTXOs** — checkbox list (amount, short outpoint, age, value bar, name). Rename trims; empty clears. Continue requires ≥1 selected. Empty: `No UTXOs`.
2. **Details** — Address, Amount (`MAX` token or BTC), Payment label. Validate in that order. Amount must be positive and ≤ selected sum (except MAX).
3. **Fee rate** — positive sat/vB (fractional ok). Build error stays on this step.
4. **Preview** — signed: amount, fee (vB), change, label, Broadcast. Watch-only: animated BC-UR QR (1s, existing encoder). Broadcast progress: `Broadcasting via Tor`, attempt/peer, success/fail. In-flight back cancels; second back force-closes.

Tx list shows `paymentLabel` when set. Send button opens this flow. Back from step 2/3 returns to the previous step.

## Broadcast

Port helix3 `createBroadcastModule` / `v2-broadcast` / `tor-byte-duplex` / `tor-dial-policy`.

- Bus: existing `broadcast:request|cancel|progress|done`
- One job at a time; second request → `broadcast already in progress`
- Wait for alive `NODE_NETWORK` (bit 1) peers, poll 500ms
- Up to 20 attempts, random alive peer (reuse allowed), pick cap 512
- Production: 3 echalote dialer cycles, 1.5s backoff. Timeouts: dial 45s, handshake 15s, ack 15s
- Session: BIP-324 initiator + version/verack (`APP_NAME` / `APP_VERSION` from `:peers`), send `tx`
- Success: `inv`/`getdata` mentioning txid or wtxid, **or** ack timeout / peer close without `reject`
- `reject` fails that attempt
- Tests inject `connect`. Default Gradle tests never dial Tor or mainnet

echalote `ByteDuplex.close()` is synchronous; wrap it as bip324 `suspend fun close()`.

## Testing

Port helix3 unit tests (no TUI / file-log / live Tor):

- `sqlite-tx-payment-labels.test.ts`
- `payment-label-actions.test.ts`
- `send-context.test.ts`
- `build-send-tx` cases that assert `txid` / `changeVouts`
- `broadcast-module.test.ts` / `v2-broadcast.test.ts` with injected duplex
- `snapshotFromDb` includes `paymentLabel`

Pass:

- `./gradlew :storage:jvmTest`
- `./gradlew :wallet:jvmTest`
- `./gradlew :parse:jvmTest`
- `./gradlew :broadcast:jvmTest`
- `./gradlew :shared:jvmTest`

## Success

- Send button opens the 4-step flow
- Selected UTXOs + MAX + required label + change names behave as helix3
- Mnemonic/WIF preview broadcasts over Tor via echalote
- zpub/address preview shows BC-UR PSBT
- Tx list shows stored payment labels
- echalote submodule is on `master` @ `3a3c850`
