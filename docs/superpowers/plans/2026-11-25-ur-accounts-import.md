# Import a watch-only account from a BC-UR accounts QR Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Onboarding Scan imports one single-sig account from a `ur:crypto-account`, `ur:crypto-multi-accounts` or `ur:crypto-hdkey` QR (single or animated), with a picker when there are several, stored as a descriptor with its key origin.

**Architecture:** `wallet` grows the in-house CBOR reader to read any item and adds `parseUrAccounts`, which turns a complete UR message into `UrAccount`s (script type, fingerprint, path, xpub, descriptor). In `shared`, a UI-free `AccountsScan` feeds camera text to `UrDecoder(ACCOUNT_UR_TYPES, UR_NOT_ACCOUNT)`; onboarding gets `ScannedAccounts` / `PickAccount` events and a small picker. The chosen descriptor goes through the existing `PersistImportedSecret` → `saveWalletSecret` → `parseWalletSecret` path and on to Year.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, bitcoin-kmp 0.31.0 (`DeterministicWallet.ExtendedPublicKey`, `KeyPath`), kotlin.test.

Spec: `docs/superpowers/specs/2026-11-25-ur-accounts-import-design.md`

## Global Constraints

- No new dependencies. commonMain only uses multiplatform APIs (`decodeToString`, no `java.*`).
- Accepted UR types: `crypto-account`, `crypto-multi-accounts`, `crypto-hdkey`. `account-descriptor`, multisig, non-Bitcoin and testnet keys are out.
- Kept accounts: script `pkh` / `sh(wpkh)` / `wpkh` / `tr` with origin exactly `purpose'/0'/account'` and purpose 44 / 49 / 84 / 86 to match.
- Descriptor form: `wrap([fingerprint/path]xpub/<0;1>/*)#checksum`, fingerprint 8 lowercase hex, `'` markers, xpub version bytes. Taproot is never a zpub.
- Copy, verbatim: `"This QR code isn't an account export."`, `"No single-sig Bitcoin accounts in this QR."`, `"Scanning animated QR… N%"`, `"Point the camera at a QR code"`, `"Choose the account to import"`.
- `toString()` never prints an xpub, descriptor or scanned text (`UrAccount`, `AccountsScanState.Text`, `OnboardingState`, events).
- Tests use real vectors only (spec appendix); no mocks. Compose screens aren't unit tested (repo convention).
- Spec decision 2's `skip()` is realised as one `CborReader.readItem()` that reads any item into a small tree; unknown map keys are simply never looked up.
- A fresh worktree needs the vendored submodules once: `git submodule update --init`.
- Full checks: `JAVA_HOME=/home/box/jdk/current ./gradlew :shared:compileKotlinJvm :shared:jvmTest :wallet:jvmTest ktlintCheck detekt --offline`.

## File map

- Modify `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/EncodePsbtUr.kt` (`CborReader` internal + `readItem`, `CborTag`; `UrEncoder` internal)
- Modify `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/UrDecoder.kt` (`wrongTypeReason`, public `type`)
- Create `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/UrAccounts.kt`
- Create `shared/src/commonMain/kotlin/io/bluewallet/blueberry/AccountsScan.kt`
- Modify `shared/src/commonMain/kotlin/io/bluewallet/blueberry/onboarding/OnboardingFlow.kt`
- Create `shared/src/commonMain/kotlin/io/bluewallet/blueberry/onboarding/ImportAccounts.kt` (`ImportScanner`, `AccountPicker`; a new file keeps `OnboardingApp.kt` under detekt's function-count and method-length limits)
- Modify `shared/src/commonMain/kotlin/io/bluewallet/blueberry/onboarding/OnboardingApp.kt`
- Tests: `wallet/.../UrAccountsVectors.kt`, `wallet/.../UrAccountsTest.kt`, `wallet/.../UrDecoderTest.kt`, `shared/.../AccountsScanTest.kt`, `shared/.../onboarding/OnboardingFlowTest.kt`

---

### Task 1: Parse account exports into accounts

**Files:**
- Modify: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/EncodePsbtUr.kt` (the `CborReader` block near the end)
- Create: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/UrAccounts.kt`
- Create: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/UrAccountsVectors.kt`
- Test: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/UrAccountsTest.kt`

**Interfaces:**
- Consumes: `descriptorChecksum(String): String?` (`DescriptorChecksum.kt`), `ACCOUNT_DEPTH` (`WatchKey.kt`), `AddressScriptType`, `UrDecoder(Set<String>)`, `UrReceive.Complete`, `hexToBytes`, `parseWalletSecret`, `ParsedWalletSecret.watchKey()`, `KeyOrigin`.
- Produces:
  ```kotlin
  internal class CborTag(val tag: Long, val item: Any?)
  internal class CborReader(data: ByteArray) { fun readItem(depth: Int = 0): Any? /* + existing readUInt/readByteString */ }
  val ACCOUNT_UR_TYPES: Set<String>                // {"crypto-account", "crypto-multi-accounts", "crypto-hdkey"}
  const val UR_NOT_ACCOUNT: String                 // "This QR code isn't an account export."
  data class UrAccount(val scriptType: AddressScriptType, val fingerprint: Long, val path: KeyPath, val xpub: String) { val descriptor: String }
  fun parseUrAccounts(type: String, message: ByteArray): List<UrAccount>   // throws IllegalArgumentException on malformed CBOR
  ```

- [ ] **Step 1: Add the vectors** (`UrAccountsVectors.kt`, values copied verbatim from the spec appendix; the hdkeys are the four BTC maps sliced out of the multi-accounts payload)

```kotlin
package io.bluewallet.blueberry.wallet

// BC-UR account exports from BlueWallet master 2432b458 (spec appendix). Keystone 3 Pro payloads are from
// tests/unit/ur-hardware-accounts.test.ts (05e9f539); the older ones from tests/unit/watch-only-wallet.test.js.

const val KEYSTONE_ACCOUNT_HEAD = "a2011a73c5da0a0284"
const val KEYSTONE_WPKH = "d90194d9012fa702f403582102707a62fdacc26ea9b63b1c197906f56ee0180d0bcf1966e1a2da34f5f3a09a9b0458204a53a0ab21b9dc95869c4e92a161194e03c0ef3ff5014ac692f433c4765490fc05d90131a20100020006d90130a301861854f500f500f5021a73c5da0a030307d90130a2018400f480f40300081a7ef32bdb"
const val KEYSTONE_PKH = "d90193d9012fa702f403582103774c910fcf07fa96886ea794f0d5caed9afe30b44b83f7e213bb92930e7df4bd0458203da4bc190a2680111d31fadfdc905f2a7f6ce77c6f109919116f253d4344521905d90131a20100020006d90130a30186182cf500f500f5021a73c5da0a030307d90130a2018400f480f40300081a155bca59"
const val KEYSTONE_SH_WPKH = "d90190d90194d9012fa702f403582102f1f347891b20f7568eae3ec9869fbfb67bcab6f358326f10ecc42356bd55939d0458206eaae365ae0e0a0aab84325cfe7cd76c3b909035f889e7d3f1b847a9a0797ecb05d90131a20100020006d90130a301861831f500f500f5021a73c5da0a030307d90130a2018400f480f40300081a3d05ff75"
const val KEYSTONE_TR = "d90199d9012fa702f403582103418278a2885c8bb98148158d1474634097a179c642f23cf1cc04da629ac6f0fb045820c61a8f27e98182314d2444da3e600eb5836ec8ad183c86c311f95df8082b18aa05d90131a20100020006d90130a301861856f500f500f5021a73c5da0a030307d90130a2018400f480f40300081a035270da"

/** BCR-2020-010 `pkh` of a bare EC key: no HD key. */
const val PKH_EC_KEY = "d90193d90132a103582102c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"

/** 10 ETH, 4 BTC and 1 TON keys. */
const val KEYSTONE_MULTI_ACCOUNTS =
    "a5011a1250b6bc0291d9012fa802f403582103c4ad25226a98764f7b753b6c763be112c940182e0b6698944c9def253cec4676045820bf103cfabcab325a7463ab5c432a4042109279d6c3531c8c5ea712684cf31f3706d90130a30186182cf5183cf500f5021a1250b6bc030307d90130a2018400f480f40300081a63c5ea6709684b657973746f6e650a706163636f756e742e7374616e64617264d9012fa802f403582103c4ad25226a98764f7b753b6c763be112c940182e0b6698944c9def253cec4676045820bf103cfabcab325a7463ab5c432a4042109279d6c3531c8c5ea712684cf31f3706d90130a30186182cf5183cf500f5021a1250b6bc030307d90130a2018280f40300081a63c5ea6709684b657973746f6e650a756163636f756e742e6c65646765725f6c6567616379d9012fa602f403582103de2f443b5070e839d6a209ed1afaadfd43daf7bc610a1fefef201a3aca5350ee06d90130a3018a182cf5183cf500f500f400f4021a1250b6bc0303081a769cd41a09684b657973746f6e650a736163636f756e742e6c65646765725f6c697665d9012fa602f403582103a96bae34b46e82eee9c5beae52e446693b5c2e365714b223adea7caae47e1b7f06d90130a3018a182cf5183cf501f500f400f4021a1250b6bc0303081a998dec6209684b657973746f6e650a736163636f756e742e6c65646765725f6c697665d9012fa602f4035821029299ea371a1c6cdcc75b91d26322a2dbdfedb58285e33760a2af82cfee1a7a6e06d90130a3018a182cf5183cf502f500f400f4021a1250b6bc0303081ac49452d709684b657973746f6e650a736163636f756e742e6c65646765725f6c697665d9012fa602f4035821030f1808f972aed2b8e01d92b09b9c0d55b994592fb5599557e0d4828705204a4106d90130a3018a182cf5183cf503f500f400f4021a1250b6bc0303081a0823c44c09684b657973746f6e650a736163636f756e742e6c65646765725f6c697665d9012fa602f4035821021c885e79f19ba2658755952801366f00f5304db8597eb72a78a6886d8175037d06d90130a3018a182cf5183cf504f500f400f4021a1250b6bc0303081aef4e5bae09684b657973746f6e650a736163636f756e742e6c65646765725f6c697665d9012fa602f4035821037653d3a64a3d1fce16d5693e044e9aea644082371f4d31f6eb83096b5b84d97b06d90130a3018a182cf5183cf505f500f400f4021a1250b6bc0303081a364c571b09684b657973746f6e650a736163636f756e742e6c65646765725f6c697665d9012fa602f403582103ded46d3bc515112c1c99b5f5b5f6c4abc76cc40d98ba4c1808c97038fe976ff606d90130a3018a182cf5183cf506f500f400f4021a1250b6bc0303081a456759fc09684b657973746f6e650a736163636f756e742e6c65646765725f6c697665d9012fa602f403582102447d94e1937819a79a0ef56cd7b7a29de7c80a45b34480ef0b593083e418557906d90130a3018a182cf5183cf507f500f400f4021a1250b6bc0303081a09f362d709684b657973746f6e650a736163636f756e742e6c65646765725f6c697665d9012fa602f403582103d94fb59028252ab9353ee376e868cfd59e9d0bc24ef35fe1b06310cd0e4e908b06d90130a3018a182cf5183cf508f500f400f4021a1250b6bc0303081a4e09767009684b657973746f6e650a736163636f756e742e6c65646765725f6c697665d9012fa602f4035821034d53b1c2630e12b853b96d70fb671363d5d4ec9406f722b4deea1f2a9eb6e76406d90130a3018a182cf5183cf509f500f400f4021a1250b6bc0303081ac418564209684b657973746f6e650a736163636f756e742e6c65646765725f6c697665d9012fa702f40358210246696468fa5c46647bdd186f568983e2a26773dc5b4c174790fee7ef9b9a7c9904582051872c7bb8270a7655ae15d738bb21379962ff11fb048b55630395afcfef442806d90130a30186182cf500f500f5021a1250b6bc030307d90130a2018280f40300081ab5d6f45009684b657973746f6e65d9012fa702f403582103ab6a1908b437179b914dc3cf4b829d7387adf60fdee483e9c748d5ee79ba4be4045820bad8f929afd65c4b3ff78db1a386062374f2d2e6552e9fdc59aa7dd9b5db6e2c06d90130a301861831f500f500f5021a1250b6bc030307d90130a2018280f40300081a197077c809684b657973746f6e65d9012fa702f40358210262ba42544acc4223c74511b37bae54de22565f25f8034f5af8aa10a04c3621b9045820de47dd17679605b84abf439bdf5477c6cb94ff5a541d3cb1734afebfa3d3fdb906d90130a301861854f500f500f5021a1250b6bc030307d90130a2018280f40300081a9f6b7e4b09684b657973746f6e65d9012fa702f403582103e0db6ca7c71e72a40091141b9dc4596ab3a9192ac41eebe1f2b048c654423ecb045820a75805b89c5079ba74e9002c3e587f7c97ea8018ab1d5245638a45de3c845e0b06d90130a301861856f500f500f5021a1250b6bc030307d90130a2018280f40300081a0484004d09684b657973746f6e65d9012fa2035820895881fe5bab8b88e450055ff6916bf2bf41bc315325cee7975f33069beb079c06d90130a20186182cf519025ff500f5021a1250b6bc036e4b657973746f6e6520332050726f047828626339633466373135376165363937643433346163383536323332306564353335653737373638300565312e372e30"

/** The four BTC hdkeys of [KEYSTONE_MULTI_ACCOUNTS] (44', 49', 84', 86'), as `crypto-hdkey` messages. */
val KEYSTONE_HDKEYS =
    listOf(
        "a702f40358210246696468fa5c46647bdd186f568983e2a26773dc5b4c174790fee7ef9b9a7c9904582051872c7bb8270a7655ae15d738bb21379962ff11fb048b55630395afcfef442806d90130a30186182cf500f500f5021a1250b6bc030307d90130a2018280f40300081ab5d6f45009684b657973746f6e65",
        "a702f403582103ab6a1908b437179b914dc3cf4b829d7387adf60fdee483e9c748d5ee79ba4be4045820bad8f929afd65c4b3ff78db1a386062374f2d2e6552e9fdc59aa7dd9b5db6e2c06d90130a301861831f500f500f5021a1250b6bc030307d90130a2018280f40300081a197077c809684b657973746f6e65",
        "a702f40358210262ba42544acc4223c74511b37bae54de22565f25f8034f5af8aa10a04c3621b9045820de47dd17679605b84abf439bdf5477c6cb94ff5a541d3cb1734afebfa3d3fdb906d90130a301861854f500f500f5021a1250b6bc030307d90130a2018280f40300081a9f6b7e4b09684b657973746f6e65",
        "a702f403582103e0db6ca7c71e72a40091141b9dc4596ab3a9192ac41eebe1f2b048c654423ecb045820a75805b89c5079ba74e9002c3e587f7c97ea8018ab1d5245638a45de3c845e0b06d90130a301861856f500f500f5021a1250b6bc030307d90130a2018280f40300081a0484004d09684b657973746f6e65",
    )

/** Sparrow/Hummingbird: origins without depth or source fingerprint; multisig 45' and 48' outputs too. */
const val SPARROW_ACCOUNT_UR =
    "ur:crypto-account/oeadcyemrewytyaolntaadmutaaddloxaxhdclaxwmfmdeiamecsdsemgtvsjzcncygrkowtrontzschgezokstswkkscfmklrtauteyaahdcxiehfonurdppfyntapejpproypegrdawkgmaewejlsfdtsrfybdehcaflmtrlbdhpamtaaddyoyadlncsdwykaeykaeykaycynlytsnyltaadmhtaadmwtaaddloxaxhdclaostvelfemdyynwydwyaievosrgmambklovabdgypdglldvespsthysadamhpmjeinaahdcxntdllnaaeykoytdacygegwhgjsiyonpywmcmrpwphsvodsrerozsbyaxluzcoxdpamtaaddyoyadlncsehykaeykaeykaycypdbskeuytaadmwtaaddloxaxhdclaxzcfxeegdrpmogrgwkbzctlttweadkiengrwlhtprremouoluutqdpfbncedkynfhaahdcxjpwevdeogthttkmeswzcolcpsaahcfnshkhtehytclmnteatmoteadtlwynnftloamtaaddyoyadlncsghykaeykaeykaycybthlvytstaadmhtaaddloxaxhdclaxhhsnhdrpftdwuocntilydibehnecmovdfekpjkclcslasbhkpawsaddmcmmnahnyaahdcxlotedtndfymyltclhlmtpfsadscnhtztaolbnnkistaedegwfmmedreetnwmcycnamtaaddyoyadlfcsdpykaycyemrewytytaadmhtaadmetaaddloxaxhdclaxdwkswmztpytnswtsecnblfbayajkdldeclqzzolrsnhljedsgminetytbnahatbyaahdcxkkguwsvyimjkvwteytwztyswvendtpmncpasfrrylprnhtkblndrgrmkoyjtbkrpamtaaddyoyadlocsdyykaeykaeykadykaycyhkrpnddrtaadmetaaddloxaxhdclaohnhffmvsbndslrfgclpfjejyatbdpebacnzokotofxntaoemvskpaowmryfnotfgaahdcxdlnbvecentssfsssgylnhkrstoytecrdlyadrekirfaybglahltalsrfcaeerobwamtaaddyoyadlocsdyykaeykaeykaoykaycyhkrpnddrgdaogykb"

const val BLUEWALLET_BIP84_ACCOUNT_UR =
    "UR:CRYPTO-ACCOUNT/OEADCYADWMTNKIAOLYTAADMWTAADDLOSAOWKAXHDCLAXMDRPFXWKHPTPNEWEVAWKYNFPJEDEMNJKAEGHCFQZLKUOTPLRIHMEFRTECWGRVWWDAAHDCXMHIODYPYLEAXZOGRPKEYPTBGBWGWDWHPZEIMVDBAAOIEVEWLZEGRBKRNFTHFAMMOAHTAADEHOEADADAOAEAMTAADDYOTADLNCSGHYKAEYKAEYKAOCYADWMTNKIAXAXATTAADDYOEADLRAEWKLAWKAXAEAYCYBGHFLOPACMIOWZLB"

const val BLUEWALLET_ABANDON_ACCOUNTS_UR =
    "UR:CRYPTO-ACCOUNT/OEADCYJKSKTNBKAOLSTAADMWTAADDLONAXHDCLAOJOKNIDZCPSSAJTPTRPFRCECFKKAMYKJTVTCSBTBDTKCFIYVYOETNEEYKWFNBNYNDAAHDCXGEGUNBPYCLRHUOMDLNNSGLMOOYHSCFGLAXRTWSFHYKADGESWMOWKEOSSKOGHMHZTAMTAADDYOTADLNCSGHYKAEYKAEYKAOCYJKSKTNBKAXAXATTAADDYOYADLRAEWKLAWKAYCYKBWFDNUYTAADMHTAADMWTAADDLONAXHDCLAOWNWFFLLDCWCXYLHFMNPLFMSOLNNERSRPKGSGRPWFHDEYJLBEWPSSCNHFRYGOMUNTAAHDCXJTPKVLIHPLBABKBKPYLREYHHZEKETSJZFRMHMHECYALDVDTEWNROFLPTNBKKKBSBAMTAADDYOTADLNCSEHYKAEYKAEYKAOCYJKSKTNBKAXAXATTAADDYOYADLRAEWKLAWKAYCYFSAHZMKPTAADMUTAADDLONAXHDCLAXKTGSMEBSTKATZSMTLOJTOSMWWTTLSGWENYZEDYQZGRLSYLVOBWRKMOMUBAKIWKRYAAHDCXFSOXRFCFBKDSLABYCAEHZSURUOMHHEDRLBJZVDKEJLBENLCFBYJLDAFSFXFYGMCFAMTAADDYOTADLNCSDWYKAEYKAEYKAOCYJKSKTNBKAXAXATTAADDYOYADLRAEWKLAWKAYCYBZHPSGHKSOPAJSLN"

const val KEYSTONE_TR_DESCRIPTOR = "tr([73c5da0a/86'/0'/0']xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteoGVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ/<0;1>/*)#kjk9q86c"
const val KEYSTONE_WPKH_DESCRIPTOR = "wpkh([73c5da0a/84'/0'/0']xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3XyuvPEbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V/<0;1>/*)#hpg6d6w2"
const val KEYSTONE_PKH_DESCRIPTOR = "pkh([73c5da0a/44'/0'/0']xpub6BosfCnifzxcFwrSzQiqu2DBVTshkCXacvNsWGYJVVhhawA7d4R5WSWGFNbi8Aw6ZRc1brxMyWMzG3DSSSSoekkudhUd9yLb6qx39T9nMdj/<0;1>/*)#x4nu8kpc"
const val KEYSTONE_SH_WPKH_DESCRIPTOR = "sh(wpkh([73c5da0a/49'/0'/0']xpub6C6nQwHaWbSrzs5tZ1q7m5R9cPK9eYpNMFesiXsYrgc1P8bvLLAet9JfHjYXKjToD8cBRswJXXbbFpXgwsswVPAZzKMa1jUp2kVkGVUaJa7/<0;1>/*))#wfg3f37j"

val KEYSTONE_MULTI_DESCRIPTORS =
    listOf(
        "pkh([1250b6bc/44'/0'/0']xpub6CzHiXpZKvPYnCwnd6qq511yn8B63yB8rCtxsY4azeuCxPYJn6B7KNEa6sDCkNAj54H5AW3zYY8QLSzG6qe7SZBvKW3ighdPGTYQgDXxfHQ/<0;1>/*)#vxkfgn4x",
        "sh(wpkh([1250b6bc/49'/0'/0']xpub6BqcZUPst99NuHmP1TNcCXftBBUjf87UAgZYfGYLKnafbhSo9rjoQoVhXDYwcf4Bt7wv2CszAiBXfrhgx5yoJNo8qe1FVQPNyTEtsaWJp52/<0;1>/*))#c8wth407",
        "wpkh([1250b6bc/84'/0'/0']xpub6CpjN9cV2eSSHvzA5113pRqD5qaWRhUXS7ABs5AqGiau3BbAnW2fx1JwEEwn9ugVAgx6vbpXAKEjQbKjYHHPCjaxHEwyfLcUvwxjbaBEPRe/<0;1>/*)#c5tjhvqc",
        "tr([1250b6bc/86'/0'/0']xpub6BghCcrqiqZKVhbMggpicCo9cziXGGyde4uW2adRcH6aLNYYPGL6vQRYS64pAp1cgCidiZ7zwGTHN2NmF4aJWijenLYmSeTVsyrauhWJDjA/<0;1>/*)#csq8lhzg",
    )

val SPARROW_DESCRIPTORS =
    listOf(
        "pkh([37b5eed4/44'/0'/0']xpub6CnQkivUEH9bSbWVWfDLCtigKKgnSWGaVSRyCbN2QNBJzuvHT1vUQpgSpY1NiVvoeNEuVwk748Cn9G3NtbQB1aGGsEL7aYEnjVWgjj9tefu/<0;1>/*)#vxycy4eh",
        "sh(wpkh([37b5eed4/49'/0'/0']xpub6CtR1iF4dZPkEyXDwVf3HE74tSwXNMcHtBzX4gwz2UnPhJ54Jz5unHx2syYCCDkvVUmsmoYTmcaHXe1wJppvct4GMMaN5XAbRk7yGScRSte/<0;1>/*))#xd5r4mrt",
        "wpkh([37b5eed4/84'/0'/0']xpub6BkU445MSEBXbPjD3g2c2ch6mn8yy1SXXQUM7EwjgYiq6Wt1NDwDZ45npqWcV8uQC5oi2gHuVukoCoZZyT4HKq8EpotPMqGqxdZRuapCQ23/<0;1>/*)#nlexnw55",
    )

const val BLUEWALLET_BIP84_DESCRIPTOR = "wpkh([01ebda7d/84'/0'/0']xpub6BnayRznWfa19UPEPT8H9BYCoFxrvaFCfUSX3MYWk84cm6dZfqUZcxYyYreexRLcGAx7qpQzJvqanav7D7QKRVP6cZwoWAvdbCbPe5QJpUF/<0;1>/*)#aksppjg0"
```

- [ ] **Step 2: Write the failing tests** (`UrAccountsTest.kt`)

```kotlin
package io.bluewallet.blueberry.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class UrAccountsTest {
    private fun descriptors(
        type: String,
        hex: String,
    ): List<String> = parseUrAccounts(type, hexToBytes(hex)).map { it.descriptor }

    /** A single-part UR through the decoder, as the camera reads it. */
    private fun scanned(ur: String): List<String> {
        val complete = assertIs<UrReceive.Complete>(UrDecoder(ACCOUNT_UR_TYPES).receive(ur))
        return parseUrAccounts("crypto-account", complete.message).map { it.descriptor }
    }

    @Test
    fun keystone_crypto_account_keeps_payload_order_with_taproot_first() {
        assertEquals(
            listOf(KEYSTONE_TR_DESCRIPTOR, KEYSTONE_WPKH_DESCRIPTOR, KEYSTONE_PKH_DESCRIPTOR, KEYSTONE_SH_WPKH_DESCRIPTOR),
            descriptors("crypto-account", KEYSTONE_ACCOUNT_HEAD + KEYSTONE_TR + KEYSTONE_WPKH + KEYSTONE_PKH + KEYSTONE_SH_WPKH),
        )
    }

    @Test
    fun a_wsh_output_is_skipped_on_its_own() {
        val wsh = KEYSTONE_WPKH.replaceFirst("d90194", "d90191")
        assertEquals(listOf(KEYSTONE_TR_DESCRIPTOR), descriptors("crypto-account", "a2011a73c5da0a0282" + wsh + KEYSTONE_TR))
    }

    @Test
    fun an_output_without_an_hd_key_rejects_the_whole_account() {
        assertEquals(emptyList(), descriptors("crypto-account", "a2011a73c5da0a0282" + KEYSTONE_WPKH + PKH_EC_KEY))
    }

    @Test
    fun keystone_multi_accounts_keeps_only_the_bitcoin_keys() {
        assertEquals(KEYSTONE_MULTI_DESCRIPTORS, descriptors("crypto-multi-accounts", KEYSTONE_MULTI_ACCOUNTS))
    }

    @Test
    fun each_crypto_hdkey_gives_its_account_with_the_origin_fingerprint() {
        assertEquals(KEYSTONE_MULTI_DESCRIPTORS, KEYSTONE_HDKEYS.flatMap { descriptors("crypto-hdkey", it) })
    }

    @Test
    fun sparrow_origins_without_depth_keep_single_sig_and_drop_multisig() {
        assertEquals(SPARROW_DESCRIPTORS, scanned(SPARROW_ACCOUNT_UR))
    }

    @Test
    fun older_bluewallet_vectors() {
        assertEquals(listOf(BLUEWALLET_BIP84_DESCRIPTOR), scanned(BLUEWALLET_BIP84_ACCOUNT_UR))
        assertEquals(
            listOf(KEYSTONE_WPKH_DESCRIPTOR, KEYSTONE_SH_WPKH_DESCRIPTOR, KEYSTONE_PKH_DESCRIPTOR),
            scanned(BLUEWALLET_ABANDON_ACCOUNTS_UR),
        )
    }

    @Test
    fun malformed_cbor_throws() {
        assertFailsWith<IllegalArgumentException> { descriptors("crypto-multi-accounts", KEYSTONE_MULTI_ACCOUNTS.dropLast(40)) }
        assertFailsWith<IllegalArgumentException> { descriptors("crypto-account", "a1") }
    }

    @Test
    fun every_account_imports_as_its_descriptor() {
        val accounts =
            parseUrAccounts("crypto-account", hexToBytes(KEYSTONE_ACCOUNT_HEAD + KEYSTONE_TR + KEYSTONE_WPKH + KEYSTONE_PKH + KEYSTONE_SH_WPKH)) +
                parseUrAccounts("crypto-multi-accounts", hexToBytes(KEYSTONE_MULTI_ACCOUNTS))
        for (account in accounts) {
            val parsed = parseWalletSecret(account.descriptor)
            assertEquals(WalletSecretKind.DESCRIPTOR, parsed.kind)
            val key = parsed.watchKey()!!
            assertEquals(account.scriptType, key.scriptType)
            assertEquals(KeyOrigin(account.fingerprint, account.path), key.origin)
        }
        assertEquals(2, accounts.count { parseWalletSecret(it.descriptor).watchKey()!!.scriptType == AddressScriptType.P2TR })
    }
}
```

- [ ] **Step 3: Run them to see them fail**

Run: `JAVA_HOME=/home/box/jdk/current ./gradlew :wallet:jvmTest --tests '*UrAccountsTest*' --offline`
Expected: FAIL, compilation errors `Unresolved reference 'parseUrAccounts'`, `'ACCOUNT_UR_TYPES'`.

- [ ] **Step 4: Grow `CborReader`** in `EncodePsbtUr.kt`. Replace the section comment line

```kotlin
// Minimal CBOR: byte strings, unsigned ints, and the fixed-shape 5-element fountain-part array.
```

with

```kotlin
// Minimal CBOR: byte strings, unsigned ints, the 5-element fountain-part array, and any item for the registry types.
```

and replace the whole `private class CborReader(...) { ... }` (up to, not including, `internal fun cborDecodeByteString`) with:

```kotlin
/** A CBOR tag (major type 6) and the item it wraps. */
internal class CborTag(
    val tag: Long,
    val item: Any?,
)

private const val MAX_CBOR_DEPTH = 16

internal class CborReader(
    private val data: ByteArray,
) {
    private var pos = 0

    private fun readByte(): Int {
        require(pos < data.size) { "CBOR ends early" }
        return data[pos++].toInt() and 0xFF
    }

    fun readHeader(): Pair<Int, Int> {
        val b = readByte()
        return (b shr 5) to (b and 0x1F)
    }

    fun readLength(additionalInfo: Int): Long =
        when {
            additionalInfo < 24 -> additionalInfo.toLong()
            additionalInfo == 24 -> readByte().toLong()
            additionalInfo == 25 -> (readByte().toLong() shl 8) or readByte().toLong()
            additionalInfo == 26 -> {
                var v = 0L
                repeat(4) { v = (v shl 8) or readByte().toLong() }
                v
            }
            else -> throw IllegalArgumentException("unsupported CBOR additional info: $additionalInfo")
        }

    fun readBytes(length: Int): ByteArray {
        require(length in 0..data.size - pos) { "CBOR ends early" }
        val out = data.copyOfRange(pos, pos + length)
        pos += length
        return out
    }

    fun readUInt(): Long {
        val (majorType, info) = readHeader()
        require(majorType == 0) { "expected CBOR unsigned int, got major type $majorType" }
        return readLength(info)
    }

    fun readByteString(): ByteArray {
        val (majorType, info) = readHeader()
        require(majorType == 2) { "expected CBOR byte string, got major type $majorType" }
        return readBytes(readLength(info).toInt())
    }

    /**
     * Any one item: Long (unsigned or negative int), ByteArray, String, List, Map, [CborTag], Boolean or null.
     * Definite lengths only, nested at most [MAX_CBOR_DEPTH] deep.
     */
    fun readItem(depth: Int = 0): Any? {
        require(depth <= MAX_CBOR_DEPTH) { "CBOR nested too deep" }
        val (majorType, info) = readHeader()
        if (majorType == 7) return simpleValue(info)
        val length = readLength(info)
        // Every element takes at least one byte, so a count past the remaining bytes is malformed.
        require(majorType == 0 || majorType == 1 || majorType == 6 || length <= data.size - pos) { "CBOR ends early" }
        return when (majorType) {
            0 -> length
            1 -> -1 - length
            2 -> readBytes(length.toInt())
            3 -> readBytes(length.toInt()).decodeToString()
            4 -> List(length.toInt()) { readItem(depth + 1) }
            5 -> buildMap { repeat(length.toInt()) { put(readItem(depth + 1), readItem(depth + 1)) } }
            else -> CborTag(length, readItem(depth + 1))
        }
    }

    private fun simpleValue(info: Int): Boolean? =
        when (info) {
            20 -> false
            21 -> true
            22 -> null
            else -> throw IllegalArgumentException("unsupported CBOR simple value: $info")
        }
}
```

(`readLength` now throws `IllegalArgumentException` instead of `error(...)`, and short input fails with `"CBOR ends early"` instead of an index exception, so every malformed message is an `IllegalArgumentException`.)

- [ ] **Step 5: Write the parser** (`UrAccounts.kt`)

```kotlin
package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath

/** UR types an account export comes in: BCR-2020-015 `crypto-account`, Keystone's `crypto-multi-accounts`, BCR-2020-007 `crypto-hdkey`. */
val ACCOUNT_UR_TYPES: Set<String> = setOf("crypto-account", "crypto-multi-accounts", "crypto-hdkey")

const val UR_NOT_ACCOUNT = "This QR code isn't an account export."

/** One single-sig Bitcoin account from an account export: `m/purpose'/0'/account'` of the master key [fingerprint]. */
data class UrAccount(
    val scriptType: AddressScriptType,
    val fingerprint: Long,
    val path: KeyPath,
    val xpub: String,
) {
    /** The account as a checksummed descriptor with its key origin and `<0;1>` multipath, e.g. `wpkh([73c5da0a/84'/0'/0']xpub…)`. */
    val descriptor: String
        get() {
            val (open, close) = DESCRIPTOR_WRAPPERS.getValue(scriptType)
            val origin = fingerprint.toString(16).padStart(8, '0') + path.asString('\'').removePrefix("m")
            val body = "$open[$origin]$xpub/<0;1>/*$close"
            return "$body#${checkNotNull(descriptorChecksum(body))}"
        }

    override fun toString(): String = "UrAccount(scriptType=$scriptType, path=${path.asString('\'')}, xpub=[redacted])"
}

private val DESCRIPTOR_WRAPPERS =
    mapOf(
        AddressScriptType.P2PKH to ("pkh(" to ")"),
        AddressScriptType.P2SH_P2WPKH to ("sh(wpkh(" to "))"),
        AddressScriptType.P2WPKH to ("wpkh(" to ")"),
        AddressScriptType.P2TR to ("tr(" to ")"),
    )

private const val TAG_HDKEY = 303L
private const val TAG_KEYPATH = 304L
private const val TAG_EC_KEY = 306L

// Script tags (BCR-2020-010) from the outside in: sh 400, pkh 403, wpkh 404, tr 409.
private val SCRIPT_TAGS =
    mapOf(
        listOf(403L) to AddressScriptType.P2PKH,
        listOf(400L, 404L) to AddressScriptType.P2SH_P2WPKH,
        listOf(404L) to AddressScriptType.P2WPKH,
        listOf(409L) to AddressScriptType.P2TR,
    )

private val PURPOSES =
    mapOf(
        44L to AddressScriptType.P2PKH,
        49L to AddressScriptType.P2SH_P2WPKH,
        84L to AddressScriptType.P2WPKH,
        86L to AddressScriptType.P2TR,
    )

private const val ACCOUNT_DEPTH_LONG = 3L
private const val COMPRESSED_KEY_SIZE = 33
private const val CHAIN_CODE_SIZE = 32

/**
 * The single-sig Bitcoin accounts in a complete UR [message] of one of [ACCOUNT_UR_TYPES], in payload order.
 * Empty when none is usable, or when a `crypto-account` output with a supported script has no usable HD key
 * (the whole account is rejected, as BlueWallet does). Throws [IllegalArgumentException] on malformed CBOR.
 */
fun parseUrAccounts(
    type: String,
    message: ByteArray,
): List<UrAccount> {
    val root = CborReader(message).readItem()
    return when (type) {
        "crypto-account" -> cryptoAccount(root.asMap())
        "crypto-multi-accounts" -> {
            val map = root.asMap()
            val fingerprint = map.uint(1)
            map.list(2).mapNotNull { key -> usableKey(key.tagged(TAG_HDKEY).asMap(), fingerprint)?.let(::byPurpose) }
        }
        "crypto-hdkey" -> listOfNotNull(usableKey(root.asMap(), null)?.let(::byPurpose))
        else -> throw IllegalArgumentException("not an account UR type: $type")
    }
}

private fun cryptoAccount(map: Map<*, *>): List<UrAccount> {
    val fingerprint = map.uint(1)
    val accounts = mutableListOf<UrAccount>()
    for (output in map.list(2)) {
        val tags = mutableListOf<Long>()
        var inner = output
        while (inner is CborTag && inner.tag != TAG_HDKEY && inner.tag != TAG_EC_KEY) {
            tags += inner.tag
            inner = inner.item
        }
        val scriptType = SCRIPT_TAGS[tags] ?: continue
        val hdKey = (inner as? CborTag)?.takeIf { it.tag == TAG_HDKEY }?.item as? Map<*, *>
        val key = hdKey?.let { usableKey(it, fingerprint) } ?: return emptyList()
        account(key, scriptType)?.let(accounts::add)
    }
    return accounts
}

/** A usable HD key (spec decision 5): `steps` are the origin's `(index, hardened)` pairs. */
private class HdKey(
    val key: ByteArray,
    val chainCode: ByteArray,
    val steps: List<Pair<Long, Boolean>>,
    val depth: Long?,
    val parentFingerprint: Long,
    val fingerprint: Long,
)

private fun usableKey(
    map: Map<*, *>,
    masterFingerprint: Long?,
): HdKey? {
    val key = (map[3L] as? ByteArray)?.takeIf { it.size == COMPRESSED_KEY_SIZE && it[0].toInt() in 2..3 }
    val chainCode = (map[4L] as? ByteArray)?.takeIf { it.size == CHAIN_CODE_SIZE }
    val origin = (map[6L] as? CborTag)?.takeIf { it.tag == TAG_KEYPATH }?.item as? Map<*, *>
    val steps = originSteps(origin?.get(1L))
    val fingerprint = masterFingerprint ?: origin?.get(2L) as? Long
    return when {
        map[1L] == true || map[2L] == true -> null
        key == null || chainCode == null || steps.isNullOrEmpty() || fingerprint == null -> null
        else -> HdKey(key, chainCode, steps, origin?.get(3L) as? Long, map[8L] as? Long ?: 0L, fingerprint)
    }
}

/** Keypath components as `(index, hardened)` pairs; null for a wildcard, a range or an odd count. */
private fun originSteps(components: Any?): List<Pair<Long, Boolean>>? {
    val list = components as? List<*> ?: return null
    val steps = list.chunked(2).mapNotNull { pair -> (pair.getOrNull(0) as? Long)?.let { index -> (pair.getOrNull(1) as? Boolean)?.let { index to it } } }
    return steps.takeIf { it.size * 2 == list.size }
}

private fun byPurpose(key: HdKey): UrAccount? = PURPOSES[key.steps.first().first]?.let { account(key, it) }

/** The account when the origin is `purpose'/0'/account'` with the purpose of [scriptType] (spec decision 6). */
private fun account(
    key: HdKey,
    scriptType: AddressScriptType,
): UrAccount? {
    val steps = key.steps
    val isAccountPath =
        steps.size == ACCOUNT_DEPTH &&
            steps.all { (index, hardened) -> hardened && index < DeterministicWallet.hardenedKeyIndex } &&
            PURPOSES[steps[0].first] == scriptType &&
            steps[1].first == 0L &&
            (key.depth ?: ACCOUNT_DEPTH_LONG) == ACCOUNT_DEPTH_LONG
    if (!isAccountPath) return null
    val path = KeyPath(steps.map { DeterministicWallet.hardened(it.first) })
    val account = DeterministicWallet.ExtendedPublicKey(ByteVector(key.key), ByteVector32(key.chainCode), ACCOUNT_DEPTH, path, key.parentFingerprint)
    return UrAccount(scriptType, key.fingerprint, path, account.encode(DeterministicWallet.xpub))
}

private fun Any?.asMap(): Map<*, *> = this as? Map<*, *> ?: throw IllegalArgumentException("expected a CBOR map")

private fun Any?.tagged(tag: Long): Any? = (this as? CborTag)?.takeIf { it.tag == tag }?.item ?: throw IllegalArgumentException("expected CBOR tag $tag")

private fun Map<*, *>.uint(key: Long): Long = this[key] as? Long ?: throw IllegalArgumentException("expected a CBOR uint at $key")

private fun Map<*, *>.list(key: Long): List<*> = this[key] as? List<*> ?: throw IllegalArgumentException("expected a CBOR array at $key")
```

Note: KDoc must not contain `/*` (as in `<0;1>/*`), or Kotlin reads it as a nested comment opener.

- [ ] **Step 6: Run the tests**

Run: `JAVA_HOME=/home/box/jdk/current ./gradlew :wallet:jvmTest --tests '*UrAccountsTest*' --tests '*UrDecoderTest*' --tests '*EncodePsbtUrTest*' --offline`
Expected: PASS (UrAccountsTest 9 tests; the PSBT UR tests unchanged).

- [ ] **Step 7: Commit**

```bash
git add wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/EncodePsbtUr.kt wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/UrAccounts.kt wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/UrAccountsVectors.kt wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/UrAccountsTest.kt
git commit -m "Parse BC-UR account exports into single-sig accounts"
```

### Task 2: Decoder reason and type for account scans, animated round trip

**Files:**
- Modify: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/UrDecoder.kt` (class header)
- Modify: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/EncodePsbtUr.kt:49` (`UrEncoder` visibility)
- Test: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/UrAccountsTest.kt`, `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/UrDecoderTest.kt`

**Interfaces:**
- Consumes: Task 1 (`ACCOUNT_UR_TYPES`, `UR_NOT_ACCOUNT`, `parseUrAccounts`, vectors).
- Produces:
  ```kotlin
  class UrDecoder(acceptedTypes: Set<String>, wrongTypeReason: String = UR_WRONG_TYPE) { val type: String? /* locked type, null before the first accepted part */ }
  internal class UrEncoder(message: ByteArray, urType: String, maxFragmentLength: Int, ...) { val fragmentsLength: Int; fun nextPart(): String }
  ```

- [ ] **Step 1: Write the failing tests.** Add `import kotlin.random.Random` to `UrAccountsTest.kt` and append inside the class:

```kotlin
    @Test
    fun animated_multi_accounts_round_trip_through_the_decoder() {
        val encoder = UrEncoder(hexToBytes(KEYSTONE_MULTI_ACCOUNTS), "crypto-multi-accounts", 400)
        val parts = List(encoder.fragmentsLength * 2) { encoder.nextPart().uppercase() }.shuffled(Random(7))
        val decoder = UrDecoder(ACCOUNT_UR_TYPES)
        val complete = parts.map { decoder.receive(it) }.filterIsInstance<UrReceive.Complete>().first()
        assertEquals("crypto-multi-accounts", decoder.type)
        assertEquals(KEYSTONE_MULTI_DESCRIPTORS, parseUrAccounts(decoder.type!!, complete.message).map { it.descriptor })
    }
```

In `UrDecoderTest.kt`, add before `fun foreign_type_is_ignored()`:

```kotlin
    @Test
    fun a_foreign_type_gets_the_decoder_s_own_reason() {
        val decoder = UrDecoder(ACCOUNT_UR_TYPES, UR_NOT_ACCOUNT)
        assertEquals(UrReceive.Ignored(UR_NOT_ACCOUNT), decoder.receive(BW_PARTS[0]))
        assertNull(decoder.type)
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `JAVA_HOME=/home/box/jdk/current ./gradlew :wallet:jvmTest --tests '*UrAccountsTest*' --tests '*UrDecoderTest*' --offline`
Expected: FAIL, compilation errors (`Cannot access 'UrEncoder': it is private`, no `UrDecoder(Set, String)` constructor, `Unresolved reference 'type'`).

- [ ] **Step 3: Implement.** In `EncodePsbtUr.kt` change `private class UrEncoder(` to `internal class UrEncoder(`. In `UrDecoder.kt` replace

```kotlin
class UrDecoder internal constructor(
    private val acceptedTypes: Set<String>,
    private val newFountain: () -> FountainDecoder,
) {
    constructor(acceptedTypes: Set<String>) : this(acceptedTypes, ::FountainDecoder)

    private var type: String? = null
```

with

```kotlin
class UrDecoder internal constructor(
    private val acceptedTypes: Set<String>,
    private val wrongTypeReason: String = UR_WRONG_TYPE,
    private val newFountain: () -> FountainDecoder,
) {
    /** [wrongTypeReason] is the [UrReceive.Ignored] reason for a UR of a type not in [acceptedTypes]. */
    constructor(acceptedTypes: Set<String>, wrongTypeReason: String = UR_WRONG_TYPE) : this(acceptedTypes, wrongTypeReason, ::FountainDecoder)

    /** The UR type locked in by the first accepted part; null before it. */
    var type: String? = null
        private set
```

and in `receivePart` replace `UrReceive.Ignored(UR_WRONG_TYPE)` with `UrReceive.Ignored(wrongTypeReason)`. (The default sits before the lambda, so the existing `UrDecoder(PSBT_UR_TYPES) { ... }` test call still compiles.)

- [ ] **Step 4: Run the tests**

Run: `JAVA_HOME=/home/box/jdk/current ./gradlew :wallet:jvmTest --tests '*UrAccountsTest*' --tests '*UrDecoderTest*' --tests '*EncodePsbtUrTest*' --offline`
Expected: PASS (UrAccountsTest 10, UrDecoderTest 24).

- [ ] **Step 5: Commit**

```bash
git add wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/UrDecoder.kt wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/EncodePsbtUr.kt wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/UrAccountsTest.kt wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/UrDecoderTest.kt
git commit -m "Let a UR decoder name its own wrong-type reason and report its type"
```

### Task 3: Camera scan state for account exports

**Files:**
- Create: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/AccountsScan.kt`
- Test: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/AccountsScanTest.kt`

**Interfaces:**
- Consumes: `UrDecoder(Set<String>, String)`, `UrDecoder.type`, `UrDecoder.percent`, `UrReceive`, `ACCOUNT_UR_TYPES`, `UR_NOT_ACCOUNT`, `UR_NOT_UR`, `UR_CORRUPT`, `parseUrAccounts`, `UrAccount`, `encodeCryptoPsbtUrFragments(ByteArray)` (test only).
- Produces:
  ```kotlin
  internal const val NO_ACCOUNTS: String     // "No single-sig Bitcoin accounts in this QR."
  internal sealed class AccountsScanState { data class Scanning(val percent: Int?, val note: String?); data class Accounts(val accounts: List<UrAccount>); data class Text(val text: String) }
  internal class AccountsScan { val state: AccountsScanState; fun accept(text: String): AccountsScanState }
  ```

- [ ] **Step 1: Write the failing tests** (`AccountsScanTest.kt`; the four parts are BlueWallet's 3-account abandon `crypto-account` re-encoded by `UrEncoder(message, "crypto-account", 120)`, and the hdkey UR is the first ETH key of the Keystone multi-accounts vector with a bytewords CRC32)

```kotlin
package io.bluewallet.blueberry

import io.bluewallet.blueberry.wallet.UR_NOT_ACCOUNT
import io.bluewallet.blueberry.wallet.UR_NOT_UR
import io.bluewallet.blueberry.wallet.encodeCryptoPsbtUrFragments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AccountsScanTest {
    @Test
    fun animated_account_frames_progress_and_end_in_accounts() {
        val scan = AccountsScan()
        var last = 0
        for (part in ABANDON_ACCOUNT_PARTS.dropLast(1)) {
            val percent = assertIs<AccountsScanState.Scanning>(scan.accept(part)).percent ?: error("no progress")
            assertTrue(percent >= last)
            last = percent
        }
        val done = assertIs<AccountsScanState.Accounts>(scan.accept(ABANDON_ACCOUNT_PARTS.last()))
        assertEquals(listOf("wpkh(", "sh(wpkh(", "pkh("), done.accounts.map { it.descriptor.substringBefore('[') })
    }

    @Test
    fun a_qr_that_is_not_a_ur_is_handed_over_as_text() {
        assertEquals(AccountsScanState.Text(ZPUB), AccountsScan().accept(ZPUB))
    }

    @Test
    fun text_after_an_animation_started_is_ignored_and_keeps_progress() {
        val scan = AccountsScan()
        val started = assertIs<AccountsScanState.Scanning>(scan.accept(ABANDON_ACCOUNT_PARTS[0]))
        assertEquals(AccountsScanState.Scanning(started.percent, UR_NOT_UR), scan.accept(ZPUB))
    }

    @Test
    fun a_psbt_qr_is_not_an_account_export() {
        val psbt = encodeCryptoPsbtUrFragments(byteArrayOf(0x70, 0x73, 0x62, 0x74)).single()
        assertEquals(AccountsScanState.Scanning(null, UR_NOT_ACCOUNT), AccountsScan().accept(psbt))
    }

    @Test
    fun an_export_without_bitcoin_accounts_says_so_and_keeps_scanning() {
        val scan = AccountsScan()
        assertEquals(AccountsScanState.Scanning(null, NO_ACCOUNTS), scan.accept(ETH_HDKEY_UR))
        assertEquals(AccountsScanState.Scanning(null, NO_ACCOUNTS), scan.accept(ETH_HDKEY_UR))
        assertIs<AccountsScanState.Scanning>(scan.accept(ABANDON_ACCOUNT_PARTS[0]))
        assertIs<AccountsScanState.Accounts>(ABANDON_ACCOUNT_PARTS.drop(1).map { scan.accept(it) }.last())
    }

    private companion object {
        const val ZPUB = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"

        // BlueWallet's 3-account abandon crypto-account (watch-only-wallet.test.js line 1125), re-encoded by our UrEncoder at 120 bytes.
        val ABANDON_ACCOUNT_PARTS =
            listOf(
                "ur:crypto-account/1-4/lpadaacfadjecysopajslnhdhpoeadcyjksktnbkaolstaadmwtaaddlonaxhdclaojoknidzcpssajtptrpfrcecfkkamykjtvtcsbtbdtkcfiyvyoetneeykwfnbnyndaahdcxgegunbpyclrhuomdlnnsglmooyhscfglaxrtwsfhykadgeswmowkeosskoghmhztamtaaddytoosamld",
                "ur:crypto-account/2-4/lpaoaacfadjecysopajslnhdhpotadlncsghykaeykaeykaocyjksktnbkaxaxattaaddyoyadlraewklawkaycykbwfdnuytaadmhtaadmwtaaddlonaxhdclaownwfflldcwcxylhfmnplfmsolnnersrpkgsgrpwfhdeyjlbewpsscnhfrygomuntaahdcxjtpkvlihplbabksofgisgu",
                "ur:crypto-account/3-4/lpaxaacfadjecysopajslnhdhpbkpylreyhhzeketsjzfrmhmhecyaldvdtewnroflptnbkkkbsbamtaaddyotadlncsehykaeykaeykaocyjksktnbkaxaxattaaddyoyadlraewklawkaycyfsahzmkptaadmutaaddlonaxhdclaxktgsmebstkatzsmtlojtosmwwttlsgwevecagdut",
                "ur:crypto-account/4-4/lpaaaacfadjecysopajslnhdhpnyzedyqzgrlsylvobwrkmomubakiwkryaahdcxfsoxrfcfbkdslabycaehzsuruomhhedrlbjzvdkejlbenlcfbyjldafsfxfygmcfamtaaddyotadlncsdwykaeykaeykaocyjksktnbkaxaxattaaddyoyadlraewklawkaycybzhpsghkaepfrlsrrk",
            )

        // Keystone's first ETH key (m/44'/60'/0') from the crypto-multi-accounts vector, as a crypto-hdkey UR.
        const val ETH_HDKEY_UR =
            "ur:crypto-hdkey/pdaowkaxhdclaxsspmdacpimmkkogwkgkpfrjzkofrvybgsofzcsdmbdiymkmwgsntwsdafnwpfgkoaahdcxrsbefnzsrfpyeyhtjyiapyhhfxdrfzfwbemokktbsrgucelkhyosbgisgswfctemamtaaddyotadlncsdwykcsfnykaeykaocybggdrprfaxaxattaaddyoeadlraewklawkaxaeaycyiaskwdioasisgrihkkjkjyjljtihbkjohsiaiajlkpjtjydmjkjyhsjtiehsjpieatztvyga"
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `JAVA_HOME=/home/box/jdk/current ./gradlew :shared:jvmTest --tests '*AccountsScanTest*' --offline`
Expected: FAIL, `Unresolved reference 'AccountsScan'`.

- [ ] **Step 3: Implement** (`AccountsScan.kt`)

```kotlin
package io.bluewallet.blueberry

import io.bluewallet.blueberry.wallet.ACCOUNT_UR_TYPES
import io.bluewallet.blueberry.wallet.UR_CORRUPT
import io.bluewallet.blueberry.wallet.UR_NOT_ACCOUNT
import io.bluewallet.blueberry.wallet.UR_NOT_UR
import io.bluewallet.blueberry.wallet.UrAccount
import io.bluewallet.blueberry.wallet.UrDecoder
import io.bluewallet.blueberry.wallet.UrReceive
import io.bluewallet.blueberry.wallet.parseUrAccounts

internal const val NO_ACCOUNTS = "No single-sig Bitcoin accounts in this QR."

internal sealed class AccountsScanState {
    /** [percent] is null until an animated QR part is read; [note] is why the last text didn't count. */
    data class Scanning(
        val percent: Int?,
        val note: String?,
    ) : AccountsScanState()

    /** A complete account export; never empty. */
    data class Accounts(
        val accounts: List<UrAccount>,
    ) : AccountsScanState()

    /** A QR that isn't a UR, for the import text field. */
    data class Text(
        val text: String,
    ) : AccountsScanState() {
        override fun toString(): String = "Text([redacted])"
    }
}

/**
 * Camera text in, accounts out: single or animated `ur:crypto-account`, `ur:crypto-multi-accounts` or
 * `ur:crypto-hdkey` frames. Any other QR read before a UR part is handed over as text. Not thread-safe;
 * feed it from one thread.
 */
internal class AccountsScan {
    private val decoder = UrDecoder(ACCOUNT_UR_TYPES, UR_NOT_ACCOUNT)
    private var lastText: String? = null

    // Why the last complete scan didn't count; stays up while frames keep coming.
    private var failure: String? = null

    var state: AccountsScanState = AccountsScanState.Scanning(null, null)
        private set

    fun accept(text: String): AccountsScanState {
        if (state !is AccountsScanState.Scanning || text == lastText) return state
        lastText = text
        val trimmed = text.trim()
        state =
            when {
                trimmed.startsWith("ur:", ignoreCase = true) -> fromUr(decoder.receive(trimmed))
                decoder.percent == null -> AccountsScanState.Text(text)
                else -> scanning(UR_NOT_UR)
            }
        return state
    }

    private fun fromUr(step: UrReceive): AccountsScanState =
        when (step) {
            is UrReceive.Progress -> scanning(null)
            is UrReceive.Ignored -> scanning(step.reason)
            is UrReceive.Failed -> fail(step.reason)
            is UrReceive.Complete -> {
                val accounts = runCatching { parseUrAccounts(checkNotNull(decoder.type), step.message) }.getOrNull()
                when {
                    accounts == null -> fail(UR_CORRUPT)
                    accounts.isEmpty() -> fail(NO_ACCOUNTS)
                    else -> AccountsScanState.Accounts(accounts)
                }
            }
        }

    /** Starts the decoder over and keeps [reason] up; the last text is kept, so the same QR isn't parsed again. */
    private fun fail(reason: String): AccountsScanState {
        decoder.reset()
        failure = reason
        return scanning(null)
    }

    private fun scanning(note: String?): AccountsScanState = AccountsScanState.Scanning(decoder.percent, failure ?: note)
}
```

- [ ] **Step 4: Run the tests**

Run: `JAVA_HOME=/home/box/jdk/current ./gradlew :shared:jvmTest --tests '*AccountsScanTest*' --tests '*SignedPsbtScanTest*' --offline`
Expected: PASS (AccountsScanTest 5).

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/io/bluewallet/blueberry/AccountsScan.kt shared/src/commonTest/kotlin/io/bluewallet/blueberry/AccountsScanTest.kt
git commit -m "Scan state for single and animated account export QRs"
```

### Task 4: Onboarding picks and imports a scanned account

**Files:**
- Modify: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/onboarding/OnboardingFlow.kt`
- Test: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/onboarding/OnboardingFlowTest.kt`

**Interfaces:**
- Consumes: `UrAccount`, `UrAccount.descriptor`, `AddressScriptType`, existing `OnboardingEffect.PersistImportedSecret`, `OnboardingEvent.PersistImportOk`.
- Produces:
  ```kotlin
  data class OnboardingState(..., val accounts: List<UrAccount> = emptyList())
  fun preselectedAccount(accounts: List<UrAccount>): UrAccount?   // first P2WPKH, else first, null when empty
  OnboardingEvent.ScannedAccounts(val accounts: List<UrAccount>)
  OnboardingEvent.PickAccount(val account: UrAccount)
  ```

- [ ] **Step 1: Write the failing tests.** In `OnboardingFlowTest.kt` add imports `fr.acinq.bitcoin.KeyPath`, `io.bluewallet.blueberry.wallet.AddressScriptType`, `io.bluewallet.blueberry.wallet.UrAccount`; after the `abandon` field add

```kotlin
    // Keystone's abandon account keys (spec appendix A), as the scanner hands them over.
    private val scannedTaproot =
        UrAccount(
            AddressScriptType.P2TR,
            0x73c5da0aL,
            KeyPath("m/86'/0'/0'"),
            "xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteoGVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ",
        )
    private val scannedNativeSegwit =
        UrAccount(
            AddressScriptType.P2WPKH,
            0x73c5da0aL,
            KeyPath("m/84'/0'/0'"),
            "xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3XyuvPEbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V",
        )
```

and append inside the class:

```kotlin
    @Test
    fun one_scanned_account_is_imported_without_a_picker() {
        val import = reduceOnboarding(initialOnboardingState(false), OnboardingEvent.ChooseImport).state
        val result = reduceOnboarding(import, OnboardingEvent.ScannedAccounts(listOf(scannedTaproot)))
        assertEquals(OnboardingEffect.PersistImportedSecret(scannedTaproot.descriptor), result.effect)
        assertEquals(scannedTaproot.descriptor, result.state.importValue)
        assertTrue(result.state.accounts.isEmpty())
        assertTrue(result.state.busy)
        val year = reduceOnboarding(result.state, OnboardingEvent.PersistImportOk).state
        assertEquals(OnboardingStep.Year, year.step)
    }

    @Test
    fun several_scanned_accounts_show_the_picker_and_import_the_picked_one() {
        val import = reduceOnboarding(initialOnboardingState(false), OnboardingEvent.ChooseImport).state
        val accounts = listOf(scannedTaproot, scannedNativeSegwit)
        val picking = reduceOnboarding(import, OnboardingEvent.ScannedAccounts(accounts))
        assertNull(picking.effect)
        assertEquals(accounts, picking.state.accounts)
        assertEquals(scannedNativeSegwit, preselectedAccount(picking.state.accounts))
        assertEquals(picking.state, reduceOnboarding(picking.state, OnboardingEvent.ImportChanged("typed")).state)

        val picked = reduceOnboarding(picking.state, OnboardingEvent.PickAccount(scannedTaproot))
        assertEquals(OnboardingEffect.PersistImportedSecret(scannedTaproot.descriptor), picked.effect)
        assertTrue(picked.state.accounts.isEmpty())
        parseWalletSecret(picked.state.importValue)
    }

    @Test
    fun back_from_the_picker_returns_to_the_import_field() {
        val import = reduceOnboarding(initialOnboardingState(false), OnboardingEvent.ChooseImport).state
        val picking = reduceOnboarding(import, OnboardingEvent.ScannedAccounts(listOf(scannedTaproot, scannedNativeSegwit))).state
        val back = reduceOnboarding(picking, OnboardingEvent.Back)
        assertEquals(OnboardingStep.Import, back.state.step)
        assertTrue(back.state.accounts.isEmpty())
        assertNull(back.effect)
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `JAVA_HOME=/home/box/jdk/current ./gradlew :shared:jvmTest --tests '*OnboardingFlowTest*' --offline`
Expected: FAIL, `Unresolved reference 'ScannedAccounts'`, `'preselectedAccount'`, `'accounts'`.

- [ ] **Step 3: Implement** in `OnboardingFlow.kt`:

1. Imports: add `io.bluewallet.blueberry.wallet.AddressScriptType` and `io.bluewallet.blueberry.wallet.UrAccount`.
2. Replace the end of `OnboardingState` and add `preselectedAccount`:

```kotlin
    val busy: Boolean = false,
    /** Accounts of a scanned export to pick from; empty when there is nothing to pick. */
    val accounts: List<UrAccount> = emptyList(),
) {
    override fun toString(): String =
        "OnboardingState(step=$step, importValue=[redacted], error=$error, mnemonic=[redacted], selectedYear=$selectedYear, busy=$busy, accounts=${accounts.size})"
}

/** The picker's starting row: the first Native SegWit account, else the first. */
fun preselectedAccount(accounts: List<UrAccount>): UrAccount? = accounts.firstOrNull { it.scriptType == AddressScriptType.P2WPKH } ?: accounts.firstOrNull()
```

3. After `data object SubmitImport : OnboardingEvent()` add:

```kotlin
    /** A scanned account export with at least one account. */
    data class ScannedAccounts(
        val accounts: List<UrAccount>,
    ) : OnboardingEvent() {
        override fun toString(): String = "ScannedAccounts(${accounts.size})"
    }

    data class PickAccount(
        val account: UrAccount,
    ) : OnboardingEvent()
```

4. In `reduceOnboarding`, `Back`: replace

```kotlin
                OnboardingStep.Create, OnboardingStep.Import ->
                    OnboardingReduction(OnboardingState(step = OnboardingStep.Choose))
```

with

```kotlin
                OnboardingStep.Create, OnboardingStep.Import ->
                    if (state.accounts.isNotEmpty()) {
                        // Back from the account picker returns to the import field.
                        OnboardingReduction(state.copy(accounts = emptyList()))
                    } else {
                        OnboardingReduction(OnboardingState(step = OnboardingStep.Choose))
                    }
```

5. Replace the `ImportChanged` branch with it plus the two new branches:

```kotlin
        is OnboardingEvent.ImportChanged -> {
            if (state.step != OnboardingStep.Import || state.accounts.isNotEmpty()) return OnboardingReduction(state)
            OnboardingReduction(state.copy(importValue = event.value, error = null))
        }
        is OnboardingEvent.ScannedAccounts -> {
            if (state.step != OnboardingStep.Import || event.accounts.isEmpty()) return OnboardingReduction(state)
            event.accounts.singleOrNull()?.let { return importAccount(state, it) }
            OnboardingReduction(state.copy(accounts = event.accounts, error = null))
        }
        is OnboardingEvent.PickAccount -> {
            if (state.step != OnboardingStep.Import || event.account !in state.accounts) return OnboardingReduction(state)
            importAccount(state, event.account)
        }
```

6. At the end of the file:

```kotlin
/** Imports [account] as its descriptor, as Continue does for a typed secret. */
private fun importAccount(
    state: OnboardingState,
    account: UrAccount,
): OnboardingReduction =
    OnboardingReduction(
        state.copy(importValue = account.descriptor, accounts = emptyList(), busy = true, error = null),
        OnboardingEffect.PersistImportedSecret(account.descriptor),
    )
```

- [ ] **Step 4: Run the tests**

Run: `JAVA_HOME=/home/box/jdk/current ./gradlew :shared:jvmTest --tests '*Onboarding*' --offline`
Expected: PASS (OnboardingFlowTest 14).

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/io/bluewallet/blueberry/onboarding/OnboardingFlow.kt shared/src/commonTest/kotlin/io/bluewallet/blueberry/onboarding/OnboardingFlowTest.kt
git commit -m "Onboarding imports a scanned account, with a picker for several"
```

### Task 5: Scanner and picker on the Import page

**Files:**
- Create: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/onboarding/ImportAccounts.kt`
- Modify: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/onboarding/OnboardingApp.kt` (`import io.bluewallet.blueberry.QrScanOverlay`, `ImportWalletPage` Continue, `ImportEntry`)

**Interfaces:**
- Consumes: `AccountsScan`, `AccountsScanState`, `QrFrameScanner(onText: (String) -> Boolean, modifier)`, `preselectedAccount`, `OnboardingEvent.ScannedAccounts`, `OnboardingEvent.PickAccount`, `AddressScriptType.receiveLabel()`.
- Produces: `internal fun ImportScanner(onText, onAccounts, modifier)`, `internal fun AccountPicker(accounts, picked, enabled, onPick, modifier)`.

No unit test (Compose, repo convention); the behaviour is covered by Tasks 3 and 4.

- [ ] **Step 1: Create `ImportAccounts.kt`**

```kotlin
package io.bluewallet.blueberry.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.AccountsScan
import io.bluewallet.blueberry.AccountsScanState
import io.bluewallet.blueberry.QrFrameScanner
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.PlatformBodyStyle
import io.bluewallet.blueberry.wallet.UrAccount
import io.bluewallet.blueberry.wallet.receiveLabel

/** Camera for Import: a plain QR fills the field; an account export, single or animated, hands over its accounts. */
@Composable
internal fun ImportScanner(
    onText: (String) -> Unit,
    onAccounts: (List<UrAccount>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scan = remember { AccountsScan() }
    var state by remember { mutableStateOf(scan.state) }
    val scanning = state as? AccountsScanState.Scanning
    Column(modifier = modifier.background(BwColors.Paper)) {
        Text(
            text =
                when {
                    scanning?.percent != null -> "Scanning animated QR… ${scanning.percent}%"
                    scanning?.note != null -> scanning.note
                    else -> "Point the camera at a QR code"
                },
            color = BwColors.InkMuted,
            fontFamily = BwFontFamily,
            fontSize = BwType.CaptionSize,
            fontWeight = BwType.Caption,
        )
        QrFrameScanner(
            onText = { text ->
                val next = scan.accept(text)
                state = next
                when (next) {
                    is AccountsScanState.Text -> onText(next.text)
                    is AccountsScanState.Accounts -> onAccounts(next.accounts)
                    is AccountsScanState.Scanning -> Unit
                }
                next !is AccountsScanState.Scanning
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/** One row per scanned account; [picked] is highlighted (Continue imports it), and tapping a row imports that row. */
@Composable
internal fun AccountPicker(
    accounts: List<UrAccount>,
    picked: UrAccount,
    enabled: Boolean,
    onPick: (UrAccount) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Choose the account to import", style = PlatformBodyStyle())
        for (account in accounts) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(if (account == picked) BwColors.AccentSoft else BwColors.Section, RoundedCornerShape(8.dp))
                        .clickable(enabled = enabled) { onPick(account) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(text = account.scriptType.receiveLabel(), style = PlatformBodyStyle(), color = BwColors.Ink)
                Text(
                    text = "${account.path.asString('\'')} · ${account.fingerprint.toString(16).padStart(8, '0')}",
                    color = BwColors.InkMuted,
                    fontFamily = BwFontFamily,
                    fontSize = BwType.CaptionSize,
                )
            }
        }
    }
}
```

- [ ] **Step 2: Wire it in `OnboardingApp.kt`.** Remove `import io.bluewallet.blueberry.QrScanOverlay`. In `ImportWalletPage`'s Continue `onClick` replace `dispatch(OnboardingEvent.SubmitImport)` with

```kotlin
                    dispatch(preselectedAccount(state.accounts)?.let { OnboardingEvent.PickAccount(it) } ?: OnboardingEvent.SubmitImport)
```

In `ImportEntry` replace

```kotlin
    val error = if (state.busy) null else state.error
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (scanning) {
            QrScanOverlay(
                modifier = Modifier.fillMaxSize(),
                onResult = { payload ->
                    dispatch(OnboardingEvent.ImportChanged(payload))
                    onScanningChange(false)
                },
            )
        } else {
```

with

```kotlin
    val error = if (state.busy) null else state.error
    val picked = preselectedAccount(state.accounts)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (picked != null) {
            AccountPicker(
                accounts = state.accounts,
                picked = picked,
                enabled = !state.busy,
                onPick = { dispatch(OnboardingEvent.PickAccount(it)) },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (scanning) {
            ImportScanner(
                onText = { text ->
                    dispatch(OnboardingEvent.ImportChanged(text))
                    onScanningChange(false)
                },
                onAccounts = { accounts ->
                    onScanningChange(false)
                    dispatch(OnboardingEvent.ScannedAccounts(accounts))
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
```

- [ ] **Step 3: Full checks**

Run: `JAVA_HOME=/home/box/jdk/current ./gradlew :shared:compileKotlinJvm :shared:jvmTest :wallet:jvmTest ktlintCheck detekt --offline`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Desktop smoke check.** `./run-desktop.sh`, Import wallet → Scan shows "Point the camera at a QR code" (no camera on the box is fine); Back closes the camera.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/io/bluewallet/blueberry/onboarding/ImportAccounts.kt shared/src/commonMain/kotlin/io/bluewallet/blueberry/onboarding/OnboardingApp.kt
git commit -m "Scan account export QRs at onboarding and pick the account to import"
```

### Task 6: Review loop and verification

- [ ] Run the review-loop skill until a round has no major issues; fix findings test-first.
- [ ] Full checks: `JAVA_HOME=/home/box/jdk/current ./gradlew :shared:compileKotlinJvm :shared:jvmTest :wallet:jvmTest :parse:jvmTest :sync:jvmTest :filters:jvmTest :headers:jvmTest ktlintCheck detekt --offline`.
- [ ] Compile Android if the SDK is present: `JAVA_HOME=/home/box/jdk/current ./gradlew :shared:compileDebugKotlinAndroid --offline`. iOS needs macOS (skip).
- [ ] Device QA with a real export (Keystone or Sparrow, single and animated) before the PR.
