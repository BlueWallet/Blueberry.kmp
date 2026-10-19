# Address label sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Address labels sync as BIP-329 `addr` records in the existing encrypted label file.

**Architecture:** `WalletLabels` gains `addressLabels` (default empty). Encode and decode handle `addr`, and a stored file counts as valid when it has any known record. Upload snapshots `db.addressLabels`. Restore clears and inserts it with the other two tables. `setAddressLabel` goes back through `editLabels`, and Receive Save calls `uploadLabels`.

**Tech Stack:** Kotlin Multiplatform, kotlinx.serialization JSON, `:labels`, `:shared`.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-10-19-address-label-sync-design.md`
- Record: `{"type":"addr","ref":"<address>","label":"<label>"}`. No `origin` / `spendable`
- Line order: `output`, then `tx`, then `addr`, each sorted by ref
- One file, one sequence number, one dirty flag. No new keys
- Watch-only and WIF stay out of sync
- Env: `JAVA_HOME=/home/box/jdk/current ANDROID_HOME=/home/box/android-sdk`
- Verify: `./gradlew ktlintCheck detekt :labels:jvmTest :storage:jvmTest :shared:jvmTest :androidApp:assembleDebug :desktopApp:compileKotlin`
- Commit as `git -c user.name=Overtorment -c user.email=overtorment@gmail.com commit`. Do not push

## File structure

```
labels/src/commonMain/kotlin/io/bluewallet/blueberry/labels/WalletLabels.kt            (addr model, encode, decode, valid-file check)
labels/src/commonTest/kotlin/io/bluewallet/blueberry/labels/WalletLabelsTest.kt
shared/src/commonMain/kotlin/io/bluewallet/blueberry/PublishWalletLabels.kt            (upload + restore include address labels)
shared/src/commonMain/kotlin/io/bluewallet/blueberry/AddressLabelsActions.kt           (back through editLabels)
shared/src/commonMain/kotlin/io/bluewallet/blueberry/ReceiveScreen.kt                  (Save uploads)
shared/src/commonTest/kotlin/io/bluewallet/blueberry/PublishWalletLabelsTest.kt
shared/src/commonTest/kotlin/io/bluewallet/blueberry/RestoreWalletLabelsTest.kt
shared/src/commonTest/kotlin/io/bluewallet/blueberry/AddressLabelsActionsTest.kt
```

---

### Task 1: `addr` records in `:labels`

**Interfaces:**
- Produces: `data class AddressLabel(val address: String, val label: String)` and `WalletLabels(utxoNames, paymentLabels, addressLabels: List<AddressLabel> = emptyList())`

- [ ] **Step 1: Failing tests in `WalletLabelsTest`**
  - `encrypt_wallet_labels_seals_bip329_lines`: add `addressLabels = listOf(AddressLabel(ADDRESS, "donations"))` and expect a third line `{"type":"addr","ref":"$ADDRESS","label":"donations"}`.
  - `decrypt_wallet_labels_skips_bad_lines_and_keeps_the_rest`: the `addr` line is now kept. Expect `addressLabels = listOf(AddressLabel("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa", "skip"))`.
  - New test:

```kotlin
    @Test
    fun stored_wallet_labels_accepts_an_addr_only_file() {
        val payload =
            encryptWalletLabels(
                ACCOUNT_KEY,
                WalletLabels(emptyList(), emptyList(), listOf(AddressLabel(ADDRESS, "donations"))),
            )

        assertEquals(
            WalletLabels(emptyList(), emptyList(), listOf(AddressLabel(ADDRESS, "donations"))),
            storedWalletLabels(ACCOUNT_KEY, payload),
        )
    }
```

  - Add `private const val ADDRESS = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"`.

- [ ] **Step 2: Run `./gradlew :labels:jvmTest`, expect FAIL** (`Unresolved reference 'AddressLabel'`)

- [ ] **Step 3: Implement in `WalletLabels.kt`**

```kotlin
data class AddressLabel(
    val address: String,
    val label: String,
)

data class WalletLabels(
    val utxoNames: List<UtxoLabel>,
    val paymentLabels: List<PaymentLabel>,
    val addressLabels: List<AddressLabel> = emptyList(),
)
```

  - Encode: after the `tx` loop, add `labels.addressLabels.sortedBy { it.address }.forEach { row -> add(bip329Line("addr", row.address, row.label)) }`.
  - Decode: add `val addressLabels = linkedMapOf<String, String>()` and the branch `"addr" -> addressLabels[record.ref] = record.label`. Return `addressLabels = addressLabels.map { (address, label) -> AddressLabel(address, label) }.sortedBy { it.address }`.
  - Known types: `type == "output" || type == "tx" || type == "addr"`.
  - `storedWalletLabels`: `hasRow` adds `|| labels.addressLabels.isNotEmpty()`. Update the KDoc to say "output, tx or addr rows".

- [ ] **Step 4: Run `./gradlew :labels:jvmTest`, expect PASS**
- [ ] **Step 5: Commit** `Read and write BIP-329 addr labels`

### Task 2: Sync address labels from `:shared`

**Interfaces:**
- Consumes: Task 1 `AddressLabel`, `WalletLabels.addressLabels`. `db.addressLabels.list(): List<AddressLabelRow>`, `upsert(address, label)`, `delete(address)`

- [ ] **Step 1: Failing tests**
  - `PublishWalletLabelsTest.mnemonic_posts_every_label_under_the_extended_fingerprint`: also `db.addressLabels.upsert(ADDRESS, "donations")`, and expect `addressLabels = listOf(AddressLabel(ADDRESS, "donations"))`.
  - `RestoreWalletLabelsTest.remote_ahead_replaces_local_labels_and_stores_seq`: local `db.addressLabels.upsert("bc1qlocal", "local-only")`. The remote carries `addressLabels = listOf(AddressLabel(ADDRESS, "from-remote"))`. Assert `db.addressLabels.list() == listOf(AddressLabelRow(ADDRESS, "from-remote"))`.
  - `RestoreWalletLabelsTest.non_bip329_blob_does_not_wipe_local_labels`: also `db.addressLabels.upsert(ADDRESS, "keep")`, and assert it survives.
  - `AddressLabelsActionsTest`: expect `assertEquals("1", db.keyValue.get(LABELS_DIRTY_KEY))` after the first set. Rename the test to `set_trims_marks_labels_dirty_empty_deletes`.

- [ ] **Step 2: Run `./gradlew :shared:jvmTest --tests '*WalletLabels*' --tests '*AddressLabelsActions*'`, expect FAIL** (assertion failures)

- [ ] **Step 3: Implement**
  - `PublishWalletLabels.kt` upload snapshot (`uploadUntilCaughtUp`):

```kotlin
                    WalletLabels(
                        utxoNames = db.utxoNames.list().map { UtxoLabel(it.outpoint, it.name) },
                        paymentLabels = db.txPaymentLabels.list().map { PaymentLabel(it.txid, it.label) },
                        addressLabels = db.addressLabels.list().map { AddressLabel(it.address, it.label) },
                    )
```

  - Restore transaction (`pullLabelsIfRemoteAhead`): add `db.addressLabels.list().forEach { db.addressLabels.delete(it.address) }` next to the other deletes, and `labels.addressLabels.forEach { db.addressLabels.upsert(it.address, it.label) }` next to the other inserts.
  - `AddressLabelsActions.kt`:

```kotlin
/** Persist a label for one of our addresses. Trim; empty clears. Marks labels dirty for sync. */
fun setAddressLabel(
    db: Database,
    address: String,
    label: String,
) {
    val trimmed = label.trim()
    editLabels(db) {
        if (trimmed.isEmpty()) {
            db.addressLabels.delete(address)
        } else {
            db.addressLabels.upsert(address, trimmed)
        }
    }
}
```

  - `ReceiveScreen.kt`: `val scope = rememberCoroutineScope()` in `ReceiveScreen`. In `onSave`, after `setAddressLabel(db, target, draft)`, call `uploadLabels(scope, db)`.

- [ ] **Step 4: Run the same tests, expect PASS**
- [ ] **Step 5: Verify** (the Global Constraints command). Expect BUILD SUCCESSFUL and 0 failures
- [ ] **Step 6: Commit** `Sync address labels with the encrypted label backup`
