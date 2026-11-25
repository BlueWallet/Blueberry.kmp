# Import a watch-only account from a BC-UR accounts QR

Date: 2026-11-25
Status: approved (conversation: "Approved, write the spec", replying to the design that extends the in-house UR stack with an accounts parser, scans single and animated account QRs in onboarding and offers a picker of single-sig accounts)

## Goal

At onboarding, "Import wallet" → Scan accepts the account QR a hardware or desktop wallet shows (Keystone, Sparrow and others):

- BC-UR `ur:crypto-account` (BCR-2020-015), `ur:crypto-multi-accounts` (Keystone) and `ur:crypto-hdkey` (BCR-2020-007),
- single-part or animated (multi-part fountain) QRs.

Blueberry holds one wallet, so the user picks one account. Each single-sig Bitcoin account in the QR becomes a row (Legacy BIP44, Nested SegWit BIP49, Native SegWit BIP84, Taproot BIP86); with only one, there is no picker. The chosen account is imported as an output descriptor with its key origin, through the existing `parseWalletSecret`, then the existing Year step runs. The descriptor keeps the master fingerprint and keeps BIP86 as taproot (a bare zpub would lose both).

## Non-goals (follow-ups)

- `ur:account-descriptor`. BlueWallet doesn't read it either.
- `ur:crypto-output`, `ur:bytes` and BBQr (`B$...`) payloads.
- Multisig accounts (`m/45'`, `m/48'/…`, `sh`/`wsh` of a key, `multi`, `cosigner`), non-Bitcoin keys (coin type other than `0'`), testnet.
- Importing more than one account, or changing the account after import (Back on Year already forgets the secret).
- Paste of a UR string into the text field. Typed or pasted text keeps today's path.

## Decisions

1. **One UR stack, no new dependency.** The in-house bytewords/fountain/CBOR code from PR #88 (`EncodePsbtUr.kt`, `UrDecoder.kt`) is reused. `kotlinx-serialization-cbor` was considered and dropped: a new dependency that fights the tag-heavy registry format and duplicates code we have.
2. **CBOR reader grows to what the registry types need.** `CborReader` (now `private` in `EncodePsbtUr.kt`) becomes `internal` and gains: map and array headers, tags (major type 6), text strings (read and dropped), `true`/`false`, and `skip()` of any one item so unknown map keys (`name`, `note`, `use-info`, `children`, `device`, `version`, …) are passed over. Definite lengths only (what the registry encoders write); an indefinite length, an 8-byte length or nesting deeper than 16 fails the parse. A read past the end fails the parse.
3. **Parser.** New `wallet/UrAccounts.kt`:
   - `ACCOUNT_UR_TYPES = setOf("crypto-account", "crypto-multi-accounts", "crypto-hdkey")`.
   - `parseUrAccounts(type: String, message: ByteArray): List<UrAccount>`. It throws `IllegalArgumentException` when the CBOR doesn't have the type's shape. It returns the usable single-sig accounts in payload order, or an empty list when there are none or the payload is rejected (decision 6).
   - `data class UrAccount(scriptType: AddressScriptType, fingerprint: Long, path: KeyPath, xpub: String)` with `val descriptor: String`. `toString()` redacts `xpub` and `descriptor`, as `ParsedWalletSecret` does.
4. **CBOR shapes read.**

   | UR type | message | keys |
   |---|---|---|
   | `crypto-account` | map: `1` master fingerprint (uint32), `2` array of outputs | each output is script tags around one key |
   | `crypto-multi-accounts` | map: `1` master fingerprint, `2` array of tag-303 hdkeys, `3`.. device info (skipped) | no script tags |
   | `crypto-hdkey` | one untagged hdkey map | no script tags |

   Tags: 303 hdkey, 304 keypath, 306 EC key (not HD), 400 `sh`, 401 `wsh`, 403 `pkh`, 404 `wpkh`, 409 `tr`; any other script tag (e.g. 406 `multi`, 410 `cosigner`) is unsupported.
   hdkey map: `1` is-master, `2` is-private, `3` key data, `4` chain code, `6` origin (tag 304), `8` parent fingerprint; others skipped. Keypath map: `1` components as `[index, hardened]` pairs, `2` source fingerprint, `3` depth.
5. **A usable HD key** is a tag-303 hdkey that is not master and not private, with a 33-byte key starting `02`/`03`, a 32-byte chain code, an origin with at least one component, and a fingerprint: the payload's master fingerprint (`crypto-account`, `crypto-multi-accounts`), else the origin's source fingerprint. A missing parent fingerprint is `0` (it doesn't affect derivation).
6. **Which accounts are kept.** The script of a key is:
   - for `crypto-account`, its output's script tags: `pkh(K)`, `sh(wpkh(K))`, `wpkh(K)` or `tr(K)`;
   - for `crypto-hdkey` and `crypto-multi-accounts` (no script), its purpose: 44 → `pkh`, 49 → `sh(wpkh)`, 84 → `wpkh`, 86 → `tr`.

   A usable key with such a script is kept when its origin is exactly `purpose'/0'/account'`, all hardened, and the purpose matches the script (44 `pkh`, 49 `sh(wpkh)`, 84 `wpkh`, 86 `tr`). If depth is present it must be 3. When it is missing (Sparrow/Hummingbird), the three components are the depth. `use-info` is not consulted: the coin is the path's second component (BlueWallet's single-BIP84 vector carries `use-info` type 1 on a `0'` path).

   Everything else is skipped one by one:
   - a `crypto-account` output with any other script shape (`sh(K)`, `wsh(K)`, `sh(wsh(K))`, `multi`, `cosigner`, …), without looking at its key;
   - `m/45'` and `m/48'/…` keys; coin type `60'`/`607'` (ETH, TON) keys; testnet `1'`;
   - a purpose that doesn't match the script; depth other than 3;
   - in `crypto-hdkey` and `crypto-multi-accounts`, a key that isn't usable (Keystone ships ETH keys without chain codes).

   **One rejection, as in BlueWallet:** a `crypto-account` output with a supported script whose key is not a usable HD key (e.g. `pkh` of a bare EC key, tag 306) rejects the whole payload (empty list). The device described an account we can't fully watch, so we don't import part of it.
7. **Descriptor.** `UrAccount.xpub` is the account key encoded with mainnet `xpub` version bytes (bitcoin-kmp `ExtendedPublicKey(key, chainCode, depth = 3, path, parentFingerprint).encode(DeterministicWallet.xpub)`), whatever the script. `descriptor` is the script wrapper around `[fingerprint/path]xpub/<0;1>/*`, with the fingerprint as 8 lowercase hex digits, `'` hardened markers, and `#` plus `descriptorChecksum(...)`:
   `wpkh([73c5da0a/84'/0'/0']xpub6CatWdiZ…/<0;1>/*)#hpg6d6w2`. `parseDescriptor` accepts every one (xpub, depth 3, origin, multipath). Taproot is never written as a zpub.
8. **Decoder wrong-type text.** `UR_WRONG_TYPE` is `"This QR code isn't a PSBT."`, which is wrong at onboarding. `UrDecoder` gets an optional constructor parameter `wrongTypeReason: String = UR_WRONG_TYPE`. The accounts scan passes `UR_NOT_ACCOUNT = "This QR code isn't an account export."`. The PSBT scan is unchanged.
9. **Scan state.** New UI-free `shared/AccountsScan.kt`, fed one camera string at a time from one thread, like `SignedPsbtScan`:
   - `accept(text)` returns `AccountsScanState.Scanning(percent, note)`, `Accounts(list)` (non-empty) or `Text(text)`.
   - A repeat of the last text changes nothing.
   - Text starting `ur:` (any case) goes to `UrDecoder(ACCOUNT_UR_TYPES, UR_NOT_ACCOUNT)`. `Progress` and `Ignored` stay `Scanning` with the decoder's percent. `Failed` resets the decoder.
   - On `Complete`, `parseUrAccounts` runs. If it throws, the result is `UR_CORRUPT`; an empty list gives `"No single-sig Bitcoin accounts in this QR."`. Either way the decoder resets and the camera keeps reading. The last text is kept, so the same QR held in view isn't parsed again.
   - A failure reason (`Failed`, corrupt, no accounts) stays as the note while frames keep coming, until a scan completes or the user leaves the camera, as in `SignedPsbtScan`. `Ignored` reasons show only when there is no failure reason.
   - Text that isn't a UR, before any UR part was accepted, is `Text(text)` for today's path. After an animation started it is ignored with `UR_NOT_UR`, so a stray QR can't break the scan.
10. **Onboarding state.** `OnboardingState` gets `accounts: List<UrAccount> = emptyList()`, the picker's rows; `toString()` shows only its size. New events:
    - `ScannedAccounts(accounts)`: one account → `importValue = descriptor`, `busy`, effect `PersistImportedSecret(descriptor)`, the same as `SubmitImport` (Year follows on `PersistImportOk`). Several → `accounts = list`, no effect.
    - `PickAccount(account)`: only while `accounts` is not empty. Sets `importValue = descriptor`, clears `accounts`, `busy`, effect `PersistImportedSecret(descriptor)`.
    - `Back` on Import while the picker shows clears `accounts` and stays on Import. `ImportChanged` is ignored while the picker shows.
    - A persist failure shows the error on Import, as today.
11. **Onboarding UI** (`OnboardingApp.kt`):
    - Scan in `ImportEntry` uses `QrFrameScanner` with an `AccountsScan` (remembered while the camera is open) instead of the one-shot `QrScanOverlay`. Under the preview, one caption line: `"Scanning animated QR… 40%"` while percent is known, else the note, else `"Point the camera at a QR code"`.
    - `Text(text)` does what the overlay did: `ImportChanged(text)` and close the camera. `Accounts(list)` closes the camera and dispatches `ScannedAccounts(list)`.
    - `ImportWalletPage` shows the picker in place of the entry while `accounts` is not empty. Each row shows `scriptType.receiveLabel()` ("Native SegWit (BIP84)", …), the path (`m/84'/0'/0'`) and the fingerprint (`73c5da0a`). Rows keep payload order.
    - The first Native SegWit row (else the first row) starts selected and is highlighted. The page's Continue button imports it, and tapping any row imports that row (`PickAccount`).
    - The import helper text and `QrScanOverlay` (still used by Send) are unchanged.

## Units

- `wallet/EncodePsbtUr.kt`: `CborReader` internal, with map/array/tag/text/bool reads and `skip()`. `UrEncoder` internal, so tests can build animated account URs.
- `wallet/UrDecoder.kt`: `wrongTypeReason` parameter.
- `wallet/UrAccounts.kt`: `ACCOUNT_UR_TYPES`, `UR_NOT_ACCOUNT`, `UrAccount`, `parseUrAccounts`.
- `shared/AccountsScan.kt`: `AccountsScan`, `AccountsScanState`.
- `shared/onboarding/OnboardingFlow.kt`: `accounts`, `ScannedAccounts`, `PickAccount`, `Back` while picking.
- `shared/onboarding/OnboardingApp.kt`: the scanner caption and the picker.

## Testing (TDD, commonTest, real vectors, no mocks)

Expected descriptors come from the BlueWallet vectors (Appendix). BlueWallet reports each key with SLIP-132 version bytes (`zpub`/`ypub`/`Zpub`); Blueberry's descriptors take the same key with `xpub` version bytes. Each expected value below was computed both ways, from the CBOR fields and by re-versioning BlueWallet's expected key, and they match. The abandon keys equal the existing `ABANDON_BIP44_XPUB` / `ABANDON_BIP84_XPUB` / `ABANDON_BIP86_XPUB` in `TestVectors.kt`. The derived checksums reproduce the existing `PKH_ABANDON_DESCRIPTOR` (`#x4nu8kpc`) and `TR_ABANDON_DESCRIPTOR` (`#kjk9q86c`).

- `UrAccountsTest` (wallet):
  - Keystone `crypto-account`, outputs reordered `tr, wpkh, pkh, sh(wpkh)` (BlueWallet's "taproot output goes first"): four accounts in that order, descriptors per Appendix A.
  - Keystone `crypto-account` with head `…82`, outputs `wsh` (the wpkh output re-tagged `d90191`) and `tr`: only the `tr` account.
  - Keystone `crypto-account` with head `…82`, outputs `wpkh` and `pkhEcKey`: empty (rejected).
  - Keystone `crypto-multi-accounts`: exactly the four BTC accounts (Appendix B); the ETH and TON keys are dropped.
  - `crypto-hdkey`: each of the four BTC hdkeys from that payload (Appendix B, untagged maps) gives its one account, the fingerprint from the origin.
  - Sparrow/Hummingbird `crypto-account` (origins without depth or source fingerprint): `pkh` 44, `sh(wpkh)` 49 and `wpkh` 84 with fingerprint `37b5eed4`; the `sh` 45', `sh(wsh)` 48'/…/1' and `wsh` 48'/…/2' outputs are dropped (Appendix C).
  - BlueWallet single BIP84 `crypto-account` (`01ebda7d`) and the 3-account abandon `crypto-account` (`wpkh`, `sh(wpkh)`, `pkh` in that order) (Appendix D).
  - Malformed CBOR (truncated Keystone payload) throws.
  - Every descriptor above passes `parseWalletSecret` as `DESCRIPTOR`; its watch key has the account's script type, origin fingerprint and path, and the BIP86 ones are `P2TR`.
  - Animated round trip: the multi-accounts CBOR through `UrEncoder(…, "crypto-multi-accounts", 400)`, parts shuffled and uppercased, into `UrDecoder(ACCOUNT_UR_TYPES)`, completes; `parseUrAccounts` gives Appendix B. The single-part Sparrow and BlueWallet URs also go through `UrDecoder` in their tests.
- `UrDecoderTest`: a `ur:crypto-psbt` part in an accounts decoder is ignored with the given wrong-type reason.
- `AccountsScanTest` (shared): animated account frames progress and end in `Accounts`; a zpub text is `Text`; non-UR text after the first animated part is ignored and keeps progress; `ur:crypto-psbt` gives `UR_NOT_ACCOUNT`; the rejected payload gives the no-accounts note and the scan keeps going.
- `OnboardingFlowTest`: `ScannedAccounts` with one account persists its descriptor with no picker; with several it shows the picker and persists nothing; `PickAccount` persists the picked descriptor; `Back` while picking clears the picker and stays on Import.
- Compose screens are not unit tested (repo convention).

## Appendix: vectors

From BlueWallet `master` at `2432b458`. `tests/unit/ur-hardware-accounts.test.ts` was added in `05e9f539` ("REF: blue_modules/ur to TypeScript, fix UR account import bugs").

### A. Keystone 3 Pro `crypto-account` (abandon … about)

`tests/unit/ur-hardware-accounts.test.ts` lines 36–44 (from KeystoneHQ/keystone3-firmware `rust/apps/wallets/src/blue_wallet.rs`, `test_generate_crypto_account`). The message is `keystoneAccountHead` + outputs. BlueWallet's tests use the order `tr, wpkh, pkh, sh(wpkh)` and, for the 2-output cases, head `a2011a73c5da0a0282`.

```
keystoneAccountHead = a2011a73c5da0a0284
wpkh    = d90194d9012fa702f403582102707a62fdacc26ea9b63b1c197906f56ee0180d0bcf1966e1a2da34f5f3a09a9b0458204a53a0ab21b9dc95869c4e92a161194e03c0ef3ff5014ac692f433c4765490fc05d90131a20100020006d90130a301861854f500f500f5021a73c5da0a030307d90130a2018400f480f40300081a7ef32bdb
pkh     = d90193d9012fa702f403582103774c910fcf07fa96886ea794f0d5caed9afe30b44b83f7e213bb92930e7df4bd0458203da4bc190a2680111d31fadfdc905f2a7f6ce77c6f109919116f253d4344521905d90131a20100020006d90130a30186182cf500f500f5021a73c5da0a030307d90130a2018400f480f40300081a155bca59
sh_wpkh = d90190d90194d9012fa702f403582102f1f347891b20f7568eae3ec9869fbfb67bcab6f358326f10ecc42356bd55939d0458206eaae365ae0e0a0aab84325cfe7cd76c3b909035f889e7d3f1b847a9a0797ecb05d90131a20100020006d90130a301861831f500f500f5021a73c5da0a030307d90130a2018400f480f40300081a3d05ff75
tr      = d90199d9012fa702f403582103418278a2885c8bb98148158d1474634097a179c642f23cf1cc04da629ac6f0fb045820c61a8f27e98182314d2444da3e600eb5836ec8ad183c86c311f95df8082b18aa05d90131a20100020006d90130a301861856f500f500f5021a73c5da0a030307d90130a2018400f480f40300081a035270da
pkhEcKey (line 114, BCR-2020-010 pkh of a bare EC key) = d90193d90132a103582102c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5
wsh     = wpkh with its leading d90194 replaced by d90191 (lines 103–104)
```

Expected (BlueWallet lines 45–66, re-versioned to xpub):

```
tr([73c5da0a/86'/0'/0']xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteoGVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ/<0;1>/*)#kjk9q86c
wpkh([73c5da0a/84'/0'/0']xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3XyuvPEbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V/<0;1>/*)#hpg6d6w2
pkh([73c5da0a/44'/0'/0']xpub6BosfCnifzxcFwrSzQiqu2DBVTshkCXacvNsWGYJVVhhawA7d4R5WSWGFNbi8Aw6ZRc1brxMyWMzG3DSSSSoekkudhUd9yLb6qx39T9nMdj/<0;1>/*)#x4nu8kpc
sh(wpkh([73c5da0a/49'/0'/0']xpub6C6nQwHaWbSrzs5tZ1q7m5R9cPK9eYpNMFesiXsYrgc1P8bvLLAet9JfHjYXKjToD8cBRswJXXbbFpXgwsswVPAZzKMa1jUp2kVkGVUaJa7/<0;1>/*))#wfg3f37j
```

### B. Keystone 3 Pro `crypto-multi-accounts` (10 ETH, 4 BTC, 1 TON keys)

`tests/unit/ur-hardware-accounts.test.ts` lines 7–10 (from KeystoneHQ/keystone3-firmware `rust/apps/wallets/src/bitget.rs`, `test_generate_with_ton_keys`):

```
a5011a1250b6bc0291d9012fa802f403582103c4ad25226a98764f7b753b6c763be112c940182e0b6698944c9def253cec4676045820bf103cfabcab325a7463ab5c432a4042109279d6c3531c8c5ea712684cf31f3706d90130a30186182cf5183cf500f5021a1250b6bc030307d90130a2018400f480f40300081a63c5ea6709684b657973746f6e650a706163636f756e742e7374616e64617264d9012fa802f403582103c4ad25226a98764f7b753b6c763be112c940182e0b6698944c9def253cec4676045820bf103cfabcab325a7463ab5c432a4042109279d6c3531c8c5ea712684cf31f3706d90130a30186182cf5183cf500f5021a1250b6bc030307d90130a2018280f40300081a63c5ea6709684b657973746f6e650a756163636f756e742e6c65646765725f6c6567616379d9012fa602f403582103de2f443b5070e839d6a209ed1afaadfd43daf7bc610a1fefef201a3aca5350ee06d90130a3018a182cf5183cf500f500f400f4021a1250b6bc0303081a769cd41a09684b657973746f6e650a736163636f756e742e6c65646765725f6c697665d9012fa602f403582103a96bae34b46e82eee9c5beae52e446693b5c2e365714b223adea7caae47e1b7f06d90130a3018a182cf5183cf501f500f400f4021a1250b6bc0303081a998dec6209684b657973746f6e650a736163636f756e742e6c65646765725f6c697665d9012fa602f4035821029299ea371a1c6cdcc75b91d26322a2dbdfedb58285e33760a2af82cfee1a7a6e06d90130a3018a182cf5183cf502f500f400f4021a1250b6bc0303081ac49452d709684b657973746f6e650a736163636f756e742e6c65646765725f6c697665d9012fa602f4035821030f1808f972aed2b8e01d92b09b9c0d55b994592fb5599557e0d4828705204a4106d90130a3018a182cf5183cf503f500f400f4021a1250b6bc0303081a0823c44c09684b657973746f6e650a736163636f756e742e6c65646765725f6c697665d9012fa602f4035821021c885e79f19ba2658755952801366f00f5304db8597eb72a78a6886d8175037d06d90130a3018a182cf5183cf504f500f400f4021a1250b6bc0303081aef4e5bae09684b657973746f6e650a736163636f756e742e6c65646765725f6c697665d9012fa602f4035821037653d3a64a3d1fce16d5693e044e9aea644082371f4d31f6eb83096b5b84d97b06d90130a3018a182cf5183cf505f500f400f4021a1250b6bc0303081a364c571b09684b657973746f6e650a736163636f756e742e6c65646765725f6c697665d9012fa602f403582103ded46d3bc515112c1c99b5f5b5f6c4abc76cc40d98ba4c1808c97038fe976ff606d90130a3018a182cf5183cf506f500f400f4021a1250b6bc0303081a456759fc09684b657973746f6e650a736163636f756e742e6c65646765725f6c697665d9012fa602f403582102447d94e1937819a79a0ef56cd7b7a29de7c80a45b34480ef0b593083e418557906d90130a3018a182cf5183cf507f500f400f4021a1250b6bc0303081a09f362d709684b657973746f6e650a736163636f756e742e6c65646765725f6c697665d9012fa602f403582103d94fb59028252ab9353ee376e868cfd59e9d0bc24ef35fe1b06310cd0e4e908b06d90130a3018a182cf5183cf508f500f400f4021a1250b6bc0303081a4e09767009684b657973746f6e650a736163636f756e742e6c65646765725f6c697665d9012fa602f4035821034d53b1c2630e12b853b96d70fb671363d5d4ec9406f722b4deea1f2a9eb6e76406d90130a3018a182cf5183cf509f500f400f4021a1250b6bc0303081ac418564209684b657973746f6e650a736163636f756e742e6c65646765725f6c697665d9012fa702f40358210246696468fa5c46647bdd186f568983e2a26773dc5b4c174790fee7ef9b9a7c9904582051872c7bb8270a7655ae15d738bb21379962ff11fb048b55630395afcfef442806d90130a30186182cf500f500f5021a1250b6bc030307d90130a2018280f40300081ab5d6f45009684b657973746f6e65d9012fa702f403582103ab6a1908b437179b914dc3cf4b829d7387adf60fdee483e9c748d5ee79ba4be4045820bad8f929afd65c4b3ff78db1a386062374f2d2e6552e9fdc59aa7dd9b5db6e2c06d90130a301861831f500f500f5021a1250b6bc030307d90130a2018280f40300081a197077c809684b657973746f6e65d9012fa702f40358210262ba42544acc4223c74511b37bae54de22565f25f8034f5af8aa10a04c3621b9045820de47dd17679605b84abf439bdf5477c6cb94ff5a541d3cb1734afebfa3d3fdb906d90130a301861854f500f500f5021a1250b6bc030307d90130a2018280f40300081a9f6b7e4b09684b657973746f6e65d9012fa702f403582103e0db6ca7c71e72a40091141b9dc4596ab3a9192ac41eebe1f2b048c654423ecb045820a75805b89c5079ba74e9002c3e587f7c97ea8018ab1d5245638a45de3c845e0b06d90130a301861856f500f500f5021a1250b6bc030307d90130a2018280f40300081a0484004d09684b657973746f6e65d9012fa2035820895881fe5bab8b88e450055ff6916bf2bf41bc315325cee7975f33069beb079c06d90130a20186182cf519025ff500f5021a1250b6bc036e4b657973746f6e6520332050726f047828626339633466373135376165363937643433346163383536323332306564353335653737373638300565312e372e30
```

The four BTC hdkeys (untagged maps sliced out of the payload above, the `crypto-hdkey` messages):

```
44: a702f40358210246696468fa5c46647bdd186f568983e2a26773dc5b4c174790fee7ef9b9a7c9904582051872c7bb8270a7655ae15d738bb21379962ff11fb048b55630395afcfef442806d90130a30186182cf500f500f5021a1250b6bc030307d90130a2018280f40300081ab5d6f45009684b657973746f6e65
49: a702f403582103ab6a1908b437179b914dc3cf4b829d7387adf60fdee483e9c748d5ee79ba4be4045820bad8f929afd65c4b3ff78db1a386062374f2d2e6552e9fdc59aa7dd9b5db6e2c06d90130a301861831f500f500f5021a1250b6bc030307d90130a2018280f40300081a197077c809684b657973746f6e65
84: a702f40358210262ba42544acc4223c74511b37bae54de22565f25f8034f5af8aa10a04c3621b9045820de47dd17679605b84abf439bdf5477c6cb94ff5a541d3cb1734afebfa3d3fdb906d90130a301861854f500f500f5021a1250b6bc030307d90130a2018280f40300081a9f6b7e4b09684b657973746f6e65
86: a702f403582103e0db6ca7c71e72a40091141b9dc4596ab3a9192ac41eebe1f2b048c654423ecb045820a75805b89c5079ba74e9002c3e587f7c97ea8018ab1d5245638a45de3c845e0b06d90130a301861856f500f500f5021a1250b6bc030307d90130a2018280f40300081a0484004d09684b657973746f6e65
```

Expected (BlueWallet lines 12–34, re-versioned to xpub; the same four for each `crypto-hdkey`):

```
pkh([1250b6bc/44'/0'/0']xpub6CzHiXpZKvPYnCwnd6qq511yn8B63yB8rCtxsY4azeuCxPYJn6B7KNEa6sDCkNAj54H5AW3zYY8QLSzG6qe7SZBvKW3ighdPGTYQgDXxfHQ/<0;1>/*)#vxkfgn4x
sh(wpkh([1250b6bc/49'/0'/0']xpub6BqcZUPst99NuHmP1TNcCXftBBUjf87UAgZYfGYLKnafbhSo9rjoQoVhXDYwcf4Bt7wv2CszAiBXfrhgx5yoJNo8qe1FVQPNyTEtsaWJp52/<0;1>/*))#c8wth407
wpkh([1250b6bc/84'/0'/0']xpub6CpjN9cV2eSSHvzA5113pRqD5qaWRhUXS7ABs5AqGiau3BbAnW2fx1JwEEwn9ugVAgx6vbpXAKEjQbKjYHHPCjaxHEwyfLcUvwxjbaBEPRe/<0;1>/*)#c5tjhvqc
tr([1250b6bc/86'/0'/0']xpub6BghCcrqiqZKVhbMggpicCo9cziXGGyde4uW2adRcH6aLNYYPGL6vQRYS64pAp1cgCidiZ7zwGTHN2NmF4aJWijenLYmSeTVsyrauhWJDjA/<0;1>/*)#csq8lhzg
```

### C. Sparrow/Hummingbird `crypto-account` (origins without depth)

`tests/unit/ur-hardware-accounts.test.ts` line 122:

```
ur:crypto-account/oeadcyemrewytyaolntaadmutaaddloxaxhdclaxwmfmdeiamecsdsemgtvsjzcncygrkowtrontzschgezokstswkkscfmklrtauteyaahdcxiehfonurdppfyntapejpproypegrdawkgmaewejlsfdtsrfybdehcaflmtrlbdhpamtaaddyoyadlncsdwykaeykaeykaycynlytsnyltaadmhtaadmwtaaddloxaxhdclaostvelfemdyynwydwyaievosrgmambklovabdgypdglldvespsthysadamhpmjeinaahdcxntdllnaaeykoytdacygegwhgjsiyonpywmcmrpwphsvodsrerozsbyaxluzcoxdpamtaaddyoyadlncsehykaeykaeykaycypdbskeuytaadmwtaaddloxaxhdclaxzcfxeegdrpmogrgwkbzctlttweadkiengrwlhtprremouoluutqdpfbncedkynfhaahdcxjpwevdeogthttkmeswzcolcpsaahcfnshkhtehytclmnteatmoteadtlwynnftloamtaaddyoyadlncsghykaeykaeykaycybthlvytstaadmhtaaddloxaxhdclaxhhsnhdrpftdwuocntilydibehnecmovdfekpjkclcslasbhkpawsaddmcmmnahnyaahdcxlotedtndfymyltclhlmtpfsadscnhtztaolbnnkistaedegwfmmedreetnwmcycnamtaaddyoyadlfcsdpykaycyemrewytytaadmhtaadmetaaddloxaxhdclaxdwkswmztpytnswtsecnblfbayajkdldeclqzzolrsnhljedsgminetytbnahatbyaahdcxkkguwsvyimjkvwteytwztyswvendtpmncpasfrrylprnhtkblndrgrmkoyjtbkrpamtaaddyoyadlocsdyykaeykaeykadykaycyhkrpnddrtaadmetaaddloxaxhdclaohnhffmvsbndslrfgclpfjejyatbdpebacnzokotofxntaoemvskpaowmryfnotfgaahdcxdlnbvecentssfsssgylnhkrstoytecrdlyadrekirfaybglahltalsrfcaeerobwamtaaddyoyadlocsdyykaeykaeykaoykaycyhkrpnddrgdaogykb
```

Outputs: `pkh` 44', `sh(wpkh)` 49', `wpkh` 84', `sh` 45', `sh(wsh)` 48'/0'/0'/1', `wsh` 48'/0'/0'/2'. BlueWallet keeps all six (lines 124–131, the last three as `Zpub` multisig); Blueberry keeps the first three:

```
pkh([37b5eed4/44'/0'/0']xpub6CnQkivUEH9bSbWVWfDLCtigKKgnSWGaVSRyCbN2QNBJzuvHT1vUQpgSpY1NiVvoeNEuVwk748Cn9G3NtbQB1aGGsEL7aYEnjVWgjj9tefu/<0;1>/*)#vxycy4eh
sh(wpkh([37b5eed4/49'/0'/0']xpub6CtR1iF4dZPkEyXDwVf3HE74tSwXNMcHtBzX4gwz2UnPhJ54Jz5unHx2syYCCDkvVUmsmoYTmcaHXe1wJppvct4GMMaN5XAbRk7yGScRSte/<0;1>/*))#xd5r4mrt
wpkh([37b5eed4/84'/0'/0']xpub6BkU445MSEBXbPjD3g2c2ch6mn8yy1SXXQUM7EwjgYiq6Wt1NDwDZ45npqWcV8uQC5oi2gHuVukoCoZZyT4HKq8EpotPMqGqxdZRuapCQ23/<0;1>/*)#nlexnw55
```

### D. Older BlueWallet `crypto-account` vectors

`tests/unit/watch-only-wallet.test.js` line 1044 (single BIP84; BlueWallet expects `zpub6qT7amLc…`, fingerprint `01EBDA7D`, path `m/84'/0'/0'`):

```
UR:CRYPTO-ACCOUNT/OEADCYADWMTNKIAOLYTAADMWTAADDLOSAOWKAXHDCLAXMDRPFXWKHPTPNEWEVAWKYNFPJEDEMNJKAEGHCFQZLKUOTPLRIHMEFRTECWGRVWWDAAHDCXMHIODYPYLEAXZOGRPKEYPTBGBWGWDWHPZEIMVDBAAOIEVEWLZEGRBKRNFTHFAMMOAHTAADEHOEADADAOAEAMTAADDYOTADLNCSGHYKAEYKAEYKAOCYADWMTNKIAXAXATTAADDYOEADLRAEWKLAWKAXAEAYCYBGHFLOPACMIOWZLB
```

```
wpkh([01ebda7d/84'/0'/0']xpub6BnayRznWfa19UPEPT8H9BYCoFxrvaFCfUSX3MYWk84cm6dZfqUZcxYyYreexRLcGAx7qpQzJvqanav7D7QKRVP6cZwoWAvdbCbPe5QJpUF/<0;1>/*)#aksppjg0
```

`tests/unit/watch-only-wallet.test.js` line 1125 (abandon … about; BlueWallet expects zpub 84, ypub 49, xpub 44, fingerprint `73C5DA0A`, in that order):

```
UR:CRYPTO-ACCOUNT/OEADCYJKSKTNBKAOLSTAADMWTAADDLONAXHDCLAOJOKNIDZCPSSAJTPTRPFRCECFKKAMYKJTVTCSBTBDTKCFIYVYOETNEEYKWFNBNYNDAAHDCXGEGUNBPYCLRHUOMDLNNSGLMOOYHSCFGLAXRTWSFHYKADGESWMOWKEOSSKOGHMHZTAMTAADDYOTADLNCSGHYKAEYKAEYKAOCYJKSKTNBKAXAXATTAADDYOYADLRAEWKLAWKAYCYKBWFDNUYTAADMHTAADMWTAADDLONAXHDCLAOWNWFFLLDCWCXYLHFMNPLFMSOLNNERSRPKGSGRPWFHDEYJLBEWPSSCNHFRYGOMUNTAAHDCXJTPKVLIHPLBABKBKPYLREYHHZEKETSJZFRMHMHECYALDVDTEWNROFLPTNBKKKBSBAMTAADDYOTADLNCSEHYKAEYKAEYKAOCYJKSKTNBKAXAXATTAADDYOYADLRAEWKLAWKAYCYFSAHZMKPTAADMUTAADDLONAXHDCLAXKTGSMEBSTKATZSMTLOJTOSMWWTTLSGWENYZEDYQZGRLSYLVOBWRKMOMUBAKIWKRYAAHDCXFSOXRFCFBKDSLABYCAEHZSURUOMHHEDRLBJZVDKEJLBENLCFBYJLDAFSFXFYGMCFAMTAADDYOTADLNCSDWYKAEYKAEYKAOCYJKSKTNBKAXAXATTAADDYOYADLRAEWKLAWKAYCYBZHPSGHKSOPAJSLN
```

```
wpkh([73c5da0a/84'/0'/0']xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3XyuvPEbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V/<0;1>/*)#hpg6d6w2
sh(wpkh([73c5da0a/49'/0'/0']xpub6C6nQwHaWbSrzs5tZ1q7m5R9cPK9eYpNMFesiXsYrgc1P8bvLLAet9JfHjYXKjToD8cBRswJXXbbFpXgwsswVPAZzKMa1jUp2kVkGVUaJa7/<0;1>/*))#wfg3f37j
pkh([73c5da0a/44'/0'/0']xpub6BosfCnifzxcFwrSzQiqu2DBVTshkCXacvNsWGYJVVhhawA7d4R5WSWGFNbi8Aw6ZRc1brxMyWMzG3DSSSSoekkudhUd9yLb6qx39T9nMdj/<0;1>/*)#x4nu8kpc
```
