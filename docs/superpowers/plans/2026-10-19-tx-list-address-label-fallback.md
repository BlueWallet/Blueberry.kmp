# Tx list address label fallback: implementation plan

Spec: `docs/superpowers/specs/2026-10-19-tx-list-address-label-fallback-design.md` (63ab98f)

**Goal:** when a tx has no note and no UTXO name, the tx list shows the Receive label of the lowest-vout output paying a labelled address of ours.

**Verify:**

```
JAVA_HOME=/home/box/jdk/current ANDROID_HOME=/home/box/android-sdk ./gradlew ktlintCheck detekt :parse:jvmTest :labels:jvmTest :storage:jvmTest :shared:jvmTest :androidApp:assembleDebug :desktopApp:compileKotlin
```

Each task follows the same steps: write the red test, run it and watch it fail, write minimal code, run it green, commit.

## Task 1: `firstAddressLabel` helper (parse)

- **Test:** `parse/src/commonTest/.../PaymentLabelResolveTest.kt`. Build a tx with fr.acinq `Transaction`:
  - Outputs: vout 0 unlabelled P2WPKH, vout 1 OP_RETURN, vout 2 labelled `"  b  "`, vout 3 labelled `"a"`. Expect `"b"`.
  - A blank label is skipped.
  - No match returns `null`.
  - Garbage bytes return `null`.
- **Code:** `parse/src/commonMain/.../PaymentLabelResolve.kt`:

```kotlin
fun firstAddressLabel(tx: ByteArray, labelByAddress: Map<String, String>): String? {
    val outputs = runCatching { Transaction.read(tx).txOut }.getOrNull() ?: return null
    return outputs.firstNotNullOfOrNull { out ->
        addressFromOutputScript(out.publicKeyScript.toByteArray())
            ?.let { labelByAddress[it]?.trim() }
            ?.takeIf { it.isNotEmpty() }
    }
}
```

- **Run:** `:parse:jvmTest --tests '*PaymentLabelResolveTest*'`
- **Commit:** "Resolve the first address label a tx pays"

## Task 2: Caption precedence (shared)

- **Test:** `TxListCaptionTest.address_label_fills_in_after_note_and_utxo_label`:
  - `txListSecondary(h, null, null, "savings") == "savings"`
  - the UTXO name beats it;
  - the note beats both;
  - `txListSecondaryMuted(null, null, "savings")` is false;
  - a blank address label stays muted.
- **Code:** `TxListCaption.kt` gets a trailing `addressLabel: String? = null` on both functions, used after `utxoLabel`.
- **Commit:** "Fall back to the address label in the tx list caption"

## Task 3: Snapshot rows carry `addressLabel` (shared)

- **Test:** `shared/.../PaymentLabelResolveTest.kt`:
  - `snapshot_exposes_address_label_without_wallet`:
    - Two stored txs pay the same labelled address (reuse). Both rows get the label.
    - Delete the label; the next snapshot has `null`.
  - `pending_send_exposes_address_label_from_tx_hex`: a `SendRow` whose change output is a labelled address.
- **Code:** in `WalletTxsStore.kt`:
  - `WalletTxRow.addressLabel: String? = null`.
  - `snapshotFromDb` builds `labelByAddress` from `db.addressLabels.list()`, plus a local `addressLabelOf(bytes)` that returns `null` when the map is empty.
  - Confirmed rows use `tx.tx`.
  - Pending rows use `runCatching { hexToBytes(row.txHex) }`, passed through `pendingSendRows` / `pendingWalletRows` / `pendingWalletTxRow` the same way `utxoLabelByTxid` is.
  - `HomeTxBubble` passes `tx.addressLabel`.
- **Commit:** "Show address labels in the tx list"

## Task 4: Receive Save rebuilds the snapshot (shared)

- **Test:** `AddressLabelsActionsTest`:
  - With a store and a stored tx paying the address: after `setAddressLabel` the store row has `addressLabel == "donations"`.
  - After clearing it, `null`.
  - The dirty flag is still `"1"`.
- **Code:** `setAddressLabel(db, wallet, walletTxsStore, address, label)` mirrors `setTxNote`: write inside `editLabels`, then `walletTxsStore.apply(snapshotFromDb(db, at, at, wallet))`. `ReceiveScreen` passes `runtime.wallet` and `runtime.walletTxsStore`.
- **Commit:** "Refresh the tx list when an address label is saved"

## Then

1. Run full verification and count the tests.
2. Review `git diff 63ab98f..HEAD`, fix major findings, and re-verify.
