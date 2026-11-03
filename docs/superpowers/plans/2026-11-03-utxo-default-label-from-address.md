# UTXO default label from address — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** A coin with no explicit name shows its address label everywhere coins are displayed.

**Architecture:** Add `addressLabel` to `WalletUtxoRow`, fill it during snapshot build and meta refresh, expose a computed `label`, switch display sites to `label`. Edit drafts and BIP329 keep using explicit names only.

**Tech Stack:** Kotlin Multiplatform, kotlin.test, SQLDelight in-memory DB.

## Global Constraints

- Explicit coin name always wins over the address label.
- Nothing derived is written to `utxoNames` or exported via BIP329.
- `./gradlew ktlintCheck detekt :shared:jvmTest` must pass.

### Task 1: Row model + precedence
**Files:** `shared/src/commonMain/.../WalletTxsStore.kt`, test `shared/src/commonTest/.../UtxoDefaultLabelTest.kt`
- [ ] Failing test: `WalletUtxoRow(name=null, addressLabel=" savings ").label == "savings"`, explicit wins, blank name falls back, both blank → null.
- [ ] Add `val addressLabel: String? = null` and `val label: String? get()`.
- [ ] Run `./gradlew :shared:jvmTest --tests '*UtxoDefaultLabelTest*'` → PASS. Commit.

### Task 2: Fill from db
**Files:** `WalletTxsStore.kt` (`CoinRowCtx`, `walletCoins`, `withStoredUtxoMeta`), test in `UtxoDefaultLabelTest.kt`
- [ ] Failing tests: `withStoredUtxoMeta` refreshes `addressLabel` from `db.addressLabels` by row address; snapshot build with a wallet fills it.
- [ ] Implement, run, commit.

### Task 3: Display sites
**Files:** `CoinControlModels.kt` (`buildCoinControlSnapshot`), `SendReview.kt`, `CoinsRows.kt`, `CoinsScreen.kt`, `CoinsUtxoSheet.kt`
- [ ] Failing test: coin with only an address label → `CoinControlCoin.label == "savings"`, `privacyState == Labeled`; explicit name still wins.
- [ ] Use `u.label` in coin control and Send Review; sheet draft from explicit name; placeholder shows address label.
- [ ] Run full `:shared:jvmTest`, ktlintCheck, detekt. Commit.
