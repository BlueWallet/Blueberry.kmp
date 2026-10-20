# Receive address navigation: implementation plan

Spec: `docs/superpowers/specs/2026-10-20-receive-address-nav-design.md` (approved; uncommitted)

**Rule:** nothing gets committed. Every step leaves its changes in the working tree on `feature/receive-address-nav`.

**Verify:**

```
JAVA_HOME=/home/box/jdk/current ANDROID_HOME=/home/box/android-sdk ./gradlew ktlintCheck detekt :wallet:jvmTest :parse:jvmTest :labels:jvmTest :storage:jvmTest :shared:jvmTest :androidApp:assembleDebug :desktopApp:compileKotlin
```

Each task follows the same steps: write the red test, run it and watch it fail, write minimal code, run it green.

## Task 1: `lastReceiveIndex` (wallet)

- **Test:** `wallet/.../ReceiveAddressTest.kt` `last_receive_index_is_gap_limit_past_highest_used_inside_window`:
  - `lastReceiveIndex(emptyList(), 40) == GAP_LIMIT - 1`;
  - `listOf(0, 1, 5)` with window 40 → `5 + GAP_LIMIT`;
  - `listOf(30)` with window 40 → `39` (window cap);
  - `listOf(0, 5)` → same as `listOf(5)` (holes ignored).
- **Code:** in `wallet/.../ReceiveAddress.kt`:

```kotlin
fun lastReceiveIndex(usedExternal: Collection<Int>, windowSize: Int): Int =
    minOf((usedExternal.maxOrNull() ?: -1) + GAP_LIMIT, windowSize - 1)
```

## Task 2: `receiveChain` (parse)

- **Test:** `parse/.../ReceiveChainTest.kt`:
  - For the abandon mnemonic, with the window at 40 for P2WPKH, a stored tx pays index 2, another pays index 1, and a third spends that index 1 output. `receiveChain(db, wallet, start = index 0 address)` gives:
    - `start == 0`;
    - `used == {1, 2}`;
    - `addresses[i] == hd(P2WPKH, i).address`;
    - `addresses.size == 40`;
    - `lastIndex == 2 + GAP_LIMIT`.
  - The saved receive type (P2TR) is honoured.
  - A WIF secret returns `null`.
- **Code:** `parse/.../ReceiveChain.kt`:
  - `data class ReceiveChain(val addresses: List<String>, val used: Set<Int>, val start: Int)` with `val lastIndex get() = lastReceiveIndex(used, addresses.size)`.
  - `fun receiveChain(db, wallet: Wallet, start: String): ReceiveChain?`, for BIP84-kind wallets only. It returns `null` when `start` isn't on the chain.
  - The receive script type rule (mnemonic → saved type, else P2WPKH) moves to one internal helper shared with `snapshotReceiveAddress`.

## Task 3: `canStepReceive` and the UI (shared)

- **Test:** `ReceiveScreenTest.step_stays_between_zero_and_last_index`:
  - left is disabled at 0;
  - right is disabled at `lastIndex`;
  - both are enabled in between.
- **Code:**
  - `fun canStepReceive(index: Int, delta: Int, lastIndex: Int): Boolean = index + delta in 0..lastIndex`.
  - New `ui/NavArrow.kt` (`NavArrowDirection.Left/Right`, `enabled`, 36dp, the same path as `SheetBackArrow`, mirrored for Right, muted when disabled). `CoinsUtxoSheet` uses it in place of `SheetBackArrow`.
  - `ReceiveScreen`:
    - `start` (pinned, as today) → `chain` (remembered on `start`) → `index` (remembered on `chain`) → `address = chain.addresses[index]`, falling back to `start` when `chain` is `null`.
    - The arrows wrap the QR (real or placeholder) in a Row.
    - A `#index` caption sits under the QR row.
    - A red `used` line sits under the label.
    - Left/Right key handling on the screen root (focus requested when the sheet state changes).
    - The script switch re-pins `start`.

## Then

1. Run full verification and compare the test counts.
2. Self-review the diff, fix real findings, and re-verify.
