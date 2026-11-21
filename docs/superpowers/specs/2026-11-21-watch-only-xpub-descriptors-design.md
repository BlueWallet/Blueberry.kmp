# Watch-only xpub, ypub, descriptors and taproot

Date: 2026-11-21
Status: approved (conversation: "let's add support for xpub, ypub, descriptors and taproot")

## Goal

Import a watch-only HD account from any of:

- an account **xpub** (BIP44, P2PKH),
- an account **ypub** (BIP49, P2SH-P2WPKH),
- an account **zpub** (BIP84, P2WPKH, unchanged),
- a single-sig **output descriptor**: `pkh(KEY)`, `sh(wpkh(KEY))`, `wpkh(KEY)` or `tr(KEY)`.

`tr(KEY)` gives a BIP86-style taproot key-path watch. Sync, coins, Receive and "Show PSBT to sign" work the same as the zpub watch today.

## Non-goals

- Multisig, `wsh(...)`, `sh(multi(...))`, `sortedmulti`, miniscript, `combo`, `addr`, `raw`.
- Taproot script trees (`tr(KEY,{...})`).
- Private keys inside descriptors, testnet keys (`tpub`/`upub`/`vpub`).
- Hardened steps after the key, single-chain descriptors without a change chain.
- Importing a signed PSBT, multiple wallets, a watch-only badge.
- Label sync and Private Send for watch-only wallets stay blocked.

## Decisions

1. **One model for every extended-key watch.** `WatchKey(account, scriptType, origin?)` replaces the zpub-only branches in derivation, change picking, Receive and PSBT building. xpub, ypub, zpub and descriptors all parse into it.
2. **Prefix picks the script for bare keys.** xpub → P2PKH (`m/44'/0'/0'`), ypub → P2SH-P2WPKH (`m/49'/0'/0'`), zpub → P2WPKH (`m/84'/0'/0'`). An xpub exported for a segwit account will show legacy addresses; such users should paste a descriptor.
3. **Account-level keys only.** Every key (bare or inside a descriptor) must be depth 3, like zpub today.
4. **Descriptor key.** `xpub`, `ypub` or `zpub` are accepted inside a descriptor (only the key bytes matter; the wrapper picks the script).
5. **Descriptor chains.** Accepted suffixes: none (account key, watches `/0/*` and `/1/*`), `/<0;1>/*`, or a two-line paste of `/0/*` and `/1/*` with the same wrapper, key and origin.
6. **Checksum.** BIP380 `#checksum` is optional; a wrong one is rejected. Each line of a two-line paste is checked on its own.
7. **Key origin.** `[fingerprint/path]` is optional. Its path length must equal the key depth (3). Hardened markers `'`, `h`, `H` are accepted.
8. **Taproot.** `tr(KEY)` watches `P2TR(BIP86 tweak of KEY/chain/i)`, the same derivation the mnemonic BIP86 path uses.

## Data model

```kotlin
data class KeyOrigin(val fingerprint: Long, val path: KeyPath)

data class WatchKey(
    val account: DeterministicWallet.ExtendedPublicKey,
    val scriptType: AddressScriptType,
    val origin: KeyOrigin? = null,
)

enum class WalletSecretKind { MNEMONIC, XPUB, YPUB, ZPUB, DESCRIPTOR, WIF, ADDRESS }
fun parseWatchKey(value: String): WatchKey      // XPUB/YPUB/ZPUB/DESCRIPTOR values
fun ParsedWalletSecret.hdScriptTypes(): List<AddressScriptType>   // mnemonic: all four, watch key: its one type
```

`WatchWalletKind` stays `BIP84` for all HD wallets (it already means "HD", including mnemonic).

Derived `WatchAddress.path` is `<accountPath>/<chain>/<index>`, where `accountPath` is the origin path when present (`m/86'/0'/0'`), else the script's standard BIP44/49/84/86 path.

## Persistence compatibility

- `wallet_secret` keeps the original text (trimmed; a two-line descriptor keeps both lines, each trimmed, joined by `\n`). The descriptor text is the persisted origin; no new keys.
- Existing mnemonic, zpub, WIF and address secrets parse to the same kind and value as before.
- Gap windows already live under per-script keys (`watch_external_p2pkh`, ...). A watch key uses its own script's keys, so nothing collides; a zpub keeps using the P2WPKH keys as today.

## PSBT origin handling

- No origin (bare xpub/ypub/zpub or descriptor without `[...]`): fingerprint = the account key's own fingerprint, path = `chain/index`. Same as zpub today.
- With origin: fingerprint = origin master fingerprint, path = origin path + `chain/index`.
- P2PKH, P2SH-P2WPKH, P2WPKH inputs: `PSBT_IN_BIP32_DERIVATION`; P2SH-P2WPKH also gets its redeem script.
- P2TR inputs: `PSBT_IN_TAP_INTERNAL_KEY` and `PSBT_IN_TAP_BIP32_DERIVATION` (no leaf hashes), not the ECDSA derivation field (BIP371). This applies to mnemonic P2TR inputs too.
- Outputs paying one of our HD addresses (change) get the same derivation fields (`PSBT_OUT_BIP32_DERIVATION`, or tap internal key + tap derivation for P2TR, plus redeem script for P2SH-P2WPKH). This lets signers recognise change (issue #62).
- Change for a watch key comes from that key's internal chain.

## Error messages

| Input | Message |
| --- | --- |
| xpub/ypub/zpub not depth 3 | `<prefix> must be account-level (m/44'/0'/0')` (path for that prefix) |
| broken xpub/ypub/zpub | `invalid <prefix>` |
| tpub/upub/vpub, *prv | `only mainnet account xpub, ypub or zpub is supported` |
| multisig / sortedmulti | `multisig descriptors are not supported` |
| other wrappers (wsh, sh(pkh), combo, addr, raw) | `only pkh, sh(wpkh), wpkh and tr descriptors are supported` |
| `tr(KEY,{...})` | `taproot script trees are not supported` |
| private key in descriptor | `descriptors with private keys are not supported` |
| testnet key in descriptor | `only mainnet descriptor keys are supported` |
| hex/WIF key in descriptor | `descriptor key must be an xpub` |
| hardened step after key | `hardened derivation after the xpub is not supported` |
| other suffix | `descriptor path must be /<0;1>/* or none` |
| lone `/0/*` or `/1/*` | `add the matching /0/* and /1/* descriptors, or use /<0;1>/*` |
| two lines that differ | `receive and change descriptors must use the same key` |
| bad checksum | `descriptor checksum does not match` |
| origin length ≠ depth | `key origin path must be the account path` |
| malformed origin | `invalid key origin` |
| descriptor key not depth 3 | `descriptor key must be account-level (depth 3)` |
| three or more lines | `paste one descriptor, or its /0/* and /1/* pair` |

Messages never echo the key.

## UI

- Settings → Secret label: `xpub`, `ypub`, `zpub`, `Descriptor`. The Show screen keeps showing the stored text.
- Receive: next unused address of the key's script type with its HD path, no script picker (picker stays mnemonic-only).
- Import helper: "Enter BIP39 seed, xpub/ypub/zpub, descriptor, WIF private key, or address"; placeholder "seed words, xpub, descriptor, WIF, or address…".

## Testing

- Checksum: BIP380 vector `raw(deadbeef)#89f8spxm`, plus descriptor vectors computed with embit.
- Parsing: each kind accepted; descriptor forms (bare, multipath, two-line, origin with `'` and `h`, missing/good/bad checksum); every rejection row above.
- Derivation: account xpub/ypub/zpub and `tr` descriptor of the `abandon … about` mnemonic give the same first receive and change addresses as the mnemonic watch; origin paths appear in `WatchAddress.path`.
- PSBT: fingerprint/path without origin (account fingerprint + `chain/index`) and with origin (`73c5da0a` + full path); taproot input has tap internal key + tap derivation and no ECDSA derivation; change output carries derivation.
- Change and Receive: change comes from the key's internal chain; receive type is the key's script.
- Persistence: save → load → derive round trip for xpub and descriptor; stored zpub loads unchanged.
