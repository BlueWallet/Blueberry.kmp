# Scan a signed PSBT back (animated BC-UR) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A watch-only wallet scans the signed PSBT back (animated or single `ur:crypto-psbt` / `ur:psbt`, or base64/hex), checks it is the transaction it built, finalizes it, reviews it and broadcasts it over the existing Tor path.

**Architecture:** An incremental `UrDecoder` in `wallet` reuses the in-house bytewords/CBOR/fountain code. `finalizeSignedPsbt` combines the scanned PSBT with our unsigned one, finalizes single-sig inputs and extracts with bitcoin-kmp's script check. In `shared`, a UI-free `SignedPsbtScan` feeds camera text to the decoder; `storeSignedPsbt` turns the stored unsigned `sends` row into the signed one (moving it when the txid changes) and `sendStoredAgain` broadcasts it. Compose screens sit on top.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, bitcoin-kmp 0.31.0 (`Psbt.read/combine/finalize*/extract/sign`), kotlin.test.

Spec: `docs/superpowers/specs/2026-11-23-animated-ur-scan-design.md`

## Global Constraints

- No new dependencies. commonMain only uses multiplatform APIs (`decodeToString`, `kotlin.io.encoding.Base64`, no `String(bytes)`/`java.*`).
- Error strings never echo PSBT bytes.
- Accepted UR types for PSBTs: `crypto-psbt`, `psbt`.
- Single-sig only: P2PKH, P2SH-P2WPKH, P2WPKH, P2TR key path.
- A stored PSBT payment keeps its coins held whatever the broadcast outcome (`PriorRun.Held` via `sendStoredAgain`).
- Checks: `JAVA_HOME=/home/box/jdk/current ./gradlew :shared:compileKotlinJvm :shared:jvmTest :wallet:jvmTest :parse:jvmTest :sync:jvmTest :filters:jvmTest :headers:jvmTest ktlintCheck detekt --offline`.

## Test vectors

- BlueWallet `watch-only-wallet.test.js` "v2: encodeUR() psbt works": PSBT `70736274ff01009a…000000` at capacity 100 → 2 parts `ur:crypto-psbt/1-2/lpadaocsptcybkgdcarhhd…` and `ur:crypto-psbt/2-2/lpaoaocsptcybkgdcarhhd…`.
- Fountain-only: the same PSBT encoded by `@ngraveio/bc-ur` `UREncoder(ur, 30, 0)` has 6 fragments; parts 7..19 (13 strings, all mixed or repeated) decode it without parts 1..6. Generated with `/workspace/bb-ur-scan-notes/gen2.js`.
- Signing: `ABANDON` mnemonic, account keys `ABANDON_BIP44_XPUB`, `ABANDON_BIP49_YPUB`, `BLUE`-style zpub via `wpkh(ABANDON_BIP84_XPUB)`, `TR_BARE_DESCRIPTOR`; private keys at `m/{44,49,84,86}'/0'/0'/0/0`.

## File map

- Create `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/UrDecoder.kt`
- Modify `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/EncodePsbtUr.kt` (helpers `internal`, decoder accessors, `decodeCryptoPsbtUrFragments` delegates)
- Create `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/SignedPsbt.kt`
- Create `shared/src/commonMain/kotlin/io/bluewallet/blueberry/SignedPsbtScan.kt`
- Create `shared/src/commonMain/kotlin/io/bluewallet/blueberry/SignedPsbtBroadcast.kt`
- Create `shared/src/commonMain/kotlin/io/bluewallet/blueberry/SignedPsbtScreen.kt`
- Modify `shared/src/commonMain/kotlin/io/bluewallet/blueberry/QrScanOverlay.kt`, `SendPsbtStep.kt`, `SendScreen.kt`, `StoredPsbtScreen.kt`
- Tests: `wallet/.../UrDecoderTest.kt`, `wallet/.../SignedPsbtTest.kt`, `shared/.../SignedPsbtScanTest.kt`, `shared/.../SignedPsbtBroadcastTest.kt`

---

### Task 1: Incremental UR decoder

**Files:** Create `UrDecoder.kt`; modify `EncodePsbtUr.kt`; test `wallet/src/commonTest/.../UrDecoderTest.kt`.

**Interfaces:**
- Produces:
  ```kotlin
  val PSBT_UR_TYPES: Set<String>                       // {"crypto-psbt", "psbt"}
  sealed class UrReceive { Progress(percent: Int); Complete(message: ByteArray); Ignored(reason: String); Failed(reason: String) }
  class UrDecoder(acceptedTypes: Set<String>) { fun receive(text: String): UrReceive; val percent: Int?; fun reset() }
  fun psbtFromUrMessage(message: ByteArray): ByteArray  // CBOR byte string → PSBT bytes
  const val UR_NOT_UR, UR_WRONG_TYPE, UR_OTHER_ANIMATION, UR_CORRUPT, UR_CHECKSUM_FAILED
  ```

- [ ] **Step 1: failing tests**

```kotlin
class UrDecoderTest {
    private fun psbtDecoder() = UrDecoder(PSBT_UR_TYPES)

    private fun UrDecoder.feed(parts: List<String>): UrReceive = parts.map { receive(it) }.last()

    private fun completedPsbt(result: UrReceive): String = hexFromBytes(psbtFromUrMessage((result as UrReceive.Complete).message))

    @Test fun bluewallet_two_part_vector_in_order() = assertEquals(BW_PSBT_HEX, completedPsbt(psbtDecoder().feed(BW_PARTS)))
    @Test fun bluewallet_two_part_vector_reversed_with_duplicates() =
        assertEquals(BW_PSBT_HEX, completedPsbt(psbtDecoder().feed(listOf(BW_PARTS[1], BW_PARTS[1], BW_PARTS[0]))))
    @Test fun fountain_only_parts_from_ngraveio_decode() = assertEquals(BW_PSBT_HEX, completedPsbt(psbtDecoder().feed(NGRAVE_MIXED_PARTS)))
    @Test fun uppercase_qr_alphanumeric_form_decodes() = assertEquals(BW_PSBT_HEX, completedPsbt(psbtDecoder().feed(BW_PARTS.map { it.uppercase() })))
    @Test fun progress_grows_and_reaches_100() { /* first part → Progress in 1..99; percent non-decreasing; Complete → percent 100 */ }
    @Test fun single_part_crypto_psbt_and_psbt_types() { /* encodeCryptoPsbtUrFragments(hex, 10_000) single; same body under "ur:psbt/" */ }
    @Test fun foreign_type_is_ignored() // "ur:crypto-account/…" → Ignored(UR_WRONG_TYPE), percent stays null
    @Test fun mixed_types_are_ignored_after_the_first() // crypto-psbt part, then the same body as ur:psbt → Ignored(UR_WRONG_TYPE)
    @Test fun a_part_of_another_animation_is_ignored_without_losing_progress()
    @Test fun corrupt_frame_is_ignored() // flip a byteword → Ignored(UR_CORRUPT)
    @Test fun not_a_ur_is_ignored() // "bitcoin:bc1…" → Ignored(UR_NOT_UR)
    @Test fun reset_starts_over()
    @Test fun round_trips_our_encoder_in_shuffled_order_at_several_capacities() // capacities 20, 60, 120, BC_UR_PSBT_CAPACITY
}
```

Full code in the test file: BW vector constants copied verbatim from BlueWallet; the ngraveio vector from `gen2.js` output.

- [ ] **Step 2:** run `./gradlew :wallet:jvmTest --tests '*UrDecoderTest*' --offline` → FAIL (unresolved `UrDecoder`).

- [ ] **Step 3: implementation**
  - In `EncodePsbtUr.kt` make `ParsedUrPart`, `parseUrPart`, `FountainDecoder`, `bytewordsDecodeMinimal`, `cborDecodeByteString`, `cborDecodeFountainPart`, `DecodedFountainPart` `internal`; `DecodedFountainPart` gains `seqNum`; `FountainDecoder` gains `expectedCount`, `recoveredCount`, `isFailed()`.
  - `UrDecoder.receive`: trim; parse (`UR_NOT_UR` on failure); type not accepted or ≠ locked type → `UR_WRONG_TYPE`; single part while a fountain is running → `UR_OTHER_ANIMATION`; single part → bytewords decode (`UR_CORRUPT`) → `Complete`; multi part → bytewords + CBOR (`UR_CORRUPT`), sequence in path must equal CBOR seqNum/seqLength, sanity bounds (`1 ≤ seqLength ≤ 10_000`, `1 ≤ messageLength ≤ seqLength × fragment.size`, fragment non-empty) else `UR_CORRUPT`; `FountainDecoder.receivePart` false → `UR_OTHER_ANIMATION`; decoder failed → `Failed(UR_CHECKSUM_FAILED)` until `reset()`; complete → `Complete`; else `Progress(percent)`.
  - `percent`: null before the first accepted part; 100 when complete; else `min(99, floor(100 × max(recovered/n, min(0.99, distinct/(1.75 n)))))`.
  - `decodeCryptoPsbtUrFragments` feeds a `UrDecoder(setOf("crypto-psbt"))` and returns `psbtFromUrMessage(...)`.

- [ ] **Step 4:** run UrDecoderTest and EncodePsbtUrTest → PASS.
- [ ] **Step 5:** commit `ADD: incremental BC-UR decoder for animated PSBT QR codes`.

### Task 2: Finalize a scanned signed PSBT

**Files:** Create `SignedPsbt.kt`; test `wallet/src/commonTest/.../SignedPsbtTest.kt`.

**Interfaces:**
- Produces:
  ```kotlin
  fun psbtBytesFromText(text: String): ByteArray?          // base64 or hex with PSBT magic, else null
  data class SignedPsbt(val txHex: String, val txid: String, val feeSats: Long, val vsize: Int, val outputs: List<Pair<String, Long>>) { fun paidTo(address: String): Long }
  sealed class SignedPsbtCheck { data class Ok(val signed: SignedPsbt); data class Failed(val reason: String) }
  fun finalizeSignedPsbt(unsignedPsbtHex: String, scanned: ByteArray): SignedPsbtCheck
  const val PSBT_NOT_A_PSBT = "This QR isn't a PSBT."
  const val PSBT_DIFFERENT_TX = "This PSBT is for a different transaction."
  const val PSBT_NOT_SIGNED = "This PSBT isn't fully signed. Sign every input on your signing device."
  const val PSBT_BAD_SIGNATURES = "The signatures in this PSBT don't check out. Sign it again on your signing device."
  ```
  `outputs` are (scriptPubKey hex, sats).

- [ ] **Step 1: failing tests** (helper builds a watch-only PSBT with `buildSend` from a funding tx like `WatchKeySendTest.send`, then signs with `Psbt.sign(priv, 0)` where `priv` is `DeterministicWallet.derivePrivateKey(master, "m/<p>'/0'/0'/0/0").privateKey`)

```kotlin
@Test fun signs_finalizes_and_extracts_each_script_type()  // P2PKH xpub, P2SH ypub, P2WPKH wpkh(), P2TR tr(): Ok; txid of extracted tx; for P2WPKH/P2TR txid == unsigned txid, for P2PKH/P2SH it differs but unsignedTxid(...) matches; feeSats == built fee; paidTo(DEST_LEGACY) == 50_000
@Test fun through_animated_ur_round_trip()                  // encodeCryptoPsbtUrFragments(signedHex, 40) → UrDecoder → finalize → Ok
@Test fun keeps_inputs_the_signer_already_finalized()       // finalizeWitnessInput ourselves before handing over → Ok
@Test fun stripped_signer_psbt_still_finalizes()            // signer PSBT rebuilt with only partial sigs (new Psbt(tx) + updateWitnessInput(txOut only) for P2WPKH + sig) → Ok via combine
@Test fun rejects_a_different_transaction()                 // PSBT for another amount → PSBT_DIFFERENT_TX
@Test fun rejects_an_unsigned_psbt()                        // the unsigned PSBT itself → PSBT_NOT_SIGNED
@Test fun rejects_a_wrong_key_signature()                   // signed with index 1 key → PSBT_BAD_SIGNATURES
@Test fun rejects_garbage()                                 // "hello".encodeToByteArray() → PSBT_NOT_A_PSBT
@Test fun reads_base64_and_hex_text()                       // psbtBase64FromHex / hex / with whitespace; null for "bitcoin:..." and "cHNi" garbage
```

- [ ] **Step 2:** run → FAIL (unresolved).
- [ ] **Step 3: implementation**

```kotlin
fun finalizeSignedPsbt(unsignedPsbtHex: String, scanned: ByteArray): SignedPsbtCheck {
    val theirs = Psbt.read(scanned).right ?: return SignedPsbtCheck.Failed(PSBT_NOT_A_PSBT)
    val ours = Psbt.read(hexToBytes(unsignedPsbtHex)).right ?: return SignedPsbtCheck.Failed(PSBT_NOT_A_PSBT)
    if (theirs.global.tx.txid != ours.global.tx.txid) return SignedPsbtCheck.Failed(PSBT_DIFFERENT_TX)
    var psbt = Psbt.combine(ours, theirs).right ?: return SignedPsbtCheck.Failed(PSBT_DIFFERENT_TX)
    for (index in psbt.inputs.indices) psbt = finalizeInput(psbt, index) ?: return SignedPsbtCheck.Failed(PSBT_NOT_SIGNED)
    val tx = psbt.extract().right ?: return SignedPsbtCheck.Failed(PSBT_BAD_SIGNATURES)
    val fee = ours.computeFees()?.sat ?: 0L
    return SignedPsbtCheck.Ok(SignedPsbt(hexFromBytes(Transaction.write(tx)), tx.txid.toString(), fee, (tx.weight() + 3) / 4, tx.txOut.map { scriptHex(it.publicKeyScript.toByteArray()) to it.amount.sat }))
}
```
  `finalizeInput`: finalized inputs pass through; P2PKH → `finalizeNonWitnessInput(i, [sig, pub])`; witness inputs by `scriptTypeOfScriptPubKey(txOut script)`: P2WPKH → witness `[sig, pub]`; P2SH-P2WPKH → add `redeemScript = Script.pay2wpkh(pub)` with `updateWitnessInput` when missing, then witness `[sig, pub]`; P2TR → witness `[taprootKeySignature]`; missing signature or unknown script → null.

- [ ] **Step 4:** run → PASS. **Step 5:** commit `ADD: finalize a signed PSBT scanned back from a signing device`.

### Task 3: Scan state for the camera

**Files:** Create `shared/.../SignedPsbtScan.kt`; test `shared/src/commonTest/.../SignedPsbtScanTest.kt`.

**Interfaces:**
- Consumes: `UrDecoder`, `UrReceive`, `PSBT_UR_TYPES`, `psbtFromUrMessage`, `psbtBytesFromText`, `PSBT_NOT_A_PSBT`.
- Produces:
  ```kotlin
  internal sealed class SignedPsbtScanState { data class Scanning(val percent: Int?, val note: String?); class Done(val psbt: ByteArray) }
  internal class SignedPsbtScan { var state: SignedPsbtScanState; fun accept(text: String): SignedPsbtScanState; fun reset(note: String? = null) }
  ```

- [ ] **Step 1: failing tests**

```kotlin
@Test fun animated_frames_reach_done()            // encode a PSBT at capacity 30, feed parts; percents non-decreasing; ends Done with the same bytes
@Test fun repeated_frame_is_cheap_and_harmless()  // same text twice → same state
@Test fun base64_psbt_is_done_at_once()
@Test fun unknown_text_keeps_progress_and_says_why() // after one UR part, "hello" → Scanning(same percent, PSBT_NOT_A_PSBT)
@Test fun reset_clears_progress_and_shows_note()
```

- [ ] **Step 2:** FAIL. **Step 3:** implement as in the spec (UR text starts with `ur:` case-insensitively → decoder; else `psbtBytesFromText`; decoder `Failed` resets the decoder and shows the reason). **Step 4:** PASS. **Step 5:** commit `ADD: camera scan state for a signed PSBT`.

### Task 4: Store and broadcast the signed payment

**Files:** Create `shared/.../SignedPsbtBroadcast.kt`; test `shared/src/commonTest/.../SignedPsbtBroadcastTest.kt`.

**Interfaces:**
- Consumes: `SignedPsbt`, `sendStoredAgain`, `storedSendSession`, `movePaymentLabel`, `editLabels`, `hydrateWallet`.
- Produces:
  ```kotlin
  const val SIGNED_PSBT_GONE = "This payment is no longer waiting to be signed."
  fun storeSignedPsbt(db: Database, runtime: PeersRuntime, storedTxid: String, signed: SignedPsbt): Boolean
  sealed class SignedPsbtSent { data class Started(val id: String); data class Refused(val reason: String) }
  fun startSignedPsbtBroadcast(runtime: PeersRuntime, db: Database, signed: SignedPsbt, receipt: BroadcastReceipt): SignedPsbtSent
  ```

- [ ] **Step 1: failing tests** (use `runFlow`, `coin`, `storeStored`-style setup from `SendHoldsTest`/`SendFlowTest`)

```kotlin
@Test fun a_segwit_spend_keeps_its_row_and_gets_the_signed_hex()
@Test fun a_legacy_spend_moves_to_the_signed_txid_with_its_note()
@Test fun storing_twice_finds_the_moved_row()
@Test fun a_forgotten_payment_is_refused()
@Test fun broadcast_starts_held_and_the_payment_can_go_again()
```

- [ ] **Step 2:** FAIL. **Step 3:** implement per spec decisions 7 and 8. **Step 4:** PASS. **Step 5:** commit `ADD: store a scanned signed PSBT and broadcast it over Tor`.

### Task 5: Screens and entry points

**Files:** Create `SignedPsbtScreen.kt`; modify `QrScanOverlay.kt`, `SendPsbtStep.kt`, `SendScreen.kt`, `StoredPsbtScreen.kt`.

- `QrScanOverlay.kt`: extract `QrFrameScanner(onText: (String) -> Boolean, modifier)` (true stops scanning); `QrScanOverlay(onResult)` = `QrFrameScanner({ onResult(it); true })`.
- `SignedPsbtScreen.kt`:
  ```kotlin
  internal class SignedPsbtTarget(val storedTxid: String, val to: SendRecipient, val psbtHex: String)
  @Composable internal fun SignedPsbtFlow(runtime: PeersRuntime, target: SignedPsbtTarget, db: Database, onDone: () -> Unit, onBack: () -> Unit)
  ```
  Scan step (`SendStepFrame` "Scan signed PSBT", camera via `QrFrameScanner`, progress text and `LinearProgressIndicator`, note line, `TextAction` "Paste" / "Start over"); on `Done` run `finalizeSignedPsbt` on `Dispatchers.Default`; `Failed` → `scan.reset(reason)`. Review step (`ToPill`, `AmountHero(paidTo)`, `AddressGroups`, fee and total rows, `TintedPillButton("Broadcast")`, error line); Broadcast tap: `storeSignedPsbt` on `Dispatchers.Default` then `startSignedPsbtBroadcast` on Main; `Started(id)` → `BroadcastScreen(runtime, id, onClose = onDone)`. Each step is wrapped in `OverlayBackEffects(enabled = true, onDismiss = …)` so Back/edge-swipe go one step back.
- `SendPsbtStep`: new optional `onScanSigned: (() -> Unit)?`; when set, a `TintedPillButton("Scan signed PSBT")` above Done and the new helper text.
- `SendScreen.SendPsbt` and `StoredPsbtScreen`: `var scanning by remember { mutableStateOf(false) }`; scanning → `SignedPsbtFlow(..., target = SignedPsbtTarget(txid, to, psbtHex), onDone = flow::finish / onBack, onBack = { scanning = false })`.

- [ ] **Step 1:** `./gradlew :shared:compileKotlinJvm --offline` passes; **Step 2:** full checks; **Step 3:** commit `ADD: Scan signed PSBT on the PSBT step, with review and broadcast`.

### Task 6: Review loop and verification

- [ ] Run the review-loop skill until a round has no major issues; fix findings with tests first.
- [ ] Run the full checks; compile Android (`:shared:compileDebugKotlinAndroid` or `:androidApp:assembleDebug --offline` if the SDK is present). iOS needs macOS (skip).
