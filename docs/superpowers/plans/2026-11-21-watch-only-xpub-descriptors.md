# Watch-only xpub, ypub, descriptors and taproot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Import watch-only HD accounts from xpub, ypub, zpub and single-sig descriptors (`pkh`, `sh(wpkh)`, `wpkh`, `tr`), with correct PSBT origin and taproot fields.

**Architecture:** Every extended-key watch parses into one `WatchKey(account, scriptType, origin?)`. Derivation, change picking, Receive and PSBT building switch from "is it a zpub" to "the secret's watch key". The stored `wallet_secret` text stays the source of truth.

**Tech Stack:** Kotlin Multiplatform, bitcoin-kmp 0.31.0 (`DeterministicWallet`, `KeyPath`, `Psbt`, `TaprootBip32DerivationPath`), kotlin.test.

Spec: `docs/superpowers/specs/2026-11-21-watch-only-xpub-descriptors-design.md`

## Global Constraints

- Mainnet only; account-level (depth 3) keys only.
- Stored zpub, mnemonic, WIF and address secrets must parse to the same kind and value as before.
- Error messages never echo key material.
- Label sync and Private Send stay mnemonic/WIF-only.
- Checks: `JAVA_HOME=/home/box/jdk/current ./gradlew :wallet:jvmTest :parse:jvmTest :shared:compileKotlinJvm :shared:jvmTest ktlintCheck detekt --offline`.

## Test vectors (`abandon … about`, master fingerprint `73c5da0a`, computed with embit)

- BIP44 xpub `xpub6BosfCnifzxcFwrSzQiqu2DBVTshkCXacvNsWGYJVVhhawA7d4R5WSWGFNbi8Aw6ZRc1brxMyWMzG3DSSSSoekkudhUd9yLb6qx39T9nMdj` → `1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA` / change `1J3J6EvPrv8q6AC3VCjWV45Uf3nssNMRtH`
- BIP49 ypub `ypub6Ww3ibxVfGzLrAH1PNcjyAWenMTbbAosGNB6VvmSEgytSER9azLDWCxoJwW7Ke7icmizBMXrzBx9979FfaHxHcrArf3zbeJJJUZPf663zsP` → `37VucYSaXLCAsxYyAPfbSi9eh4iEcbShgf` / `34K56kSjgUCUSD8GTtuF7c9Zzwokbs6uZ7`
- BIP86 xpub `xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteoGVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ`
- `tr([73c5da0a/86'/0'/0']<BIP86 xpub>/<0;1>/*)#kjk9q86c`
- `pkh([73c5da0a/44'/0'/0']<BIP44 xpub>/<0;1>/*)#x4nu8kpc`
- `wpkh(<BIP84 xpub>)#fnl05fl6`, `tr(<BIP86 xpub>)#9uqpxtcg`
- `tr([73c5da0a/86'/0'/0']<BIP86 xpub>/0/*)#rg247h69` + `.../1/*)#ju05rz2a`
- BIP380: `raw(deadbeef)#89f8spxm`

## File map

- Create `wallet/.../DescriptorChecksum.kt`: BIP380 checksum.
- Create `wallet/.../WatchKey.kt`: `KeyOrigin`, `WatchKey`, account key decoding, `parseWatchKey`.
- Create `wallet/.../Descriptor.kt`: `parseDescriptor` and its helpers.
- Modify `wallet/.../Secret.kt`: new kinds, dispatch, `hdScriptTypes()`.
- Modify `wallet/.../Derive.kt`: derive from `WatchKey`.
- Modify `wallet/.../MnemonicChange.kt`: types from `hdScriptTypes()`.
- Modify `wallet/.../BuildSendTx.kt`: `SendAccount.Watch(WatchKey)`, origin paths, tap fields, change outputs.
- Modify `parse/.../UsedIndexes.kt`: receive type from the watch key.
- Modify `shared/.../SettingsScreen.kt`, `shared/.../onboarding/OnboardingApp.kt`: labels and helper text.
- Tests: `DescriptorChecksumTest`, `WatchKeyTest` (new), `SecretTest`, `DeriveTest`, `MnemonicChangeTest`, `BuildSendTxTest`, `WalletTest`, `parse/ReceiveChainTest`, `shared/SettingsSecretLabelTest` (new).

---

### Task 1: BIP380 descriptor checksum

**Files:** Create `wallet/src/commonMain/.../DescriptorChecksum.kt`; Test `wallet/src/commonTest/.../DescriptorChecksumTest.kt`

**Interfaces:** Produces `fun descriptorChecksum(descriptor: String): String?` (null when a character is outside the BIP380 charset).

- [ ] Step 1: failing test

```kotlin
class DescriptorChecksumTest {
    @Test fun bip380_vector() = assertEquals("89f8spxm", descriptorChecksum("raw(deadbeef)"))
    @Test fun matches_embit_for_multipath_tr() =
        assertEquals("kjk9q86c", descriptorChecksum("tr([73c5da0a/86'/0'/0']$ABANDON_BIP86_XPUB/<0;1>/*)"))
    @Test fun rejects_non_charset() = assertNull(descriptorChecksum("wpkh(é)"))
}
```

- [ ] Step 2: run `./gradlew :wallet:jvmTest --tests '*DescriptorChecksumTest*' --offline` → FAIL (unresolved reference).
- [ ] Step 3: implement the BIP380 polymod (generator `0xf5dee51989, 0xa9fdca3312, 0x1bab10e32d, 0x3706b1677a, 0x644d626ffd`; input charset from BIP380; checksum charset `qpzry9x8gf2tvdw0s3jn54khce6mua7l`).
- [ ] Step 4: run → PASS.
- [ ] Step 5: commit `feat(wallet): BIP380 descriptor checksum`.

### Task 2: WatchKey and bare xpub/ypub/zpub parsing

**Files:** Create `WatchKey.kt`; Modify `Secret.kt:9,68-85`; Test `WatchKeyTest.kt`, `SecretTest.kt`

**Interfaces:**
- Produces `data class KeyOrigin(val fingerprint: Long, val path: KeyPath)`
- Produces `data class WatchKey(val account: ExtendedPublicKey, val scriptType: AddressScriptType, val origin: KeyOrigin? = null)` with `val accountPath: String`, `fun keyPath(change: Boolean, index: Int): List<Long>` (relative `chain/index`), `fun bip32Fingerprint(): Long`, `fun bip32Path(change, index): KeyPath`.
- Produces `WalletSecretKind { MNEMONIC, XPUB, YPUB, ZPUB, DESCRIPTOR, WIF, ADDRESS }`, `fun parseWatchKey(value: String): WatchKey`, `fun ParsedWalletSecret.watchKey(): WatchKey?`, `fun ParsedWalletSecret.hdScriptTypes(): List<AddressScriptType>`.

- [ ] Step 1: failing tests

```kotlin
@Test fun accepts_account_xpub_ypub_zpub_with_their_script() {
    assertEquals(WalletSecretKind.XPUB, parseWalletSecret(ABANDON_BIP44_XPUB).kind)
    assertEquals(WalletSecretKind.YPUB, parseWalletSecret(ABANDON_BIP49_YPUB).kind)
    assertEquals(AddressScriptType.P2PKH, parseWatchKey(ABANDON_BIP44_XPUB).scriptType)
    assertEquals(AddressScriptType.P2SH_P2WPKH, parseWatchKey(ABANDON_BIP49_YPUB).scriptType)
    assertEquals(AddressScriptType.P2WPKH, parseWatchKey(BLUE_ZPUB).scriptType)
    assertNull(parseWatchKey(BLUE_ZPUB).origin)
}
@Test fun rejects_testnet_private_and_non_account_keys()  // vpub, zprv, master xpub → messages from the spec table
```

- [ ] Step 2: run → FAIL.
- [ ] Step 3: implement `parseExtendedKey(value, allowed prefixes)` (decode, check prefix and depth 3) and route `xpub`/`ypub`/`zpub` in `parseWalletSecret`. Update the old test that expected xpub rejection.
- [ ] Step 4: run `:wallet:jvmTest` → PASS.
- [ ] Step 5: commit `feat(wallet): watch key model and account xpub/ypub parsing`.

### Task 3: Descriptor parsing

**Files:** Modify `WatchKey.kt`, `Secret.kt`; Test `WatchKeyTest.kt`

**Interfaces:** Produces `fun parseDescriptor(text: String): WatchKey` and `WalletSecretKind.DESCRIPTOR` routing for values starting with `name(`.

- [ ] Step 1: failing tests: each wrapper → script type; origin fingerprint `0x73c5da0a` and path `m/86'/0'/0'`; `h` hardened marker; bare key; multipath; two-line paste; missing checksum ok; wrong checksum message; every rejection row of the spec table, e.g.

```kotlin
@Test fun rejects_script_tree() = assertMessage("taproot script trees are not supported") {
    parseWalletSecret("tr($ABANDON_BIP86_XPUB,{pk($ABANDON_BIP86_XPUB)})")
}
```

- [ ] Step 2: run → FAIL.
- [ ] Step 3: implement: strip `#checksum` and verify; reject multisig/other wrappers/script trees; unwrap `sh(wpkh(`, `pkh(`, `wpkh(`, `tr(`; split key into origin/key/suffix with one regex; classify the key prefix (private/testnet/non-xpub); parse origin steps; parse suffix (`""`, `/<0;1>/*`, `/0/*`, `/1/*`, hardened → error); two lines must be one `/0/*` and one `/1/*` with equal wrapper/key/origin. Store trimmed lines joined by `\n`.
- [ ] Step 4: run → PASS.
- [ ] Step 5: commit `feat(wallet): parse single-sig output descriptors`.

### Task 4: Derivation from a watch key

**Files:** Modify `Derive.kt:129-181`; Test `DeriveTest.kt`

- [ ] Step 1: failing tests: for xpub, ypub, `tr` descriptor and `wpkh` descriptor of ABANDON, `deriveWatchWallet(key, WatchGaps(3, 2)).addresses.map { it.address }` equals the mnemonic watch filtered to that script type; origin descriptor paths read `m/86'/0'/0'/0/0`; bare key paths use the standard account path; xpub watch has only P2PKH addresses.
- [ ] Step 2: run → FAIL.
- [ ] Step 3: implement: `deriveHdWatchWallet` takes `ParsedWalletSecret`; mnemonic branch unchanged; watch key branch derives `watchKey.scriptType` with `gaps[scriptType]` and `watchKey.accountPath`.
- [ ] Step 4: run → PASS.
- [ ] Step 5: commit `feat(wallet): derive addresses for every watch key script`.

### Task 5: Change and Receive use the key's script

**Files:** Modify `MnemonicChange.kt`, `parse/.../UsedIndexes.kt:133-138`; Test `MnemonicChangeTest.kt`, `parse/.../ReceiveChainTest.kt`

- [ ] Step 1: failing tests: `pickMnemonicChange` on an xpub wallet picks P2PKH internal index 0; `snapshotReceive` on a `tr` descriptor wallet returns `hd(P2TR, 0)` with a chain of P2TR external addresses.
- [ ] Step 2: run → FAIL.
- [ ] Step 3: implement: `pickMnemonicChange` uses `parseWalletSecret(secret).hdScriptTypes()` (error if empty); `snapshotReceive` receive type = `loadReceiveScriptType(db)` for mnemonic, else `hdScriptTypes().singleOrNull() ?: P2WPKH`.
- [ ] Step 4: run `:wallet:jvmTest :parse:jvmTest` → PASS.
- [ ] Step 5: commit `feat: change and receive follow the watch key script`.

### Task 6: PSBT origin, taproot fields and change outputs

**Files:** Modify `BuildSendTx.kt:255-318,540-638`; Test `WatchKeySendTest.kt` (new), `EncodePsbtUrTest.kt` (golden PSBT now carries output derivations)

- [ ] Step 1: failing tests:
  - origin descriptor `wpkh([73c5da0a/84'/0'/0']…)` → input derivation fingerprint `0x73c5da0a`, path `[84', 0', 0', 0, 0]`.
  - bare xpub → account fingerprint + `[0, 0]` (existing zpub test still passes).
  - `tr` origin descriptor → input `taprootInternalKey` set, `taprootDerivationPaths` = `{xonly: (leaves=[], 73c5da0a, 86'/0'/0'/0/0)}`, `derivationPaths` empty.
  - change output of a zpub send carries `derivationPaths` with path `[1, 0]`; tr change output carries tap internal key and tap derivation.
  - ypub input carries redeem script.
- [ ] Step 2: run → FAIL.
- [ ] Step 3: implement: `SendAccount.Zpub` → `SendAccount.Watch(WatchKey)`; `publicKeyAt`, `bip32PathNums`, `fingerprint` use `WatchKey`; P2TR inputs use `taprootDerivationPaths`; after inputs, for every output whose script is one of our HD addresses, call `updateNonWitnessOutput` (P2PKH) or `updateWitnessOutput` with redeem script / tap fields.
- [ ] Step 4: run → PASS (including `AddressWatchSendTest`, `WifSendTest`).
- [ ] Step 5: commit `feat(wallet): PSBT origin, taproot derivation and change metadata for watch keys`.

### Task 7: Persistence round trip

**Files:** Test `WalletTest.kt` (and `SecretTest.kt`)

- [ ] Step 1: tests: `saveWalletSecret(db, descriptorTwoLine)` stores trimmed lines; `createWallet` reload derives the same addresses; a stored zpub reloads with kind ZPUB and same first address. (Expected to pass once Tasks 2-4 land; if they fail, fix the cause.)
- [ ] Step 2: run → PASS.
- [ ] Step 3: commit `test(wallet): persisted watch key round trip`.

### Task 8: Settings label and import text

**Files:** Modify `shared/.../SettingsScreen.kt:312-320` (make `secretKindLabel` internal), `shared/.../onboarding/OnboardingApp.kt:435,540`; Test `shared/src/commonTest/.../SettingsSecretLabelTest.kt`

- [ ] Step 1: failing test: `secretKindLabel(ABANDON_BIP44_XPUB) == "xpub"`, ypub → `"ypub"`, descriptor → `"Descriptor"`, zpub → `"zpub"`.
- [ ] Step 2: run `:shared:jvmTest` → FAIL.
- [ ] Step 3: implement labels; update helper text and placeholder per spec.
- [ ] Step 4: run → PASS; run full checks.
- [ ] Step 5: commit `feat(ui): label xpub, ypub and descriptor secrets; mention them on Import`.

### Task 9: Verification and review loop

- [ ] Run the full check command from Global Constraints; all green.
- [ ] Review the diff (correctness, performance, robustness), fix valid findings, repeat until clean.
