# Multi-script mnemonic wallets

Date: 2026-09-14  
Status: approved (conversation)

## Goal

A mnemonic wallet tracks BIP44, BIP49, BIP84, and BIP86 at the same time. The app discovers funds on all four script types and can spend them. Receive lets the user pick a script type (default BIP84). The choice is stored. Send change uses a random script type and that type’s first unused internal address.

## Non-goals

- Extra BIP32 accounts (`m/44'/0'/1'` and similar) in the live wallet. Account 0 only.
- `xpub` / `ypub` watch wallets. `zpub` stays BIP84 only.
- BIP39 passphrase.
- Change the WIF or imported-address wallet kinds.
- Migration of old `watch_external` / `watch_internal` keys. The app is not in production. Replace those keys.

## Decisions

| Topic | Choice |
| --- | --- |
| Shape | One `WatchWallet`. Four script types on the same mnemonic. |
| Accounts | Mainnet account 0 only. |
| `zpub` | BIP84 only. |
| Gaps | Independent external/internal window per script type. |
| Defaults | `INITIAL_WATCH_COUNT = 20`, `GAP_LIMIT = 20`. `MAX_WATCH_COUNT = 10_000`. |
| Old KV | No compatibility. New keys for all four types. |
| Receive | Dropdown on mnemonic only. Default Native SegWit (BIP84). Stored. |
| Change | Random script type. First unused internal index of that type. Always derive. Never fail for a missing change address. |
| Random | Inject `kotlin.random.Random` in tests. |

## Paths

| Spec | Account path | Script |
| --- | --- | --- |
| BIP44 | `m/44'/0'/0'` | P2PKH |
| BIP49 | `m/49'/0'/0'` | P2SH-P2WPKH |
| BIP84 | `m/84'/0'/0'` | P2WPKH |
| BIP86 | `m/86'/0'/0'` | P2TR |

Child path: `{account}/{0\|1}/{index}`. `WatchAddress.path` is the full string (`m/86'/0'/0'/1/0`). Send already signs from `path` and `scriptType`.

Address list order: BIP44, BIP49, BIP84, BIP86. Each type: all external indexes, then all internal indexes.

`WatchWalletKind.BIP84` still means “HD wallet that can grow gaps” (mnemonic or `zpub`).

## Gaps

`HdWatchGaps` holds four `WatchGaps` values: `p2pkh`, `p2shP2wpkh`, `p2wpkh`, `p2tr`.

KV keys:

| Type | External | Internal |
| --- | --- | --- |
| P2PKH | `watch_external_p2pkh` | `watch_internal_p2pkh` |
| P2SH-P2WPKH | `watch_external_p2sh_p2wpkh` | `watch_internal_p2sh_p2wpkh` |
| P2WPKH | `watch_external_p2wpkh` | `watch_internal_p2wpkh` |
| P2TR | `watch_external_p2tr` | `watch_internal_p2tr` |

Parse and rewrite rules stay the same as today’s `loadWatchGaps` (per key). Missing or invalid → `INITIAL_WATCH_COUNT`.

`Wallet.gaps()` and `peekGaps()` return `HdWatchGaps`. `CreateWalletOptions.addressGap` writes the same `n` to every type.

`deriveWatchWallet(secret, WatchGaps)` on a mnemonic applies that pair to every HD type. `deriveWatchWallet(secret, HdWatchGaps)` sets each type on its own. A `zpub` uses only the P2WPKH pair.

Grow each type on its own. A used index in that type’s last `GAP_LIMIT` addresses grows that type only. Then mark filters unscanned from birthday, same as today.

Default mnemonic watch: 20 + 20 per type → 160 scripts.

## Used indexes

`usedWatchIndexes` returns used external and internal indexes **per script type**. A used BIP44 index `0` does not mark BIP84 index `0`.

`firstUnusedExternalAddress` and `firstUnusedInternalAddress` take an `AddressScriptType`. They look only at that type’s chain.

First unused index is the lowest index that is not used. If `0..n-1` are all used, the next index is `n`. Derive that address even when it is past the current window.

## Receive

Mnemonic only. Receive screen shows a dropdown (not a header `PillButton`):

- Legacy (BIP44)
- Nested SegWit (BIP49)
- Native SegWit (BIP84) — default
- Taproot (BIP86)

Store the choice in `receive_script_type` using existing wire names: `p2pkh`, `p2sh-p2wpkh`, `p2wpkh`, `p2tr`. Missing or invalid → `p2wpkh`. The next open uses the stored type.

QR and text show the first unused **external** address of the saved type. If that type’s window is full, grow that type and rescan.

`zpub`, WIF, and imported address have no dropdown.

## Send

Every watched UTXO stays spendable. The builder already signs P2PKH, P2SH-P2WPKH, P2WPKH, and P2TR from `WatchAddress`.

Change (not MAX):

1. Pick one of the four script types at random. Always all four. Do not filter by the current window.
2. Take that type’s first unused **internal** index.
3. If that address is not in the watch list, derive it and set that type’s internal window to at least `index + 1`. Save gaps. Refresh the wallet.
4. Use that address as change.

Do not throw `no unused change address in watch window` for a mnemonic wallet.

`zpub` uses the same grow rule, BIP84 only. WIF and imported address keep today’s change rule. MAX: no change output.

## Files

| Area | Files |
| --- | --- |
| Constants, types, derive, gaps | `:wallet` `Constants.kt`, `Types.kt`, `Derive.kt`, `WatchGaps.kt`, `Wallet.kt` |
| Receive pick | `:wallet` `ReceiveAddress.kt` |
| Receive preference | `:wallet` `ReceivePreference.kt` |
| Send change | `:parse` `SendContext.kt`; `:wallet` `pickMnemonicChangeAddress(wallet, usedInternalByType, random)` |
| Used indexes | `:parse` `UsedIndexes.kt`, `Types.kt` |
| Gap grow on parse | `:parse` `ParseBlocks.kt` |
| Receive UI | `:shared` `ReceiveScreen.kt` |
| Tests | `:wallet` derive / receive / gaps / send tests; `:parse` used-index and receive tests |

## Test vectors

### BIP44 — BlueWallet `tests/unit/hd-legacy-wallet.test.js`

Mnemonic: `abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about`

| Path | Address |
| --- | --- |
| `m/44'/0'/1'/0/0` | `15qucUWKf95Fo58FdCBhUTSAtsm22HHE2Q` |
| `m/44'/0'/1'/1/0` | `1DgjtFUiXvqxGic9A9fiDPrHNyKC4cGtTH` |

Account 0 is the live path. Assert `m/44'/0'/0'/0/0` equals `1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA` (same abandon seed; widely used BIP44 vector). Derive account-1 addresses in the test by path, not as watched account 1.

### BIP49 — BlueWallet `tests/unit/hd-segwit-p2sh-wallet.test.ts`

Mnemonic: `honey risk juice trip orient galaxy win situate shoot anchor bounce remind horse traffic exotic since escape mimic ramp skin judge owner topple erode`

| Path | Address |
| --- | --- |
| `m/49'/0'/0'/0/0` | `3GcKN7q7gZuZ8eHygAhHrvPa5zZbG5Q1rK` |
| `m/49'/0'/0'/0/1` | `35p5LwCAE7mH2css7onyQ1VuS1jgWtQ4U3` |
| `m/49'/0'/0'/1/0` | `32yn5CdevZQLk3ckuZuA8fEKBco8mEkLei` |

### BIP84 — existing abandon vectors

| Path | Address |
| --- | --- |
| `m/84'/0'/0'/0/0` | `bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu` |
| `m/84'/0'/0'/0/1` | `bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g` |
| `m/84'/0'/0'/1/0` | `bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el` |

### BIP86 — BIP-0086 (same as BlueWallet `hd-taproot-wallet.test.ts`)

Mnemonic: abandon (same as BIP84)

| Path | Address |
| --- | --- |
| `m/86'/0'/0'/0/0` | `bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr` |
| `m/86'/0'/0'/0/1` | `bc1p4qhjn9zdvkux4e44uhx8tc55attvtyu358kutcqkudyccelu0was9fqzwh` |
| `m/86'/0'/0'/1/0` | `bc1p3qkhfews2uk44qtvauqyr2ttdsw7svhkl9nkm9s9c3x4ax5h60wqwruhk7` |

## Behaviour tests

- Default mnemonic watch size is 160 scripts.
- Used index on one type does not mark the same index on another type.
- Gap grow on one type does not grow the others.
- Receive preference: default `p2wpkh`; save and reload.
- Receive address is first unused external of the saved type.
- Change: injected `Random` picks each type; address is that type’s first unused internal; window grows when the index is past the current list.
- Send spends a BIP44 UTXO and a BIP86 UTXO in one tx.
- `zpub` still derives BIP84 only.

## Errors

- Bad `receive_script_type` → `p2wpkh`.
- WIF / address / `zpub`: no receive dropdown.
- Mnemonic send does not fail for a missing change address.
