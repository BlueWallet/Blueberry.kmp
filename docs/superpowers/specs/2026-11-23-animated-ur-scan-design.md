# Scan a signed PSBT back (animated BC-UR)

Date: 2026-11-23
Status: approved (conversation: "yes. don't forget about superpowers skill", replying to the proposal of a scan flow that collects animated UR frames and gives signed-PSBT import and broadcast)

## Goal

Close the watch-only loop. Today a watch-only wallet shows the unsigned PSBT as an animated `ur:crypto-psbt` QR and tells the user to broadcast from the signing device. After this change the user can scan the signed PSBT back:

- an animated (multi-part) BC-UR `ur:crypto-psbt` or `ur:psbt` QR,
- a single-part UR,
- a single QR holding the PSBT as base64 or hex (the "Paste" action accepts the same text from the clipboard).

The app checks that it is the same transaction it asked to sign, finalizes it, shows a short review and broadcasts it through the existing Tor broadcast path.

## Non-goals (follow-ups)

- Importing an account from `ur:crypto-account` / `ur:crypto-output` QRs.
- BBQr (`B$...`) frames.
- Multisig or any script other than our single-sig inputs (P2PKH, P2SH-P2WPKH, P2WPKH, P2TR key path).
- PSBT v2 (BIP370), raw signed transaction hex, signing-device-specific formats.
- Merging several partially signed PSBTs.

## Decisions

1. **Incremental decoder in `wallet`.** A stateful `UrDecoder(acceptedTypes)` takes one scanned string at a time, in any order, with duplicates. It reuses the in-house bytewords/CBOR/fountain code already in `EncodePsbtUr.kt` (no new dependency). `decodeCryptoPsbtUrFragments` becomes a thin wrapper over it, so the existing golden encoder test keeps covering both directions.
2. **Decoder outcomes.** Each `receive(text)` returns one of:
   - `Progress(percent)`: accepted (new or duplicate part),
   - `Complete(message)`: the CBOR message is reassembled and its CRC32 matches,
   - `Ignored(reason)`: not a UR, a UR of a type we don't accept, a type other than the one already locked in, a corrupt frame (bad bytewords checksum or CBOR), or a part of a different animation (other length, size or checksum). Ignored parts never change state, so a stray QR can't break a scan in progress,
   - `Failed(reason)`: reassembly produced a message whose checksum doesn't match. Only `reset()` recovers.
   `reset()` clears everything. After `Complete`, every further `receive` returns the same `Complete`.
3. **Types.** For PSBTs the decoder accepts `crypto-psbt` (BCR-2020-006) and `psbt` (newer registry name). Both wrap the PSBT in a CBOR byte string. The first accepted part locks the type.
4. **Progress.** `max(recovered fragments / fragment count, min(0.99, distinct parts / (fragment count × 1.75)))`, shown as a whole percent. The second term is `@ngraveio/bc-ur`'s estimate, so fountain-mixed frames move the bar before any fragment is recovered. Complete is 100.
5. **Plain PSBT text.** A scanned or pasted string that is not a UR is read as base64 (standard alphabet, trailing `=` padding optional) or hex, and must start with the PSBT magic `70736274ff`. Whitespace is trimmed.
6. **Check against what we asked to sign.** `finalizeSignedPsbt(unsignedPsbtHex, signedBytes)`:
   1. parses the scanned PSBT (bitcoin-kmp `Psbt.read`),
   2. requires its unsigned transaction txid to equal ours. Same unsigned txid means the same inputs, outputs, amounts and fee, so nothing on the review can differ from what was built,
   3. builds each input's final scriptSig/witness: the signer's own final fields when it finalized the input, otherwise from its single-sig signature: P2WPKH `[sig, pubkey]` witness, P2SH-P2WPKH the same witness plus the redeem script `0014<hash160(pubkey)>`, P2PKH `<sig> <pubkey>` scriptSig, P2TR key path `[schnorr sig]` witness. The spent coins always come from our unsigned PSBT, so a signer that strips UTXOs, redeem scripts or derivation data still works. (`Psbt.combine` is not used: bitcoin-kmp treats a finalized input merged with our derivation data as not finalized.) The signing fields (partial sigs 0x02, final scriptSig 0x07, final witness 0x08, taproot key sig 0x13) are read off the raw input maps, because `Psbt.read` drops them from an input with no UTXO or with final scripts beside other fields.
   4. verifies every input with `Transaction.correctlySpends` and standard script flags, so a wrong or missing signature fails here.
   5. requires every input's signature to be `SIGHASH_ALL` (ECDSA hash type byte `0x01`; taproot 64 bytes, or 65 ending in `0x01`), including inputs the signer finalized. `SIGHASH_NONE`, `SIGHASH_SINGLE` and any `ANYONECANPAY` signature also verify, but leave outputs or inputs free to change, so the transaction broadcast would not be the one on the review. They fail as bad signatures.
   Errors (never echo PSBT bytes):
   - `This QR isn't a PSBT.`
   - `This PSBT is for a different transaction.`
   - `This PSBT isn't fully signed. Sign every input on your signing device.`
   - `The signatures in this PSBT don't check out. Sign it again on your signing device.`
7. **Storage.** The watch-only send is already stored as an unsigned `sends` row keyed by the unsigned txid, holding its coins. On Broadcast:
   - segwit-only spends keep their txid, so the row's `txHex` becomes the signed hex;
   - legacy and nested-segwit spends get a new txid, so the row is moved to the signed txid (same destination, coins and created time) and the payment note and change names follow it (`movePaymentLabel`, the same move `settleBySpender` does when such a spend confirms).
   If the row is gone (forgotten, or confirmed meanwhile) the broadcast is refused with `This payment is no longer waiting to be signed.` The same holds when storing itself settles the row because the signed txid is already in a block: `storeSignedPsbt` re-checks the row after hydrating and returns false.
8. **Broadcast path.** After storing, broadcast through `sendStoredAgain` (the "Broadcast again" path in transaction details). It already checks the coins are still ours and unreserved, and starts the job as handed off (`PriorRun.Held`): the signing device may have published the same transaction, so a node refusal or failure never drops the stored payment or frees its coins. Status is shown on the existing `BroadcastScreen`. Once stored as signed, transaction details show "Broadcast again" instead of "Show PSBT".
9. **UI entry points.** The PSBT step (`SendPsbtStep`) gets a "Scan signed PSBT" button above Done. It appears in both places that show that step: the watch-only exit of Send, and "Show PSBT" in a pending payment's details. The helper text becomes "This wallet can't sign. Sign the PSBT on your signing device, then scan the signed PSBT here to broadcast it."
10. **Scan screen.** Camera preview that keeps scanning across frames, a line with progress ("Scanning animated QR… 40%" / "Point the camera at the signed PSBT QR"), the last ignore or error reason, "Paste" (clipboard text through the same parser) and "Start over" (reset). On a complete PSBT the check runs off the main thread; a check error shows on the scan screen and resets the decoder.
11. **Review screen.** Recipient label, amount hero, destination address groups, network fee and total leaving the wallet, and a Broadcast button. Back returns to the PSBT QR.
- **Review-loop decisions.** A PSBT that fails its check is remembered: holding the same QR in view keeps its
  reason on screen instead of re-checking it every frame. That reason, like a checksum failure, stays up on
  every following frame until a different PSBT completes or the user taps Start over, including frames from a stray QR.
  Start over also cancels a signature check in flight. The review's amount is the first output paying the
  destination: `buildSend` puts the payment before change, which matters when the destination is our change address. On the send flow, the scan target is captured when
  "Scan signed PSBT" is tapped, so the scan, review and broadcast survive the build dropping once a moved
  legacy/nested spend makes its coins look spent. From tx details, finishing a broadcast whose txid moved opens
  the details of the new txid in place of the old one.
- **Review round 2 decisions (GLaDOS, 2026-11-24).**
  - Storing and starting the broadcast run as one non-cancellable step (`broadcastSignedPsbt`). Back and the edge
    swipe do nothing while it runs, and a throw from storing becomes a refusal on the review instead of leaving it stuck.
    Once the signed row is stored, leaving the review exits the flow to the signed txid (as Done does), since the
    payment is no longer waiting to be signed.
  - A moved row and its note and change names commit in one transaction. When the row is already on the signed
    txid (a retry after a crash), the note and change names are still copied from the unsigned txid.
  - A broadcast that starts held and that every node refuses keeps its row and coin hold, but shows the refusal
    ("Not accepted", with the node's reason) instead of "Sent".
  - A started signed-PSBT broadcast records Tor as its broadcast method, like a hot-wallet send.
  - Transaction details only say a signed payment "was given to Bitcoin nodes" once a broadcast of it is recorded
    (a Tor record or a broadcast job). Otherwise they say it is signed and may not be published yet.
  - Animated URs are limited to 1,000 fragments (175 KB at our own 175-byte fragments, far beyond a single-sig
    PSBT), and the fountain decoder keeps at most 4 × fragment-count mixed parts, so one frame's work stays small
    enough for the camera thread.
  - An unexpected throw while checking a scanned PSBT shows `This QR isn't a PSBT.` instead of the exception text.
- **Device QA decisions (GLaDOS, 2026-11-24).** Offline, a signed-PSBT broadcast that reached no node
  (`given=0/3`, every Tor dial failed) showed "Sent · Given to Bitcoin nodes", and details said "Broadcast over
  Tor". Cause: `sendStoredAgain` started every stored payment with no live job as `PriorRun.Held`, so a run that
  failed without a hand-off became `success`/unacknowledged, and the Tor record was written as soon as the job started.
  - `sendStoredAgain` starts a run as held only on evidence that one of our runs gave nodes the transaction: an
    earlier job of it that finished as sent, or its Tor record. Otherwise (a signed PSBT's first broadcast, or one
    that never reached a node) it is an ordinary first run: Stop shows until a node has it, and a run that reaches
    no node, or that every node refuses, ends as "Not sent yet". The stored row, not the job, holds the coins, so
    that outcome frees nothing and Broadcast again stays.
  - A stored payment's Tor record is written when a run finishes with a node holding it, not when it starts. This
    replaces the round-2 decision above. It is the only proof that survives a restart that a node holds the payment,
    so a write that still fails after its retries stays due in the runtime and is written on the next wallet refresh.
  - Details say a signed payment "was given to Bitcoin nodes" on its Tor record or a job of it that finished as
    sent; a running or failed job no longer counts.
  - The broadcast screen offers no "Forget this transaction" for a failed run of a payment that is still stored:
    forgetting the job would leave the row and its coin hold behind. Forget this payment in details drops it.
  - Back on the review leaves the flow to the PSBT QR (decision 11), instead of returning to the scanner.
  - Not changed here: after a restart Home showed a pending self-send as −0.00010000 while details said
    −0.00000423. That row comes from `pendingWalletRows`/`inferPendingSendNetDelta` on master, not from this change.
- **Review round 4 decisions (GLaDOS and CodeRabbit, 2026-11-24).**
  - A stored payment's Tor record is written at hand-off, as a hot-wallet send's is, since the app can die while a
    node already holds the raw transaction; after a restart details then say it was given to nodes and offer no
    Forget. This replaces the Device QA decision above. A write that fails stays due like the one at the end. If the
    run then ends with no node holding it (every node refused, or none reached) and it started as a first run, so no
    earlier record existed, the record is deleted and leaves the due set, so the next run doesn't start held.
  - The runtime's hand-off and done hooks note the Tor record even when persisting the send throws.
  - The broadcast screen recomputes whether Forget shows whenever the wallet store refreshes, not only when the job
    phase changes, so a hot send's row dropped just after its failure is shown on the screen already open.
  - `broadcastSignedPsbt` remembers that the signed row committed, so a later throw whose status read also fails
    still says the payment was saved (`SIGNED_PSBT_NOT_STARTED`, `stored = true`).
- **Review round 5 decisions (GLaDOS and CodeRabbit, 2026-11-24).**
  - `DueTorNotes` holds one lock across its due set and the SQL that writes or deletes a note. Broadcast hooks and
    wallet refreshes run on different threads, so a refresh writing a due note can no longer upsert it after a
    refused first run's drop deleted it.
  - A dropped note whose delete fails is kept as a pending deletion and deleted again on every wallet refresh, the
    same way a failed write stays due. A later run a node holds makes the note due again and cancels that deletion.

## Units

- `wallet/UrDecoder.kt`: `UrDecoder`, `UrReceive`, `PSBT_UR_TYPES`, `psbtFromUrMessage`.
- `wallet/EncodePsbtUr.kt`: helpers the decoder needs become `internal`; `decodeCryptoPsbtUrFragments` delegates.
- `wallet/SignedPsbt.kt`: `psbtBytesFromText`, `finalizeSignedPsbt`, `SignedPsbt`, `SignedPsbtCheck`.
- `shared/SignedPsbtScan.kt`: UI-free scan state (`SignedPsbtScan.accept(text)`), wiring UR and plain text.
- `shared/SignedPsbtBroadcast.kt`: `storeSignedPsbt` (row update or move) and `broadcastSignedPsbt`.
- `shared/QrScanOverlay.kt`: `QrFrameScanner(onText: (String) -> Boolean)`; `QrScanOverlay` wraps it.
- `shared/SignedPsbtScreen.kt`: Scan → Review → Broadcast composables.
- `shared/SendPsbtStep.kt`, `SendScreen.kt`, `StoredPsbtScreen.kt`: the button and hosting.

## Testing

- `UrDecoderTest`: BlueWallet's 2-part `crypto-psbt` vector in order, reversed, with duplicates; a fountain-only vector generated with `@ngraveio/bc-ur` (parts 7..19 of a 6-fragment animation) decodes without any simple part; uppercase QR alphanumeric form; single-part; `ur:psbt`; foreign type, mixed type, other animation, corrupt frame are ignored without losing progress; progress grows and hits 100; reset; round trips with our encoder at several capacities in shuffled order.
- `SignedPsbtTest`: watch-only PSBTs for the `abandon … about` xpub (P2PKH), ypub (P2SH-P2WPKH), zpub (P2WPKH) and BIP86 tr (P2TR) are signed with the matching private keys through bitcoin-kmp (`Psbt.sign`, partial signatures), round-tripped through UR, finalized and extracted; a signer-finalized PSBT (P2TR key path included) also passes; different transaction, unsigned, half-signed, wrong-key and non-`SIGHASH_ALL` (partial or signer-finalized) cases give the errors above; base64 (padded or not) and hex text parse; non-PSBT text is rejected.
- `SignedPsbtScanTest`: UR frames reach 100 and yield the PSBT; base64 text completes at once; ignored text keeps progress; reset.
- `SignedPsbtBroadcastTest`: a segwit spend keeps its row key with the signed hex; a legacy spend moves to the new txid with its note; a missing row is refused; a signed txid already in a block (segwit and legacy) is refused after storing; the broadcast job starts as a first run (not held) and `storedSendCanGoAgain` becomes true; a start that throws after the row is stored says it was saved but couldn't start; a Tor note that keeps failing to write is written on the next wallet refresh; a hand-off writes the Tor note (kept due if the write fails), a first run every node then refuses deletes it and the next run starts as a first run, while an earlier held run's note stays; the done hook keeps the note due when persisting the send throws; a throw after the row commits whose status read also fails still says it was saved.
- `SendHoldsTest`: the broadcast screen's Forget watcher asks again on the wallet refresh that follows a refused send's dropped row, and then offers Forget.
- Compose screens are not unit tested (repo convention); they are thin over the units above.
