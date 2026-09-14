# Multi-script mnemonic wallets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mnemonic wallets track BIP44, BIP49, BIP84, and BIP86 together, discover and spend all four script types, pick a receive type (default BIP84), and send change to a random type’s first unused internal address.

**Architecture:** One `WatchWallet` holds all four account paths. `HdWatchGaps` stores eight independent windows. Used indexes are per script type. Receive preference is KV. Send already signs all four types; change picking and gap grow move into `:wallet` / `:parse`.

**Tech Stack:** Kotlin Multiplatform, bitcoin-kmp, Compose Multiplatform Material3, `:wallet` `:parse` `:filters` `:shared`.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-09-14-multi-script-mnemonic-design.md`
- Package stays `io.bluewallet.blueberry.wallet` / `io.bluewallet.blueberry.parse` / `io.bluewallet.blueberry`
- `INITIAL_WATCH_COUNT = 20`, `GAP_LIMIT = 20`, `MAX_WATCH_COUNT = 10_000`
- Mainnet account 0 only in the live watch list
- `zpub` stays BIP84 only
- WIF and imported address do not change
- No old-key migration. Replace `watch_external` / `watch_internal`
- Mnemonic send must not throw `no unused change address in watch window`
- Do not put `PillButton` in a screen header
- Do not format or lint `vendor/*`
- Pass `./gradlew :wallet:jvmTest :parse:jvmTest :filters:jvmTest :shared:jvmTest` before claiming done
- Run `./gradlew ktlintCheck` and `./gradlew detekt` before the last commit of Kotlin/Gradle changes
- Do not commit unless the user asks
- Linux host: do not run iOS simulator tests

## File structure

```
wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Constants.kt
wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Types.kt
wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/WatchGaps.kt
wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Derive.kt
wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Wallet.kt
wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/ReceiveAddress.kt
wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/ReceivePreference.kt
wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/MnemonicChange.kt
parse/src/commonMain/kotlin/io/bluewallet/blueberry/parse/Types.kt
parse/src/commonMain/kotlin/io/bluewallet/blueberry/parse/UsedIndexes.kt
parse/src/commonMain/kotlin/io/bluewallet/blueberry/parse/SendContext.kt
parse/src/commonMain/kotlin/io/bluewallet/blueberry/parse/modules/ParseBlocks.kt
filters/src/commonMain/kotlin/io/bluewallet/blueberry/filters/modules/FiltersMatching.kt
shared/src/commonMain/kotlin/io/bluewallet/blueberry/ReceiveScreen.kt
wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/*.kt
parse/src/commonTest/kotlin/io/bluewallet/blueberry/parse/*.kt
filters/src/commonTest/kotlin/io/bluewallet/blueberry/filters/modules/FiltersMatchingTest.kt
```

---

### Task 1: Constants, `HdWatchGaps`, new gap KV

**Files:**
- Modify: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Constants.kt`
- Modify: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Types.kt`
- Modify: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/WatchGaps.kt`
- Test: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/WatchGapsTest.kt`

**Interfaces:**
- Consumes: existing `WatchGaps`, `parseInt10`, `Database`
- Produces: `BIP44_ACCOUNT_PATH`, `BIP49_ACCOUNT_PATH`, `BIP86_ACCOUNT_PATH`, eight KV key constants, `HD_SCRIPT_TYPES`, `AddressScriptType.accountPath()`, `HdWatchGaps`, `UsedChainIndexes`, `UsedHdIndexes`, `loadHdWatchGaps`, `saveHdWatchGaps`, `growHdWatchGapsIfNeeded`. Keep today’s `loadWatchGaps` / `saveWatchGaps` / `growWatchGapsIfNeeded` working.

- [ ] **Step 1: Write the failing HdWatchGaps persistence test**

Add this test to `WatchGapsTest.kt` (keep the old tests; they still use `loadWatchGaps`):

```kotlin
@Test
fun hd_gaps_default_and_independent_types() {
    val db = createSqliteDatabase(":memory:")
    val loaded = loadHdWatchGaps(db)
    assertEquals(HdWatchGaps.initial(), loaded)
    assertEquals(INITIAL_WATCH_COUNT.toString(), db.keyValue.get(WATCH_EXTERNAL_P2WPKH_KEY))
    assertEquals(INITIAL_WATCH_COUNT.toString(), db.keyValue.get(WATCH_INTERNAL_P2PKH_KEY))
    saveHdWatchGaps(
        db,
        HdWatchGaps.initial().with(
            AddressScriptType.P2TR,
            WatchGaps(40, 21),
        ),
    )
    val again = loadHdWatchGaps(db)
    assertEquals(WatchGaps(40, 21), again[AddressScriptType.P2TR])
    assertEquals(WatchGaps(INITIAL_WATCH_COUNT, INITIAL_WATCH_COUNT), again[AddressScriptType.P2WPKH])
    db.close()
}

@Test
fun hd_grow_one_type_only() {
    val start = HdWatchGaps.uniform(WatchGaps(40, 40))
    val used =
        UsedHdIndexes(
            mapOf(
                AddressScriptType.P2WPKH to UsedChainIndexes(listOf(25), emptyList()),
            ),
        )
    val r = growHdWatchGapsIfNeeded(start, used, 20)
    assertTrue(r.grew)
    assertEquals(WatchGaps(60, 40), r.gaps[AddressScriptType.P2WPKH])
    assertEquals(WatchGaps(40, 40), r.gaps[AddressScriptType.P2PKH])
    assertEquals(WatchGaps(40, 40), r.gaps[AddressScriptType.P2TR])
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.WatchGapsTest`

Expected: compile FAIL (`loadHdWatchGaps` unresolved) or test FAIL.

- [ ] **Step 3: Write minimal implementation**

`Constants.kt`:

```kotlin
const val BIP44_ACCOUNT_PATH = "m/44'/0'/0'"
const val BIP49_ACCOUNT_PATH = "m/49'/0'/0'"
const val BIP84_ACCOUNT_PATH = "m/84'/0'/0'"
const val BIP86_ACCOUNT_PATH = "m/86'/0'/0'"
const val WALLET_SECRET_KEY = "wallet_secret"
const val WATCH_EXTERNAL_KEY = "watch_external"
const val WATCH_INTERNAL_KEY = "watch_internal"
const val WATCH_EXTERNAL_P2PKH_KEY = "watch_external_p2pkh"
const val WATCH_INTERNAL_P2PKH_KEY = "watch_internal_p2pkh"
const val WATCH_EXTERNAL_P2SH_P2WPKH_KEY = "watch_external_p2sh_p2wpkh"
const val WATCH_INTERNAL_P2SH_P2WPKH_KEY = "watch_internal_p2sh_p2wpkh"
const val WATCH_EXTERNAL_P2WPKH_KEY = "watch_external_p2wpkh"
const val WATCH_INTERNAL_P2WPKH_KEY = "watch_internal_p2wpkh"
const val WATCH_EXTERNAL_P2TR_KEY = "watch_external_p2tr"
const val WATCH_INTERNAL_P2TR_KEY = "watch_internal_p2tr"
const val RECEIVE_SCRIPT_TYPE_KEY = "receive_script_type"
const val INITIAL_WATCH_COUNT = 20
const val GAP_LIMIT = 20
const val MAX_WATCH_COUNT = 10_000
```

Keep the other existing constants (`WALLET_BIRTHDAY_HEIGHT_KEY`, `WALLET_BIRTHDAY_PENDING`, `BC_UR_PSBT_CAPACITY`).

`Types.kt` — add after `WatchGaps`:

```kotlin
val HD_SCRIPT_TYPES =
    listOf(
        AddressScriptType.P2PKH,
        AddressScriptType.P2SH_P2WPKH,
        AddressScriptType.P2WPKH,
        AddressScriptType.P2TR,
    )

fun AddressScriptType.accountPath(): String =
    when (this) {
        AddressScriptType.P2PKH -> BIP44_ACCOUNT_PATH
        AddressScriptType.P2SH_P2WPKH -> BIP49_ACCOUNT_PATH
        AddressScriptType.P2WPKH -> BIP84_ACCOUNT_PATH
        AddressScriptType.P2TR -> BIP86_ACCOUNT_PATH
    }

fun AddressScriptType.watchKeys(): Pair<String, String> =
    when (this) {
        AddressScriptType.P2PKH -> WATCH_EXTERNAL_P2PKH_KEY to WATCH_INTERNAL_P2PKH_KEY
        AddressScriptType.P2SH_P2WPKH -> WATCH_EXTERNAL_P2SH_P2WPKH_KEY to WATCH_INTERNAL_P2SH_P2WPKH_KEY
        AddressScriptType.P2WPKH -> WATCH_EXTERNAL_P2WPKH_KEY to WATCH_INTERNAL_P2WPKH_KEY
        AddressScriptType.P2TR -> WATCH_EXTERNAL_P2TR_KEY to WATCH_INTERNAL_P2TR_KEY
    }

fun AddressScriptType.receiveLabel(): String =
    when (this) {
        AddressScriptType.P2PKH -> "Legacy (BIP44)"
        AddressScriptType.P2SH_P2WPKH -> "Nested SegWit (BIP49)"
        AddressScriptType.P2WPKH -> "Native SegWit (BIP84)"
        AddressScriptType.P2TR -> "Taproot (BIP86)"
    }

data class HdWatchGaps(
    val p2pkh: WatchGaps,
    val p2shP2wpkh: WatchGaps,
    val p2wpkh: WatchGaps,
    val p2tr: WatchGaps,
) {
    operator fun get(type: AddressScriptType): WatchGaps =
        when (type) {
            AddressScriptType.P2PKH -> p2pkh
            AddressScriptType.P2SH_P2WPKH -> p2shP2wpkh
            AddressScriptType.P2WPKH -> p2wpkh
            AddressScriptType.P2TR -> p2tr
        }

    fun with(
        type: AddressScriptType,
        gaps: WatchGaps,
    ): HdWatchGaps =
        when (type) {
            AddressScriptType.P2PKH -> copy(p2pkh = gaps)
            AddressScriptType.P2SH_P2WPKH -> copy(p2shP2wpkh = gaps)
            AddressScriptType.P2WPKH -> copy(p2wpkh = gaps)
            AddressScriptType.P2TR -> copy(p2tr = gaps)
        }

    companion object {
        fun uniform(gaps: WatchGaps): HdWatchGaps = HdWatchGaps(gaps, gaps, gaps, gaps)

        fun initial(): HdWatchGaps = uniform(WatchGaps(INITIAL_WATCH_COUNT, INITIAL_WATCH_COUNT))
    }
}

data class UsedChainIndexes(
    val external: List<Int> = emptyList(),
    val internal: List<Int> = emptyList(),
)

data class UsedHdIndexes(
    val byType: Map<AddressScriptType, UsedChainIndexes> = emptyMap(),
) {
    fun get(type: AddressScriptType): UsedChainIndexes = byType[type] ?: UsedChainIndexes()
}
```

`WatchGaps.kt` — add (keep existing `loadWatchGaps` / `saveWatchGaps` / `growWatchGapsIfNeeded`):

```kotlin
fun saveHdWatchGaps(
    db: Database,
    gaps: HdWatchGaps,
) {
    for (type in HD_SCRIPT_TYPES) {
        val pair = type.watchKeys()
        val g = gaps[type]
        db.keyValue.set(pair.first, g.external.toString())
        db.keyValue.set(pair.second, g.internal.toString())
    }
}

fun loadHdWatchGaps(db: Database): HdWatchGaps {
    fun parseKey(key: String): Int {
        val raw = db.keyValue.get(key)
        val n = parseInt10(raw)
        if (n == null || n < 0 || !n.isFinite()) return INITIAL_WATCH_COUNT
        return minOf(floor(n).toInt(), MAX_WATCH_COUNT)
    }

    var result = HdWatchGaps.initial()
    var dirty = false
    for (type in HD_SCRIPT_TYPES) {
        val (extKey, intKey) = type.watchKeys()
        val extRaw = db.keyValue.get(extKey)
        val intRaw = db.keyValue.get(intKey)
        val external = parseKey(extKey)
        val internal = parseKey(intKey)
        result = result.with(type, WatchGaps(external, internal))
        if (
            extRaw == null ||
            intRaw == null ||
            extRaw != external.toString() ||
            intRaw != internal.toString()
        ) {
            dirty = true
        }
    }
    if (dirty) saveHdWatchGaps(db, result)
    return result
}

data class GrowHdWatchGapsResult(
    val gaps: HdWatchGaps,
    val grew: Boolean,
)

fun growHdWatchGapsIfNeeded(
    gaps: HdWatchGaps,
    used: UsedHdIndexes,
    gapLimit: Int = GAP_LIMIT,
): GrowHdWatchGapsResult {
    var next = gaps
    var grew = false
    for (type in HD_SCRIPT_TYPES) {
        val chain = used.get(type)
        val one = growWatchGapsIfNeeded(gaps[type], chain.external, chain.internal, gapLimit)
        next = next.with(type, one.gaps)
        if (one.grew) grew = true
    }
    return GrowHdWatchGapsResult(next, grew)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.WatchGapsTest`

Expected: PASS. Existing default tests now expect `20` because `INITIAL_WATCH_COUNT` changed.

- [ ] **Step 5: Commit** (only if the user asked)

```bash
git add wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Constants.kt \
  wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Types.kt \
  wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/WatchGaps.kt \
  wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/WatchGapsTest.kt
git commit -m "$(cat <<'EOF'
feat: store independent HD watch gaps per script type

EOF
)"
```

---

### Task 2: Derive BIP44, BIP49, BIP84, BIP86

**Files:**
- Modify: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Derive.kt`
- Modify: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/TestVectors.kt`
- Modify: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/DeriveTest.kt`
- Modify: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/WalletTest.kt`
- Modify: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/BuildSendTxTest.kt`
- Modify: `filters/src/commonTest/kotlin/io/bluewallet/blueberry/filters/modules/FiltersMatchingTest.kt`

**Interfaces:**
- Consumes: `HdWatchGaps`, `AddressScriptType.accountPath()`, existing address helpers in `Derive.kt`
- Produces: mnemonic `deriveWatchWallet` emits four script types; `deriveWatchWallet(secret, WatchGaps)` applies that pair to every HD type; `zpub` uses only `gaps.p2wpkh`

- [ ] **Step 1: Write the failing vector tests**

Add to `TestVectors.kt`:

```kotlin
const val HONEY_BIP49 =
    "honey risk juice trip orient galaxy win situate shoot anchor bounce remind horse traffic exotic since escape mimic ramp skin judge owner topple erode"
const val BIP44_ABANDON_EXT_0 = "1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA"
const val BIP44_ACCOUNT1_EXT_0 = "15qucUWKf95Fo58FdCBhUTSAtsm22HHE2Q"
const val BIP44_ACCOUNT1_INT_0 = "1DgjtFUiXvqxGic9A9fiDPrHNyKC4cGtTH"
const val BIP49_HONEY_EXT_0 = "3GcKN7q7gZuZ8eHygAhHrvPa5zZbG5Q1rK"
const val BIP49_HONEY_EXT_1 = "35p5LwCAE7mH2css7onyQ1VuS1jgWtQ4U3"
const val BIP49_HONEY_INT_0 = "32yn5CdevZQLk3ckuZuA8fEKBco8mEkLei"
const val BIP86_ABANDON_EXT_0 = "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr"
const val BIP86_ABANDON_EXT_1 = "bc1p4qhjn9zdvkux4e44uhx8tc55attvtyu358kutcqkudyccelu0was9fqzwh"
const val BIP86_ABANDON_INT_0 = "bc1p3qkhfews2uk44qtvauqyr2ttdsw7svhkl9nkm9s9c3x4ax5h60wqwruhk7"

fun WatchWallet.hd(
    type: AddressScriptType,
    index: Int,
    change: Boolean = false,
): WatchAddress = addresses.first { it.resolvedScriptType() == type && it.index == index && it.change == change }
```

Replace `DeriveTest.abandon_mnemonic_matches_bluewallet_addresses` and `small_gaps_and_zpub_match_mnemonic` with:

```kotlin
@Test
fun abandon_mnemonic_matches_bip84_bip44_bip86_vectors() {
    val wallet = deriveWatchWallet(ABANDON, WatchGaps(2, 1))
    assertEquals(WatchWalletKind.BIP84, wallet.kind)
    assertEquals(12, wallet.addresses.size)
    assertEquals(BLUE_EXTERNAL_0, wallet.hd(AddressScriptType.P2WPKH, 0).address)
    assertEquals("m/84'/0'/0'/0/0", wallet.hd(AddressScriptType.P2WPKH, 0).path)
    assertEquals(BLUE_INTERNAL_0, wallet.hd(AddressScriptType.P2WPKH, 0, change = true).address)
    assertEquals(BIP44_ABANDON_EXT_0, wallet.hd(AddressScriptType.P2PKH, 0).address)
    assertEquals("m/44'/0'/0'/0/0", wallet.hd(AddressScriptType.P2PKH, 0).path)
    assertEquals(BIP86_ABANDON_EXT_0, wallet.hd(AddressScriptType.P2TR, 0).address)
    assertEquals(BIP86_ABANDON_EXT_1, wallet.hd(AddressScriptType.P2TR, 1).address)
    assertEquals(BIP86_ABANDON_INT_0, wallet.hd(AddressScriptType.P2TR, 0, change = true).address)
    assertEquals("m/86'/0'/0'/1/0", wallet.hd(AddressScriptType.P2TR, 0, change = true).path)
}

@Test
fun honey_mnemonic_matches_bluewallet_bip49() {
    val wallet = deriveWatchWallet(HONEY_BIP49, WatchGaps(2, 1))
    assertEquals(BIP49_HONEY_EXT_0, wallet.hd(AddressScriptType.P2SH_P2WPKH, 0).address)
    assertEquals(BIP49_HONEY_EXT_1, wallet.hd(AddressScriptType.P2SH_P2WPKH, 1).address)
    assertEquals(BIP49_HONEY_INT_0, wallet.hd(AddressScriptType.P2SH_P2WPKH, 0, change = true).address)
    assertEquals("m/49'/0'/0'/0/0", wallet.hd(AddressScriptType.P2SH_P2WPKH, 0).path)
}

@Test
fun bluewallet_bip44_account_1_path_matches() {
    val seed = MnemonicCode.toSeed(ABANDON, "")
    val master = DeterministicWallet.generate(seed)
    val ext = master.derivePrivateKey("m/44'/0'/1'/0/0").publicKey
    val intern = master.derivePrivateKey("m/44'/0'/1'/1/0").publicKey
    assertEquals(BIP44_ACCOUNT1_EXT_0, Bitcoin.computeP2PkhAddress(ext, Block.LivenetGenesisBlock.hash))
    assertEquals(BIP44_ACCOUNT1_INT_0, Bitcoin.computeP2PkhAddress(intern, Block.LivenetGenesisBlock.hash))
}

@Test
fun default_mnemonic_watch_is_160_scripts() {
    val wallet = deriveWatchWallet(ABANDON)
    assertEquals(INITIAL_WATCH_COUNT * 2 * 4, wallet.addresses.size)
}

@Test
fun small_gaps_and_zpub_match_mnemonic_bip84_only() {
    val small = deriveWatchWallet(ABANDON, WatchGaps(2, 1))
    assertEquals(12, small.addresses.size)
    val fromMnemonic = deriveWatchWallet(ABANDON, WatchGaps(3, 2))
    val fromZpub = deriveWatchWallet(BLUE_ZPUB, WatchGaps(3, 2))
    assertEquals(5, fromZpub.addresses.size)
    assertEquals(
        fromMnemonic.addresses.filter { it.scriptType == AddressScriptType.P2WPKH }.map { it.address },
        fromZpub.addresses.map { it.address },
    )
    assertEquals(SEEDSIGNER_EXTERNAL_0, deriveWatchWallet(SEEDSIGNER_ZPUB, WatchGaps(1, 0)).addresses[0].address)
    assertEquals(20, deriveWatchWallet(ABANDON, WatchGaps(3, 2)).addresses.size)
    assertEquals(32, deriveWatchWallet(ABANDON, 4).addresses.size)
}
```

Keep the WIF and address tests unchanged.

Add `import fr.acinq.bitcoin.Bitcoin` and `import fr.acinq.bitcoin.Block`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.DeriveTest`

Expected: FAIL — mnemonic still has only BIP84 addresses; size is 3 not 12.

- [ ] **Step 3: Write minimal implementation**

In `Derive.kt` replace `normalizeGaps` and `deriveBip84WatchWallet` with:

```kotlin
private fun normalizeHdGaps(gaps: HdWatchGaps?): HdWatchGaps {
    if (gaps == null) return HdWatchGaps.initial()
    fun clip(g: WatchGaps) = WatchGaps(maxOf(0, g.external), maxOf(0, g.internal))
    return HdWatchGaps(clip(gaps.p2pkh), clip(gaps.p2shP2wpkh), clip(gaps.p2wpkh), clip(gaps.p2tr))
}

private fun addressForType(
    scriptType: AddressScriptType,
    publicKey: PublicKey,
): String =
    when (scriptType) {
        AddressScriptType.P2PKH -> p2pkhAddress(publicKey)
        AddressScriptType.P2SH_P2WPKH -> p2shP2wpkhAddress(publicKey)
        AddressScriptType.P2WPKH -> p2wpkhAddress(publicKey)
        AddressScriptType.P2TR -> p2trAddress(publicKey)
    }

private fun deriveHdWatchWallet(
    secret: String,
    kind: WalletSecretKind,
    gaps: HdWatchGaps,
): WatchWallet {
    val seed = if (kind == WalletSecretKind.MNEMONIC) MnemonicCode.toSeed(secret, "") else null
    val master = seed?.let { DeterministicWallet.generate(it) }
    val types =
        if (kind == WalletSecretKind.ZPUB) {
            listOf(AddressScriptType.P2WPKH)
        } else {
            HD_SCRIPT_TYPES
        }
    val addresses = mutableListOf<WatchAddress>()
    for (scriptType in types) {
        val accountPath = scriptType.accountPath()
        val typeGaps = if (kind == WalletSecretKind.ZPUB) gaps.p2wpkh else gaps[scriptType]
        val account =
            when (kind) {
                WalletSecretKind.MNEMONIC -> master!!.derivePrivateKey(accountPath)
                WalletSecretKind.ZPUB -> DeterministicWallet.ExtendedPublicKey.decode(secret).second
                else -> error("unsupported HD secret kind")
            }
        val chains = listOf(false to typeGaps.external, true to typeGaps.internal)
        for ((change, count) in chains) {
            val chain = if (change) 1 else 0
            for (index in 0 until count) {
                val path = "$accountPath/$chain/$index"
                val childKey =
                    when (kind) {
                        WalletSecretKind.MNEMONIC ->
                            (account as DeterministicWallet.ExtendedPrivateKey).derivePrivateKey("m/$chain/$index").publicKey
                        WalletSecretKind.ZPUB ->
                            (account as DeterministicWallet.ExtendedPublicKey).derivePublicKey("m/$chain/$index").publicKey
                        else -> error("unsupported HD secret kind")
                    }
                val address = addressForType(scriptType, childKey)
                addresses.add(
                    WatchAddress(
                        path = path,
                        index = index,
                        change = change,
                        address = address,
                        scriptPubKey = scriptPubKeyForAddress(address),
                        scriptType = scriptType,
                    ),
                )
            }
        }
    }
    return WatchWallet(
        kind = WatchWalletKind.BIP84,
        secret = secret,
        addresses = addresses,
        scripts = addresses.map { it.scriptPubKey },
    )
}

fun deriveWatchWallet(
    secret: String,
    gaps: HdWatchGaps? = null,
): WatchWallet {
    val parsed = parseWalletSecret(secret)
    return when (parsed.kind) {
        WalletSecretKind.WIF -> deriveWifWatchWallet(parsed.value)
        WalletSecretKind.ADDRESS -> deriveAddressWatchWallet(parsed.value)
        WalletSecretKind.MNEMONIC, WalletSecretKind.ZPUB ->
            deriveHdWatchWallet(parsed.value, parsed.kind, normalizeHdGaps(gaps))
    }
}

fun deriveWatchWallet(
    secret: String,
    gaps: WatchGaps,
): WatchWallet = deriveWatchWallet(secret, HdWatchGaps.uniform(gaps))

fun deriveWatchWallet(
    secret: String,
    gaps: Int,
): WatchWallet = deriveWatchWallet(secret, WatchGaps(maxOf(0, gaps), maxOf(0, gaps)))
```

Delete the old `deriveBip84WatchWallet` and the old `normalizeGaps` helpers.

- [ ] **Step 4: Fix tests that assume `addresses[0]` is BIP84**

`WalletTest.loads_kv_secret_and_first_address`:

```kotlin
assertEquals(HdWatchGaps.initial(), wallet.gaps()) // still WatchGaps today — use scripts size only this task
assertEquals(BLUE_EXTERNAL_0, wallet.snapshot().hd(AddressScriptType.P2WPKH, 0).address)
assertEquals(INITIAL_WATCH_COUNT * 2 * 4, wallet.scripts().size)
```

If `Wallet.gaps()` is still `WatchGaps`, keep `assertEquals(WatchGaps(INITIAL_WATCH_COUNT, INITIAL_WATCH_COUNT), wallet.gaps())` until Task 6. Change only address/size assertions:

```kotlin
assertEquals(BLUE_EXTERNAL_0, wallet.snapshot().hd(AddressScriptType.P2WPKH, 0).address)
assertEquals(INITIAL_WATCH_COUNT * 2 * 4, wallet.scripts().size)
```

`WalletTest.secret_override_and_address_gap_does_not_write_secret`: `addressGap = 3` still writes one pair via old API; mnemonic derive with `WatchGaps(3,3)` → 24 scripts:

```kotlin
assertEquals(24, wallet.snapshot().addresses.size)
```

`WalletTest.sync_from_db_rederives_only_when_gaps_change`: `addressGap = 2` → 16 scripts; after `WatchGaps(5,2)` → `(5+2)*4 = 28`:

```kotlin
assertEquals(16, wallet.scripts().size)
// after grow:
assertEquals(28, wallet.scripts().size)
assertEquals(BLUE_EXTERNAL_0, wallet.snapshot().hd(AddressScriptType.P2WPKH, 0).address)
```

`peek_gaps`: `addressGap = 2` → 16 scripts.

`zpub_and_wif_from_kv`: zpub `addressGap = 2` stays 4 scripts; first BIP84 address via `.hd` or `addresses[0]` (zpub list is still BIP84-only).

`BuildSendTxTest.utxoAt`:

```kotlin
scriptPubKey = wallet.hd(AddressScriptType.P2WPKH, index).scriptPubKey,
```

`FiltersMatchingTest` line that asserts `INITIAL_WATCH_COUNT * 2` → `INITIAL_WATCH_COUNT * 2 * 4`. Line that asserts `addresses[0].address == BLUE_EXTERNAL_0` → `wallet.snapshot().hd(AddressScriptType.P2WPKH, 0).address`.

Add `import io.bluewallet.blueberry.wallet.hd` in filters test (same package? no — use the test helper from wallet test source; it is not visible). Duplicate a one-liner in the filters test or move `WatchWallet.hd` into `commonMain` `ReceiveAddress.kt` as `fun WatchWallet.addressAt(...)`. Put `WatchWallet.hd` in `Types.kt` `commonMain` so tests and prod can use it:

```kotlin
fun WatchWallet.hd(
    type: AddressScriptType,
    index: Int,
    change: Boolean = false,
): WatchAddress = addresses.first { it.resolvedScriptType() == type && it.index == index && it.change == change }
```

Put this in `Types.kt` in this task. TestVectors.kt can omit its copy.

- [ ] **Step 5: Run tests**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.DeriveTest --tests io.bluewallet.blueberry.wallet.WalletTest --tests io.bluewallet.blueberry.wallet.BuildSendTxTest`

Expected: PASS.

- [ ] **Step 6: Commit** (only if the user asked)

```bash
git commit -m "$(cat <<'EOF'
feat: derive BIP44, BIP49, BIP84, and BIP86 for mnemonic wallets

EOF
)"
```

---

### Task 3: Unused index and change pick

**Files:**
- Modify: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/ReceiveAddress.kt`
- Create: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/MnemonicChange.kt`
- Test: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/ReceiveAddressTest.kt`
- Test: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/MnemonicChangeTest.kt`

**Interfaces:**
- Consumes: `WatchWallet.hd`, `HD_SCRIPT_TYPES`, `firstUnusedIndex`
- Produces: `firstUnusedIndex`, `firstUnusedExternalAddress(..., scriptType)`, `firstUnusedInternalAddress(..., scriptType)`, `MnemonicChangePick`, `pickMnemonicChange`, `resolveReceiveAddress(..., receiveType)`

- [ ] **Step 1: Write the failing tests**

Add `MnemonicChangeTest.kt`:

```kotlin
package io.bluewallet.blueberry.wallet

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MnemonicChangeTest {
    @Test
    fun first_unused_index_fills_holes_then_increments() {
        assertEquals(0, firstUnusedIndex(emptyList()))
        assertEquals(2, firstUnusedIndex(listOf(0, 1)))
        assertEquals(1, firstUnusedIndex(listOf(0, 2)))
    }

    @Test
    fun pick_uses_injected_random_and_that_type_internal_index() {
        val wallet = deriveWatchWallet(ABANDON, WatchGaps(2, 2))
        val used =
            mapOf(
                AddressScriptType.P2PKH to listOf(0),
            )
        val pick0 = pickMnemonicChange(wallet, used, Random(0))
        val expectedType = HD_SCRIPT_TYPES[Random(0).nextInt(HD_SCRIPT_TYPES.size)]
        assertEquals(expectedType, pick0.scriptType)
        val expectedIndex = firstUnusedIndex(used[expectedType] ?: emptyList())
        assertEquals(expectedIndex, pick0.index)
        assertEquals(expectedType, pick0.address!!.resolvedScriptType())
        assertEquals(true, pick0.address!!.change)
        assertEquals(expectedIndex, pick0.address!!.index)
    }

    @Test
    fun pick_past_window_returns_null_address_and_next_index() {
        val wallet = deriveWatchWallet(ABANDON, WatchGaps(1, 1))
        val used = mapOf(AddressScriptType.P2TR to listOf(0))
        val pick =
            pickMnemonicChange(
                wallet,
                used,
                object : Random() {
                    override fun nextBits(bitCount: Int): Int = 0

                    override fun nextInt(until: Int): Int = HD_SCRIPT_TYPES.indexOf(AddressScriptType.P2TR)
                },
            )
        assertEquals(AddressScriptType.P2TR, pick.scriptType)
        assertEquals(1, pick.index)
        assertNull(pick.address)
    }
}
```

`Random.nextInt(until)` is what `pickMnemonicChange` must call. The anonymous `Random` override of `nextInt(until)` is the stable way to force P2TR.

Update `resolve_bip84_uses_first_unused_external` to pass `AddressScriptType.P2WPKH` if the new param is required.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.MnemonicChangeTest`

Expected: FAIL — unresolved `firstUnusedIndex` / `pickMnemonicChange`.

- [ ] **Step 3: Write minimal implementation**

`ReceiveAddress.kt` — change the two first-unused functions and `resolveReceiveAddress`:

```kotlin
fun firstUnusedIndex(used: List<Int>): Int {
    val set = used.toSet()
    var i = 0
    while (i in set) i++
    return i
}

fun firstUnusedExternalAddress(
    wallet: WatchWallet,
    usedExternal: List<Int>,
    scriptType: AddressScriptType = AddressScriptType.P2WPKH,
): WatchAddress? {
    val used = usedExternal.toSet()
    return wallet.addresses
        .filter { !it.change && it.resolvedScriptType() == scriptType }
        .sortedBy { it.index }
        .firstOrNull { it.index !in used }
}

fun firstUnusedInternalAddress(
    wallet: WatchWallet,
    usedInternal: List<Int>,
    scriptType: AddressScriptType = AddressScriptType.P2WPKH,
): WatchAddress? {
    val used = usedInternal.toSet()
    return wallet.addresses
        .filter { it.change && it.resolvedScriptType() == scriptType }
        .sortedBy { it.index }
        .firstOrNull { it.index !in used }
}

fun resolveReceiveAddress(
    wallet: WatchWallet,
    usedExternal: List<Int> = emptyList(),
    wifTxs: List<WifReceiveTxRow> = emptyList(),
    receiveType: AddressScriptType = AddressScriptType.P2WPKH,
): WatchAddress? =
    when (wallet.kind) {
        WatchWalletKind.BIP84 -> firstUnusedExternalAddress(wallet, usedExternal, receiveType)
        WatchWalletKind.WIF -> preferredWifReceiveAddress(wallet, wifTxs)
        WatchWalletKind.ADDRESS -> wallet.addresses.firstOrNull()
    }
```

`MnemonicChange.kt`:

```kotlin
package io.bluewallet.blueberry.wallet

import kotlin.random.Random

data class MnemonicChangePick(
    val scriptType: AddressScriptType,
    val index: Int,
    val address: WatchAddress?,
)

fun pickMnemonicChange(
    wallet: WatchWallet,
    usedInternalByType: Map<AddressScriptType, List<Int>>,
    random: Random,
): MnemonicChangePick {
    val types = wallet.addresses.map { it.resolvedScriptType() }.distinct()
    val scriptType = types[random.nextInt(types.size)]
    val index = firstUnusedIndex(usedInternalByType[scriptType] ?: emptyList())
    val address =
        wallet.addresses.firstOrNull {
            it.change && it.resolvedScriptType() == scriptType && it.index == index
        }
    return MnemonicChangePick(scriptType, index, address)
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.MnemonicChangeTest --tests io.bluewallet.blueberry.wallet.ReceiveAddressTest`

Expected: PASS.

- [ ] **Step 5: Commit** (only if the user asked)

```bash
git commit -m "$(cat <<'EOF'
feat: pick mnemonic change by random script type and unused internal index

EOF
)"
```

---

### Task 4: Receive script-type preference

**Files:**
- Create: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/ReceivePreference.kt`
- Test: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/ReceivePreferenceTest.kt`

**Interfaces:**
- Consumes: `RECEIVE_SCRIPT_TYPE_KEY`, `addressScriptTypeFromWire`, `AddressScriptType.wireName()`
- Produces: `loadReceiveScriptType(db)`, `saveReceiveScriptType(db, type)`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.bluewallet.blueberry.wallet

import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class ReceivePreferenceTest {
    @Test
    fun defaults_to_p2wpkh_and_persists() {
        val db = createSqliteDatabase(":memory:")
        assertEquals(AddressScriptType.P2WPKH, loadReceiveScriptType(db))
        saveReceiveScriptType(db, AddressScriptType.P2TR)
        assertEquals(AddressScriptType.P2TR, loadReceiveScriptType(db))
        db.keyValue.set(RECEIVE_SCRIPT_TYPE_KEY, "nope")
        assertEquals(AddressScriptType.P2WPKH, loadReceiveScriptType(db))
        db.close()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.ReceivePreferenceTest`

Expected: FAIL — unresolved `loadReceiveScriptType`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package io.bluewallet.blueberry.wallet

import io.bluewallet.blueberry.storage.Database

fun saveReceiveScriptType(
    db: Database,
    type: AddressScriptType,
) {
    db.keyValue.set(RECEIVE_SCRIPT_TYPE_KEY, type.wireName())
}

fun loadReceiveScriptType(db: Database): AddressScriptType {
    val raw = db.keyValue.get(RECEIVE_SCRIPT_TYPE_KEY) ?: return AddressScriptType.P2WPKH
    return runCatching { addressScriptTypeFromWire(raw) }.getOrDefault(AddressScriptType.P2WPKH)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.ReceivePreferenceTest`

Expected: PASS.

- [ ] **Step 5: Commit** (only if the user asked)

```bash
git commit -m "$(cat <<'EOF'
feat: persist receive script type, default BIP84

EOF
)"
```

---

### Task 5: Used indexes per script type

**Files:**
- Modify: `parse/src/commonMain/kotlin/io/bluewallet/blueberry/parse/Types.kt`
- Modify: `parse/src/commonMain/kotlin/io/bluewallet/blueberry/parse/UsedIndexes.kt`
- Modify: `parse/src/commonTest/kotlin/io/bluewallet/blueberry/parse/UsedWatchIndexesTest.kt`
- Modify: `parse/src/commonTest/kotlin/io/bluewallet/blueberry/parse/ReceiveAddressFromWalletTest.kt`

**Interfaces:**
- Consumes: `UsedHdIndexes`, `UsedChainIndexes`, `resolveReceiveAddress(..., receiveType)`
- Produces: `usedWatchIndexes` returns `UsedHdIndexes`; `receiveAddressFromWallet(..., receiveType)`

- [ ] **Step 1: Write the failing isolation test**

Add to `UsedWatchIndexesTest.kt`:

```kotlin
@Test
fun used_index_on_bip44_does_not_mark_bip84() {
    val wallet = deriveWatchWallet(ABANDON_MNEMONIC, 3)
    val bip44 = wallet.hd(AddressScriptType.P2PKH, 0)
    val receive = coinbaseLikeReceive(bip44.scriptPubKey, 1000)
    val used = usedWatchIndexes(listOf(Transaction.write(receive)), wallet)
    assertEquals(listOf(0), used.get(AddressScriptType.P2PKH).external)
    assertEquals(emptyList(), used.get(AddressScriptType.P2WPKH).external)
}
```

Change existing assertions:

```kotlin
assertEquals(listOf(2), used.get(AddressScriptType.P2WPKH).external)
```

and pick addresses with `wallet.hd(AddressScriptType.P2WPKH, 2)` (not `first { !it.change && it.index == 2 }`).

`detects_p2pkh_spend_via_scriptSig` stays ADDRESS kind; assert `used.get(AddressScriptType.P2PKH).external`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :parse:jvmTest --tests io.bluewallet.blueberry.parse.UsedWatchIndexesTest`

Expected: FAIL — `UsedWatchIndexes` has `external` not `get(type)`.

- [ ] **Step 3: Write minimal implementation**

Delete `UsedWatchIndexes` from `parse/Types.kt`.

`UsedIndexes.kt`:

```kotlin
import io.bluewallet.blueberry.wallet.AddressScriptType
import io.bluewallet.blueberry.wallet.UsedChainIndexes
import io.bluewallet.blueberry.wallet.UsedHdIndexes
import io.bluewallet.blueberry.wallet.hd
import io.bluewallet.blueberry.wallet.loadReceiveScriptType
import io.bluewallet.blueberry.wallet.resolvedScriptType

fun usedWatchIndexes(
    txs: List<ByteArray>,
    wallet: WatchWallet,
): UsedHdIndexes {
    val scriptToIndex = LinkedHashMap<String, Triple<AddressScriptType, Boolean, Int>>()
    for (addr in wallet.addresses) {
        scriptToIndex[scriptHex(addr.scriptPubKey)] =
            Triple(addr.resolvedScriptType(), addr.change, addr.index)
    }
    val external = mutableMapOf<AddressScriptType, MutableSet<Int>>()
    val internal = mutableMapOf<AddressScriptType, MutableSet<Int>>()

    fun mark(
        type: AddressScriptType,
        change: Boolean,
        index: Int,
    ) {
        val dest = if (change) internal else external
        dest.getOrPut(type) { mutableSetOf() }.add(index)
    }

    val watchOutpoints = mutableMapOf<String, Triple<AddressScriptType, Boolean, Int>>()
    val decodedTxs = txs.map { Transaction.read(it) }
    for (decoded in decodedTxs) {
        val txid = decoded.txid.toString()
        decoded.txOut.forEachIndexed { vout, o ->
            val info = scriptToIndex[scriptHex(o.publicKeyScript.toByteArray())]
            if (info != null) watchOutpoints[outpointKey(txid, vout)] = info
        }
    }
    for (tx in decodedTxs) {
        for (outp in tx.txOut) {
            val info = scriptToIndex[scriptHex(outp.publicKeyScript.toByteArray())]
            if (info != null) mark(info.first, info.second, info.third)
        }
        if (!tx.isCoinbase()) {
            for (inn in tx.txIn) {
                val outInfo = watchOutpoints[prevoutKey(inn)]
                if (outInfo != null) mark(outInfo.first, outInfo.second, outInfo.third)
                for (script in watchedScriptsFromInput(inn)) {
                    val info = scriptToIndex[scriptHex(script)]
                    if (info != null) mark(info.first, info.second, info.third)
                }
            }
        }
    }
    val types = (external.keys + internal.keys)
    return UsedHdIndexes(
        types.associateWith { type ->
            UsedChainIndexes(
                external = external[type]?.sorted().orEmpty(),
                internal = internal[type]?.sorted().orEmpty(),
            )
        },
    )
}

fun receiveAddressFromWallet(
    wallet: WatchWallet,
    txs: List<StoredTx>,
    receiveType: AddressScriptType = AddressScriptType.P2WPKH,
): String? {
    val used = usedWatchIndexes(txs.map { it.tx }, wallet)
    val wifTxs = txs.map { WifReceiveTxRow(it.height, it.txIndex, it.tx) }
    return resolveReceiveAddress(wallet, used.get(receiveType).external, wifTxs, receiveType)?.address
}
```

Leave `snapshotReceiveAddress` on the old `growWatchGapsIfNeeded(loadWatchGaps, used.external, used.internal)` until Task 6; it will not compile. Fix it in this task to compile:

```kotlin
fun snapshotReceiveAddress(
    db: Database,
    wallet: Wallet,
): String? {
    wallet.refresh()
    val txs = db.transactions.list()
    val receiveType = loadReceiveScriptType(db)
    val unused = receiveAddressFromWallet(wallet.snapshot(), txs, receiveType)
    if (unused != null) return unused
    val snap = wallet.snapshot()
    if (snap.kind != WatchWalletKind.BIP84) return null
    val used = usedWatchIndexes(txs.map { it.tx }, snap)
    val grown = growHdWatchGapsIfNeeded(loadHdWatchGaps(db), used)
    if (!grown.grew) return null
    saveHdWatchGaps(db, grown.gaps)
    val fromHeight = compactFilterFrom(db) ?: db.transactions.minHeight()
    if (fromHeight != null) {
        db.filters.markUnscannedFrom(fromHeight)
        db.parsedBlocks.clearFrom(fromHeight)
    }
    return receiveAddressFromWallet(wallet.refresh(), txs, receiveType)
}
```

`ReceiveAddressFromWalletTest`:

- `empty_hd_wallet_shows_first_external` expected = `wallet.hd(P2WPKH, 0).address`
- `funding_first_external_advances_to_next` fund `hd(P2WPKH, 0)`, expect `hd(P2WPKH, 1)`
- `all_watched_externals_used_is_null` fund only P2WPKH externals (`filter { !it.change && it.scriptType == P2WPKH }`)
- `exhausted_watch_grows...` fund only P2WPKH externals; assert `loadHdWatchGaps(db)[P2WPKH].external > 2` after snapshot. If Task 6 has not switched `createWallet` to `loadHdWatchGaps`, `addressGap = 2` still sets old keys and `loadHdWatchGaps` defaults all types to 20, so grow may not trigger.

To keep this test meaningful before Task 6, also `saveHdWatchGaps(db, HdWatchGaps.uniform(WatchGaps(2, 2)))` in the exhausted tests after `createWallet`. Or finish Task 6 immediately after this task in the same session.

If `snapshotReceiveAddress` already calls `loadHdWatchGaps`, `createWallet(..., addressGap = 2)` still writes old keys only — the exhausted test will fail until Task 6. Update those two tests in Task 6. In this task only fix compile + the isolation test + the first three receive tests.

ParseBlocks still calls `used.external` — that will not compile. Fix `ParseBlocks.kt` `maybeGrowWatch` in this task to:

```kotlin
val used = usedWatchIndexes(...)
val result = growHdWatchGapsIfNeeded(loadHdWatchGaps(ctx.db), used)
if (!result.grew) {
    wallet.syncFromDb()
    val gaps = wallet.gaps()
    if (lastGaps is WatchGaps) { /* still WatchGaps */ }
}
```

`wallet.gaps()` is still `WatchGaps`. `lastGaps` comparison stays on old gaps until Task 6. For compile of `used.external`, change `maybeGrowWatch` now:

```kotlin
val used = usedWatchIndexes(...)
val result = growHdWatchGapsIfNeeded(loadHdWatchGaps(ctx.db), used)
if (!result.grew) {
    wallet.syncFromDb()
    val gaps = wallet.gaps()
    if (gaps.external == lastGaps.external && gaps.internal == lastGaps.internal) return false
    lastGaps = gaps
    needsRun.store(true)
    return true
}
ctx.db.transaction {
    saveHdWatchGaps(ctx.db, result.gaps)
    ...
}
```

`createWallet` still persists old keys, so parse gap tests will not see HD growth until Task 6. That is expected. After this task, `:parse:jvmTest` `UsedWatchIndexesTest` must pass. `ParseBlocksGapTest` may fail; fix in Task 6.

- [ ] **Step 4: Run used-index tests**

Run: `./gradlew :parse:jvmTest --tests io.bluewallet.blueberry.parse.UsedWatchIndexesTest --tests io.bluewallet.blueberry.parse.ReceiveAddressFromWalletTest`

Expected: isolation + first receive tests PASS. Exhausted-grow tests may FAIL until Task 6; if they fail because `loadHdWatchGaps` ignores `addressGap`, skip asserting grow here and move those two tests to Task 6.

- [ ] **Step 5: Commit** (only if the user asked)

```bash
git commit -m "$(cat <<'EOF'
feat: track used HD indexes per script type

EOF
)"
```

---

### Task 6: `Wallet` and callers use `HdWatchGaps`

**Files:**
- Modify: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Wallet.kt`
- Modify: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/WatchGaps.kt`
- Modify: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/WalletTest.kt`
- Modify: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/WatchGapsTest.kt`
- Modify: `parse/src/commonMain/kotlin/io/bluewallet/blueberry/parse/modules/ParseBlocks.kt`
- Modify: `parse/src/commonTest/kotlin/io/bluewallet/blueberry/parse/ParseBlocksGapTest.kt`
- Modify: `parse/src/commonTest/kotlin/io/bluewallet/blueberry/parse/ReceiveAddressFromWalletTest.kt`
- Modify: `filters/src/commonMain/kotlin/io/bluewallet/blueberry/filters/modules/FiltersMatching.kt`
- Modify: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/ReceiveScreen.kt`

**Interfaces:**
- Consumes: `loadHdWatchGaps`, `saveHdWatchGaps`, `growHdWatchGapsIfNeeded`, `UsedHdIndexes`
- Produces: `Wallet.gaps(): HdWatchGaps`, `peekGaps(): HdWatchGaps`. `loadWatchGaps` / `saveWatchGaps` become aliases of the HD functions (delete old two-key API).

- [ ] **Step 1: Write the failing wallet-gap test**

Replace `WalletTest.loads_kv_secret_and_first_address` gap assert:

```kotlin
assertEquals(HdWatchGaps.initial(), wallet.gaps())
assertEquals(INITIAL_WATCH_COUNT.toString(), db.keyValue.get(WATCH_EXTERNAL_P2WPKH_KEY))
```

Replace `secret_override_and_address_gap_does_not_write_secret`:

```kotlin
assertEquals(HdWatchGaps.uniform(WatchGaps(3, 3)), wallet.gaps())
assertEquals(HdWatchGaps.uniform(WatchGaps(3, 3)), loadHdWatchGaps(db))
assertEquals(24, wallet.snapshot().addresses.size)
```

Replace sync/peek tests to `saveHdWatchGaps` / `loadHdWatchGaps` / `HdWatchGaps.uniform`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.WalletTest`

Expected: FAIL — `gaps()` still returns `WatchGaps`.

- [ ] **Step 3: Wire `HdWatchGaps` through `Wallet`**

`Wallet.kt`:

```kotlin
interface Wallet {
    fun snapshot(): WatchWallet
    fun scripts(): List<ByteArray>
    fun gaps(): HdWatchGaps
    fun peekGaps(): HdWatchGaps
    fun refresh(): WatchWallet
    fun syncFromDb(): SyncFromDbResult
}

fun createWallet(...): Wallet {
    ...
    if (options.addressGap != null) {
        val n = max(0, floor(options.addressGap.toDouble()).toInt())
        saveHdWatchGaps(db, HdWatchGaps.uniform(WatchGaps(n, n)))
    }
    var currentGaps = loadHdWatchGaps(db)
    var current = deriveWatchWallet(secret, currentGaps)
    val syncFromDbImpl: () -> SyncFromDbResult = {
        val gaps = loadHdWatchGaps(db)
        val grew = gaps != currentGaps
        if (grew) {
            currentGaps = gaps
            current = deriveWatchWallet(secret, currentGaps)
        }
        SyncFromDbResult(grew)
    }
    return object : Wallet {
        override fun snapshot() = current
        override fun scripts() = current.scripts
        override fun gaps() = currentGaps
        override fun peekGaps() = loadHdWatchGaps(db)
        override fun refresh(): WatchWallet {
            syncFromDbImpl()
            return current
        }
        override fun syncFromDb() = syncFromDbImpl()
    }
}
```

Make `loadWatchGaps` / `saveWatchGaps` wrappers so leftover call sites compile, then delete wrappers after grep is clean:

```kotlin
fun saveWatchGaps(db: Database, gaps: HdWatchGaps) = saveHdWatchGaps(db, gaps)
fun loadWatchGaps(db: Database): HdWatchGaps = loadHdWatchGaps(db)
```

Remove the old two-key `saveWatchGaps(db, WatchGaps)` and `loadWatchGaps(): WatchGaps`. Update `WatchGapsTest` old tests to use `WATCH_EXTERNAL_P2WPKH_KEY` / `WATCH_INTERNAL_P2WPKH_KEY` and `loadHdWatchGaps(db).p2wpkh`.

`ParseBlocks.kt`:
- `var lastGaps = wallet.gaps()` type becomes `HdWatchGaps`
- `maybeGrowWatch` uses `growHdWatchGapsIfNeeded` + `saveHdWatchGaps`
- Compare `gaps != lastGaps` (data-class equality)
- Log `external=${gaps.p2wpkh.external}` or all four; keep one line: `gaps=$gaps`

`FiltersMatching.kt`:
- `var loadedGaps: HdWatchGaps? = null`
- Replace `previous.external != gaps.external || previous.internal != gaps.internal` with `previous != gaps`

`ReceiveScreen.kt` `currentReceiveAddress`:

```kotlin
if (after != before) { ... }
```

`ParseBlocksGapTest`:
- `danger = wallet.hd(AddressScriptType.P2WPKH, 25)`
- `next = wallet.hd(AddressScriptType.P2WPKH, 45)`
- `loadWatchGaps(db).external` → `loadHdWatchGaps(db)[P2WPKH].external`
- `loadWatchGaps(db).internal` → `loadHdWatchGaps(db)[P2WPKH].internal`
- `usedWatchIndexes(...).external.contains(45)` → `usedWatchIndexes(...).get(P2WPKH).external.contains(45)`

`ReceiveAddressFromWalletTest` exhausted tests: assert `wallet.gaps()[P2WPKH].external > 2`. Fund only P2WPKH externals.

Grep for `.gaps().external`, `saveWatchGaps(db, WatchGaps`, `loadWatchGaps(db).external` and fix every hit.

- [ ] **Step 4: Run tests**

Run: `./gradlew :wallet:jvmTest :parse:jvmTest :filters:jvmTest`

Expected: PASS.

- [ ] **Step 5: Commit** (only if the user asked)

```bash
git commit -m "$(cat <<'EOF'
feat: grow and persist HD watch windows per script type

EOF
)"
```

---

### Task 7: Send change — random type, always derive

**Files:**
- Modify: `parse/src/commonMain/kotlin/io/bluewallet/blueberry/parse/SendContext.kt`
- Modify: `parse/src/commonTest/kotlin/io/bluewallet/blueberry/parse/SendContextTest.kt`

**Interfaces:**
- Consumes: `pickMnemonicChange`, `UsedHdIndexes`, `saveHdWatchGaps`, `loadHdWatchGaps`, `kotlin.random.Random`
- Produces: `buildActiveSendTx(..., random: Random = Random.Default)` never throws `no unused change address in watch window` for mnemonic

- [ ] **Step 1: Write the failing tests**

Add to `SendContextTest.kt`:

```kotlin
@Test
fun mnemonic_change_uses_random_type_internal_and_grows_past_window() {
    val db = createSqliteDatabase(":memory:")
    saveWalletSecret(db, ABANDON_MNEMONIC)
    val wallet = createWallet(db, io.bluewallet.blueberry.wallet.CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 1))
    val watch = wallet.snapshot()
    val recv = watch.hd(AddressScriptType.P2WPKH, 0)
    val (fundTxid, fundBytes) = fundingTx(recv.scriptPubKey, 100_000L)
    db.transactions.upsert(
        StoredTx(fundTxid, 800_000, 0, "aa".repeat(32), fundBytes, 100_000L),
    )
    val forced =
        object : kotlin.random.Random() {
            override fun nextBits(bitCount: Int) = 0

            override fun nextInt(until: Int) = HD_SCRIPT_TYPES.indexOf(AddressScriptType.P2TR)
        }
    saveHdWatchGaps(db, HdWatchGaps.uniform(WatchGaps(1, 0)))
    wallet.refresh()
    val result =
        buildActiveSendTx(
            db,
            wallet,
            SendBuildParams(
                utxos = listOf(SendInputUtxo(fundTxid, 0, 100_000L, recv.scriptPubKey, nonWitnessUtxo = fundBytes)),
                toAddress = DEST_LEGACY,
                amountSats = SendAmount.Exact(50_000L),
                feeRateSatPerVb = 1.0,
            ),
            random = forced,
        )
    val signed = result as SignedSendResult
    val tx = Transaction.read(hexToBytes(signed.txHex))
    val changeScript = outputScriptFromAddress(BIP86_ABANDON_INT_0)
    assertTrue(tx.txOut.any { it.publicKeyScript.toByteArray().contentEquals(changeScript) })
    assertEquals(1, loadHdWatchGaps(db)[AddressScriptType.P2TR].internal)
    db.close()
}
```

`BIP86_ABANDON_INT_0` lives in wallet test sources. Duplicate the string in this test or move the constants to `wallet` `commonMain` test-only — keep the literal in `SendContextTest` to avoid cross-module test helpers:

```kotlin
private const val BIP86_INT_0 = "bc1p3qkhfews2uk44qtvauqyr2ttdsw7svhkl9nkm9s9c3x4ax5h60wqwruhk7"
```

Internal window `0` means no internal addresses. `firstUnusedIndex(emptyList())` is `0`. `pick.address` is null. Grow internal to `1`. Derive `m/86'/0'/0'/1/0`.

Need `import io.bluewallet.blueberry.wallet.HD_SCRIPT_TYPES` (add `HD_SCRIPT_TYPES` is already public in `Types.kt`).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :parse:jvmTest --tests io.bluewallet.blueberry.parse.SendContextTest`

Expected: FAIL — `buildActiveSendTx` has no `random` and still throws or uses BIP84 change.

- [ ] **Step 3: Write minimal implementation**

`SendContext.kt` replace the HD branch:

```kotlin
fun buildActiveSendTx(
    db: Database,
    wallet: Wallet,
    params: SendBuildParams,
    random: Random = Random.Default,
): BuildSendResult {
    wallet.syncFromDb()
    var watch = wallet.snapshot()
    val changeAddress =
        when {
            params.amountSats is SendAmount.Max -> params.toAddress
            watch.kind == WatchWalletKind.WIF ->
                preferredWifReceiveAddress(
                    watch,
                    db.transactions.list().map { WifReceiveTxRow(it.height, it.txIndex, it.tx) },
                ).address
            watch.kind == WatchWalletKind.ADDRESS -> {
                val addr = watch.addresses.firstOrNull()
                    ?: throw IllegalArgumentException("address wallet missing watched address")
                addr.address
            }
            else -> {
                val used = usedWatchIndexes(db.transactions.list().map { it.tx }, watch)
                val usedInternal = HD_SCRIPT_TYPES.associateWith { used.get(it).internal }
                val pick = pickMnemonicChange(watch, usedInternal, random)
                val existing = pick.address
                if (existing != null) {
                    existing.address
                } else {
                    val gaps = loadHdWatchGaps(db)
                    val current = gaps[pick.scriptType]
                    val next = WatchGaps(current.external, maxOf(current.internal, pick.index + 1))
                    saveHdWatchGaps(db, gaps.with(pick.scriptType, next))
                    watch = wallet.refresh()
                    watch.hd(pick.scriptType, pick.index, change = true).address
                }
            }
        }
    return buildSend(
        io.bluewallet.blueberry.wallet.BuildSendTxParams(
            secret = loadWalletSecret(db),
            wallet = watch,
            utxos = attachNonWitnessUtxos(db, params.utxos),
            toAddress = params.toAddress,
            amountSats = params.amountSats,
            feeRateSatPerVb = params.feeRateSatPerVb,
            changeAddress = changeAddress,
        ),
    )
}
```

Remove the `firstUnusedInternalAddress` throw path for HD.

- [ ] **Step 4: Run tests**

Run: `./gradlew :parse:jvmTest --tests io.bluewallet.blueberry.parse.SendContextTest`

Expected: PASS. Existing WIF/address tests still pass.

- [ ] **Step 5: Commit** (only if the user asked)

```bash
git commit -m "$(cat <<'EOF'
feat: derive mnemonic change on a random script type

EOF
)"
```

---

### Task 8: Spend mixed script types in one send

**Files:**
- Modify: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/BuildSendTxTest.kt`

**Interfaces:**
- Consumes: existing `buildSignedSendTx`, `WatchWallet.hd`
- Produces: a test that spends BIP44 + BIP86 UTXOs

- [ ] **Step 1: Write the failing test**

Add to `BuildSendTxTest.kt`:

```kotlin
@Test
fun signs_bip44_and_bip86_inputs_together() {
    val wallet = deriveWatchWallet(ABANDON, WatchGaps(1, 1))
    val p2pkh = wallet.hd(AddressScriptType.P2PKH, 0)
    val p2tr = wallet.hd(AddressScriptType.P2TR, 0)
    val fundLegacy = testFundingTx(p2pkh.scriptPubKey, 80_000L, salt = 1)
    val result =
        buildSignedSendTx(
            baseParams(
                wallet = wallet,
                utxos =
                    listOf(
                        SendInputUtxo(
                            txid = fundLegacy.txid,
                            vout = 0,
                            valueSats = 80_000L,
                            scriptPubKey = p2pkh.scriptPubKey,
                            nonWitnessUtxo = fundLegacy.bytes,
                        ),
                        SendInputUtxo(
                            txid = "22".repeat(32),
                            vout = 0,
                            valueSats = 70_000L,
                            scriptPubKey = p2tr.scriptPubKey,
                        ),
                    ),
                amount = SendAmount.Exact(100_000L),
                feeRate = 1.0,
            ),
        )
    val tx = parseSigned(result.txHex)
    assertEquals(2, tx.txIn.size)
    assertTrue(result.feeSats > 0L)
}
```

`testFundingTx` is already in `TestVectors.kt`.

- [ ] **Step 2: Run test**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.BuildSendTxTest.signs_bip44_and_bip86_inputs_together`

If it PASSES on the first run, the builder already signs both types. Keep the test. Do not add production code.

If it FAILS, fix `BuildSendTx.kt` key derivation (mnemonic uses `addr.path`). Do not change WIF paths.

- [ ] **Step 3: Run full send suite**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.BuildSendTxTest`

Expected: PASS.

- [ ] **Step 4: Commit** (only if the user asked)

```bash
git commit -m "$(cat <<'EOF'
test: spend BIP44 and BIP86 UTXOs in one mnemonic send

EOF
)"
```

---

### Task 9: Receive dropdown

**Files:**
- Modify: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/ReceiveScreen.kt`
- Modify: `parse/src/commonTest/kotlin/io/bluewallet/blueberry/parse/ReceiveAddressFromWalletTest.kt`

**Interfaces:**
- Consumes: `loadReceiveScriptType`, `saveReceiveScriptType`, `AddressScriptType.receiveLabel()`, `parseWalletSecret`, `loadWalletSecret`
- Produces: mnemonic Receive screen dropdown; `snapshotReceiveAddress` already honors KV

- [ ] **Step 1: Write the failing preference-to-address test**

Add to `ReceiveAddressFromWalletTest.kt`:

```kotlin
@Test
fun snapshot_uses_saved_receive_script_type() {
    val db = createSqliteDatabase(":memory:")
    val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 2))
    saveReceiveScriptType(db, AddressScriptType.P2TR)
    assertEquals(
        wallet.snapshot().hd(AddressScriptType.P2TR, 0).address,
        snapshotReceiveAddress(db, wallet),
    )
    db.close()
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew :parse:jvmTest --tests io.bluewallet.blueberry.parse.ReceiveAddressFromWalletTest.snapshot_uses_saved_receive_script_type`

If Task 6 `snapshotReceiveAddress` already loads preference, this PASSES. Keep it.

- [ ] **Step 3: Add the dropdown**

In `ReceiveScreen.kt`:

```kotlin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import io.bluewallet.blueberry.wallet.AddressScriptType
import io.bluewallet.blueberry.wallet.HD_SCRIPT_TYPES
import io.bluewallet.blueberry.wallet.WalletSecretKind
import io.bluewallet.blueberry.wallet.loadReceiveScriptType
import io.bluewallet.blueberry.wallet.loadWalletSecret
import io.bluewallet.blueberry.wallet.parseWalletSecret
import io.bluewallet.blueberry.wallet.receiveLabel
import io.bluewallet.blueberry.wallet.saveReceiveScriptType

fun showsReceiveTypePicker(db: Database): Boolean =
    runCatching { parseWalletSecret(loadWalletSecret(db)).kind == WalletSecretKind.MNEMONIC }
        .getOrDefault(false)
```

In `ReceiveScreen` composable, under `ScreenHeader`, only when `showsReceiveTypePicker(db)`:

```kotlin
var receiveType by remember { mutableStateOf(loadReceiveScriptType(db)) }
var menuOpen by remember { mutableStateOf(false) }
if (showsReceiveTypePicker(db)) {
    Box {
        Text(
            text = receiveType.receiveLabel(),
            color = BwColors.Link,
            fontFamily = BwFontFamily,
            fontSize = BwType.BodySize,
            fontWeight = BwType.Body,
            modifier =
                Modifier.clickable {
                    menuOpen = true
                },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            for (type in HD_SCRIPT_TYPES) {
                DropdownMenuItem(
                    text = { Text(type.receiveLabel()) },
                    onClick = {
                        saveReceiveScriptType(db, type)
                        receiveType = type
                        menuOpen = false
                        address = currentReceiveAddress(runtime, db)
                    },
                )
            }
        }
    }
}
```

Do not put this control in the header row. Do not use `PillButton`.

- [ ] **Step 4: Run tests**

Run: `./gradlew :parse:jvmTest --tests io.bluewallet.blueberry.parse.ReceiveAddressFromWalletTest :shared:jvmTest :wallet:jvmTest`

Expected: PASS.

- [ ] **Step 5: Format and static analysis**

Run: `./gradlew ktlintFormat ktlintCheck detekt`

Expected: no new findings. Do not regenerate `config/detekt/baselines/*`.

- [ ] **Step 6: Commit** (only if the user asked)

```bash
git commit -m "$(cat <<'EOF'
feat: receive address type dropdown for mnemonic wallets

EOF
)"
```

---

## Self-review

**Spec coverage**

| Spec item | Task |
| --- | --- |
| Four paths, account 0 | 2 |
| `zpub` BIP84 only | 2 |
| `INITIAL` / `GAP` 20 | 1 |
| Independent gaps + new KV keys | 1, 6 |
| Used indexes per type | 5 |
| Receive dropdown + KV default BIP84 | 4, 9 |
| Random change + always derive | 3, 7 |
| BlueWallet / BIP86 vectors | 2 |
| Mixed send | 8 |
| No old-key migration | 6 deletes two-key API |
| WIF / address unchanged | 2, 7 |

**Placeholder scan:** none of TBD / “handle edge cases” / “similar to Task N” remain.

**Type consistency:** `HdWatchGaps`, `UsedHdIndexes`, `pickMnemonicChange`, `loadHdWatchGaps`, `WatchWallet.hd` names match across tasks.

---

Plan complete and saved to `docs/superpowers/plans/2026-09-14-multi-script-mnemonic.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
