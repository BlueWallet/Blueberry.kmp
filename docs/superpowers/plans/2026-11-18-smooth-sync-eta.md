# Steady sync ETA Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop every sync time from jumping by many minutes between seconds.

**Architecture:** Add one pure ease function, `smoothEtaMs`, and call it from each store that already computes a raw ETA. The screen keeps reading `etaMs`. The 8-increase speed sample stays. The top sync time stays the longest of the four eased bars, with no second ease.

**Tech Stack:** Kotlin Multiplatform, `kotlin.test`, `./gradlew :shared:jvmTest`.

## Global Constraints

- `ETA_SMOOTH_TAU_MS` is `8_000`.
- The first estimate for a job shows at once.
- After that, the shown time follows the latest estimate with an 8 second time constant.
- A lasting change is about 85% complete after 15 seconds.
- Finished work clears the time at once. The shown time does not ease down through the minutes.
- A restarted job drops the old time. The next estimate shows at once.
- A tick with no new estimate keeps the last positive time.
- Chain tip, filters download, filters match, blocks download, and transaction parsing all use this ease.
- The top time stays the longest of the four sync bars. Do not edit `overallSyncEtaMs`.
- The partner swap time stays the value the partner sent. Do not edit `RocketxFormat.kt`.
- Do not change `MAX_SAMPLES` or the bodies of the private `estimateEtaMs` functions.
- Sync bars store `0` when the job is finished. Wallet `nextState` still stores null when the backlog is gone. Wallet `setParsingActive(false)` still stores null and clears samples.
- On a restart, pass `previous = null` and `previousAt = null`.
- `./gradlew ktlintCheck detekt :shared:jvmTest` must pass before the last commit. Do not format or lint `vendor/*`.
- Package is `io.bluewallet.blueberry`. Stores call `smoothEtaMs` with no new import.

## File structure

- Modify `shared/src/commonMain/kotlin/io/bluewallet/blueberry/ProgressFormat.kt` — the ease function.
- Modify `shared/src/commonTest/kotlin/io/bluewallet/blueberry/HeadersProgressStoreTest.kt` — ease unit tests.
- Modify `shared/src/commonMain/kotlin/io/bluewallet/blueberry/FiltersProgressStore.kt` — filters download.
- Modify `shared/src/commonTest/kotlin/io/bluewallet/blueberry/FiltersProgressStoreTest.kt` — filters ease test.
- Modify `shared/src/commonMain/kotlin/io/bluewallet/blueberry/HeadersProgressStore.kt` — chain tip.
- Modify `shared/src/commonMain/kotlin/io/bluewallet/blueberry/BlocksMatchedStore.kt` — blocks download.
- Modify `shared/src/commonTest/kotlin/io/bluewallet/blueberry/BlocksMatchedStoreTest.kt` — blocks ease test.
- Modify `shared/src/commonMain/kotlin/io/bluewallet/blueberry/MatchingProgressStore.kt` — filters match.
- Modify `shared/src/commonTest/kotlin/io/bluewallet/blueberry/MatchingProgressStoreTest.kt` — match ease test.
- Modify `shared/src/commonMain/kotlin/io/bluewallet/blueberry/WalletTxsStore.kt` — parse time, both `setBlockCounts` and `nextState`.
- Modify `shared/src/commonTest/kotlin/io/bluewallet/blueberry/WalletTxsStoreEtaTest.kt` — parse ease tests.

---

### Task 1: Ease function

**Files:**
- Modify: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/ProgressFormat.kt`
- Test: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/HeadersProgressStoreTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `const val ETA_SMOOTH_TAU_MS = 8_000L` and `fun smoothEtaMs(previous: Long?, previousAt: Long?, raw: Long?, at: Long, tauMs: Long = ETA_SMOOTH_TAU_MS): Long?`

- [ ] **Step 1: Write the failing tests**

Append these tests inside `HeadersProgressStoreTest`, before the class's closing brace.

```kotlin
@Test
fun smoothEtaMs_shows_the_first_raw_value() {
    assertEquals(3_000_000L, smoothEtaMs(previous = null, previousAt = null, raw = 3_000_000L, at = 1_000))
}

@Test
fun smoothEtaMs_snaps_done_to_zero() {
    assertEquals(0L, smoothEtaMs(previous = 3_000_000L, previousAt = 1_000, raw = 0L, at = 2_000))
}

@Test
fun smoothEtaMs_missing_raw_without_history_is_null() {
    assertNull(smoothEtaMs(previous = null, previousAt = null, raw = null, at = 1_000))
    assertNull(smoothEtaMs(previous = 0L, previousAt = 1_000, raw = null, at = 2_000))
}

@Test
fun smoothEtaMs_keeps_a_positive_time_when_raw_is_missing() {
    assertEquals(3_000_000L, smoothEtaMs(previous = 3_000_000L, previousAt = 1_000, raw = null, at = 2_000))
}

@Test
fun smoothEtaMs_non_positive_previous_returns_raw() {
    assertEquals(3_000_000L, smoothEtaMs(previous = 0L, previousAt = 1_000, raw = 3_000_000L, at = 2_000))
    assertEquals(3_000_000L, smoothEtaMs(previous = -5L, previousAt = 1_000, raw = 3_000_000L, at = 2_000))
}

@Test
fun smoothEtaMs_clock_not_moving_forward_returns_raw() {
    assertEquals(3_600_000L, smoothEtaMs(previous = 3_000_000L, previousAt = null, raw = 3_600_000L, at = 2_000))
    assertEquals(3_600_000L, smoothEtaMs(previous = 3_000_000L, previousAt = 2_000, raw = 3_600_000L, at = 2_000))
    assertEquals(3_600_000L, smoothEtaMs(previous = 3_000_000L, previousAt = 3_000, raw = 3_600_000L, at = 2_000))
}

@Test
fun smoothEtaMs_one_second_move_stays_under_two_minutes() {
    val shown = smoothEtaMs(previous = 3_000_000L, previousAt = 0, raw = 3_600_000L, at = 1_000)
    assertEquals(3_070_502L, shown)
    assertTrue(shown!! - 3_000_000L < 120_000L)
}

@Test
fun smoothEtaMs_fifteen_seconds_covers_most_of_a_lasting_change() {
    val previous = 3_000_000L
    val raw = 1_500_000L
    val shown = smoothEtaMs(previous = previous, previousAt = 0, raw = raw, at = 15_000)
    assertEquals(1_730_032L, shown)
    val moved = previous - shown!!
    val gap = previous - raw
    assertTrue(moved * 100 >= gap * 80)
}

@Test
fun smoothEtaMs_alternating_minute_swing_stays_calm() {
    var shown = 3_000_000L
    var at = 0L
    repeat(10) { i ->
        at += 1_000
        val raw = if (i % 2 == 0) 3_600_000L else 3_000_000L
        val next = smoothEtaMs(previous = shown, previousAt = at - 1_000, raw = raw, at = at)!!
        assertTrue(kotlin.math.abs(next - shown) < 120_000L)
        assertTrue(next < 3_360_000L)
        shown = next
    }
}
```

Add `import kotlin.test.assertTrue` if it is missing.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.HeadersProgressStoreTest.smoothEtaMs_shows_the_first_raw_value`

Expected: FAIL to compile. Unresolved reference `smoothEtaMs`.

- [ ] **Step 3: Write the minimal implementation**

In `ProgressFormat.kt`, add `exp` to the kotlin.math imports. Add this below the imports and above `formatEta`:

```kotlin
const val ETA_SMOOTH_TAU_MS = 8_000L

fun smoothEtaMs(
    previous: Long?,
    previousAt: Long?,
    raw: Long?,
    at: Long,
    tauMs: Long = ETA_SMOOTH_TAU_MS,
): Long? =
    when {
        raw == 0L -> 0L
        raw == null -> if (previous != null && previous > 0L) previous else null
        previous == null || previous <= 0L -> raw
        previousAt == null || at <= previousAt -> raw
        else -> {
            val dt = (at - previousAt).toDouble()
            val alpha = 1.0 - exp(-dt / tauMs.toDouble())
            round(previous + alpha * (raw - previous)).toLong()
        }
    }
```

Rule order is the spec order. `raw == 0` snaps. A missing raw keeps a positive previous time. The first positive raw shows as itself. A clock that does not move forward shows the raw value. Otherwise ease with time constant `tauMs`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.HeadersProgressStoreTest`

Expected: PASS. If `3_070_502` or `1_730_032` is off by one, the `kotlin.math.exp` result differed. Recompute with `kotlin.math.round` and keep the spec bounds (`< 120_000` and at least 80%).

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/io/bluewallet/blueberry/ProgressFormat.kt \
  shared/src/commonTest/kotlin/io/bluewallet/blueberry/HeadersProgressStoreTest.kt
git commit -m "$(cat <<'EOF'
Ease a sync time toward the latest speed sample.

EOF
)"
```

---

### Task 2: Filters download

**Files:**
- Modify: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/FiltersProgressStore.kt` (`applyEvent`, the `nextEta` block)
- Test: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/FiltersProgressStoreTest.kt`

**Interfaces:**
- Consumes: `smoothEtaMs(previous: Long?, previousAt: Long?, raw: Long?, at: Long, tauMs: Long = ETA_SMOOTH_TAU_MS): Long?`
- Produces: `FiltersProgress.etaMs` is the eased time

- [ ] **Step 1: Write the failing test**

Append to `FiltersProgressStoreTest`. The second increase is a raw time of `3_287_351` ms (about 5 minutes above the shown `2_990_000`). Today the store stores that raw jump.

```kotlin
@Test
fun eta_eases_a_one_second_speed_swing() {
    val store = createFiltersProgressStore()
    store.applyEvent(at = 0, downloaded = 0, total = 600_000)
    store.applyEvent(at = 10_000, downloaded = 2_000, total = 600_000)
    assertEquals(2_990_000L, store.get().etaMs)

    store.applyEvent(at = 11_000, downloaded = 2_001, total = 600_000)
    assertEquals(3_024_940L, store.get().etaMs)
}
```

`3_024_940` is `smoothEtaMs(2_990_000, 10_000, 3_287_351, 11_000)`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.FiltersProgressStoreTest.eta_eases_a_one_second_speed_swing`

Expected: FAIL. Expected `3024940`, actual `3287351`.

- [ ] **Step 3: Write the minimal implementation**

In `FiltersProgressStore.applyEvent`, replace the `nextEta` block with:

```kotlin
val nextPercent = progressPercent(downloaded, total)
val isDone = total > 0 && downloaded >= total
val wasDone = prev.total > 0 && prev.downloaded >= prev.total
val reset = downloaded < prev.downloaded || (wasDone && !isDone)
val raw = if (isDone) 0L else estimateEtaMs(nextSamples, total)
val nextEta =
    smoothEtaMs(
        previous = if (reset) null else prev.etaMs,
        previousAt = if (reset) null else prev.at,
        raw = raw,
        at = at,
    )
```

Leave `nextProgressSamples`, `estimateEtaMs`, and `MAX_SAMPLES` unchanged. A finished job passes raw `0`, which snaps to `0`. A restart passes no previous time.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.FiltersProgressStoreTest`

Expected: PASS, including the older tests whose first revealed estimate is still the raw speed.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/io/bluewallet/blueberry/FiltersProgressStore.kt \
  shared/src/commonTest/kotlin/io/bluewallet/blueberry/FiltersProgressStoreTest.kt
git commit -m "$(cat <<'EOF'
Keep the filters download time from jumping.

EOF
)"
```

---

### Task 3: Chain tip and blocks download

**Files:**
- Modify: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/HeadersProgressStore.kt` (`applyEvent`, the `nextEta` block)
- Modify: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/BlocksMatchedStore.kt` (`applyEvent`, the `nextEta` block)
- Test: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/HeadersProgressStoreTest.kt`
- Test: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/BlocksMatchedStoreTest.kt`

**Interfaces:**
- Consumes: `smoothEtaMs(previous: Long?, previousAt: Long?, raw: Long?, at: Long, tauMs: Long = ETA_SMOOTH_TAU_MS): Long?`
- Produces: `HeadersProgress.etaMs` and `BlocksProgress.etaMs` are eased times

- [ ] **Step 1: Write the failing tests**

Append to `HeadersProgressStoreTest`:

```kotlin
@Test
fun eta_eases_a_one_second_speed_swing() {
    val store = createHeadersProgressStore()
    store.applyEvent(at = 0, downloaded = 0, total = 600_000, height = 0)
    store.applyEvent(at = 10_000, downloaded = 2_000, total = 600_000, height = 2_000)
    assertEquals(2_990_000L, store.get().etaMs)

    store.applyEvent(at = 11_000, downloaded = 2_001, total = 600_000, height = 2_001)
    assertEquals(3_024_940L, store.get().etaMs)
}
```

Append to `BlocksMatchedStoreTest`:

```kotlin
@Test
fun eta_eases_a_one_second_speed_swing() {
    val store = createBlocksMatchedStore()
    store.applyEvent(at = 0, downloaded = 0, matched = 600_000)
    store.applyEvent(at = 10_000, downloaded = 2_000, matched = 600_000)
    assertEquals(2_990_000L, store.get().etaMs)

    store.applyEvent(at = 11_000, downloaded = 2_001, matched = 600_000)
    assertEquals(3_024_940L, store.get().etaMs)
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.HeadersProgressStoreTest.eta_eases_a_one_second_speed_swing --tests io.bluewallet.blueberry.BlocksMatchedStoreTest.eta_eases_a_one_second_speed_swing`

Expected: FAIL. Expected `3024940`, actual `3287351`, for both.

- [ ] **Step 3: Write the minimal implementation**

In `HeadersProgressStore.applyEvent`, replace the `nextEta` block with:

```kotlin
val nextPercent =
    if (total == 0) 0 else min(100, (100 * downloaded) / total)
val isDone = total > 0 && downloaded >= total
val wasDone = prev.total > 0 && prev.downloaded >= prev.total
val reset = downloaded < prev.downloaded || (wasDone && !isDone)
val raw = if (isDone) 0L else estimateEtaMs(nextSamples, total)
val nextEta =
    smoothEtaMs(
        previous = if (reset) null else prev.etaMs,
        previousAt = if (reset) null else prev.at,
        raw = raw,
        at = at,
    )
```

In `BlocksMatchedStore.applyEvent`, replace the `nextEta` block with:

```kotlin
val nextPercent = progressPercent(downloaded, matched)
val isDone = downloaded >= matched
val wasDone = prev.downloaded >= prev.matched
val reset = downloaded < prev.downloaded || (wasDone && !isDone)
val raw = if (isDone) 0L else estimateEtaMs(nextSamples, matched)
val nextEta =
    smoothEtaMs(
        previous = if (reset) null else prev.etaMs,
        previousAt = if (reset) null else prev.at,
        raw = raw,
        at = at,
    )
```

`downloaded >= matched` includes `0 >= 0`, so an empty block queue still stores `0`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.HeadersProgressStoreTest --tests io.bluewallet.blueberry.BlocksMatchedStoreTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/io/bluewallet/blueberry/HeadersProgressStore.kt \
  shared/src/commonMain/kotlin/io/bluewallet/blueberry/BlocksMatchedStore.kt \
  shared/src/commonTest/kotlin/io/bluewallet/blueberry/HeadersProgressStoreTest.kt \
  shared/src/commonTest/kotlin/io/bluewallet/blueberry/BlocksMatchedStoreTest.kt
git commit -m "$(cat <<'EOF'
Keep the chain tip and blocks download times from jumping.

EOF
)"
```

---

### Task 4: Filters match

**Files:**
- Modify: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/MatchingProgressStore.kt` (`applyEvent`)
- Test: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/MatchingProgressStoreTest.kt`

**Interfaces:**
- Consumes: `smoothEtaMs(previous: Long?, previousAt: Long?, raw: Long?, at: Long, tauMs: Long = ETA_SMOOTH_TAU_MS): Long?`
- Produces: `MatchingProgress.etaMs` is the eased time. Origin tracking stays as it is.

- [ ] **Step 1: Write the failing test**

Append to `MatchingProgressStoreTest`. The fourth event's raw time is `20_350` ms. Today the store stores `20_350`. The eased time is `5_745`.

```kotlin
@Test
fun eta_eases_after_the_first_real_reading() {
    val store = createMatchingProgressStore()
    store.applyEvent(at = 1_000, scanned = 1_000, total = 5_000)
    store.applyEvent(at = 121_000, scanned = 1_100, total = 5_000)
    store.applyEvent(at = 121_100, scanned = 1_200, total = 5_000)
    assertEquals(3_800L, store.get().etaMs)

    store.applyEvent(at = 122_100, scanned = 1_300, total = 5_000)
    assertEquals(5_745L, store.get().etaMs)
}

@Test
fun eta_drops_when_the_scanned_count_goes_backwards() {
    val store = createMatchingProgressStore()
    store.applyEvent(at = 1_000, scanned = 0, total = 5_000)
    store.applyEvent(at = 2_000, scanned = 100, total = 5_000)
    store.applyEvent(at = 3_000, scanned = 200, total = 5_000)
    assertTrue(store.get().etaMs!! > 0L)

    store.applyEvent(at = 4_000, scanned = 50, total = 5_000)
    assertNull(store.get().etaMs)
}
```

Add `import kotlin.test.assertTrue` if it is missing. The backwards test passes on the current code. It fails if a missing raw value keeps a positive previous time across a restart. Run it again after the wire.

- [ ] **Step 2: Run the ease test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.MatchingProgressStoreTest.eta_eases_after_the_first_real_reading`

Expected: FAIL. Expected `5745`, actual `20350`.

- [ ] **Step 3: Write the minimal implementation**

Keep the origin logic that assigns `nextEta`. After that `if` / `else` block, and before the equality check, add:

```kotlin
val restart = (scanned < cur.scanned || wasDone) && !isDone
val shownEta =
    smoothEtaMs(
        previous = if (restart) null else cur.progress.etaMs,
        previousAt = if (restart) null else cur.at,
        raw = nextEta,
        at = at,
    )
```

Use `shownEta` in the equality check (`cur.progress.etaMs == shownEta`) and in `MatchingProgress.etaMs`. Do not change `etaFor`. A done event still sets `nextEta = 0`, and `smoothEtaMs` snaps that to `0`. A restart passes no previous time, so a missing raw value does not keep the old time.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.MatchingProgressStoreTest`

Expected: PASS. The old resume test still sees `38_000` on the first new reading.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/io/bluewallet/blueberry/MatchingProgressStore.kt \
  shared/src/commonTest/kotlin/io/bluewallet/blueberry/MatchingProgressStoreTest.kt
git commit -m "$(cat <<'EOF'
Keep the filters match time from jumping.

EOF
)"
```

---

### Task 5: Transaction parsing

**Files:**
- Modify: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/WalletTxsStore.kt` (`setBlockCounts` and `nextState`)
- Test: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/WalletTxsStoreEtaTest.kt`

**Interfaces:**
- Consumes: `smoothEtaMs(previous: Long?, previousAt: Long?, raw: Long?, at: Long, tauMs: Long = ETA_SMOOTH_TAU_MS): Long?`
- Produces: `WalletTxsSnapshot.etaMs` is the eased time while parsing is active

- [ ] **Step 1: Write the failing tests**

Append to `WalletTxsStoreEtaTest`. The third reading's raw time is `10_000` ms. Today both paths store `10_000`. The eased time is `8_235`.

```kotlin
@Test
fun apply_eases_a_one_second_speed_swing() {
    val store = createWalletTxsStore()
    store.setParsingActive(true)
    store.apply(snap(1000, 100, 1000))
    store.apply(snap(2000, 200, 1000))
    assertEquals(8000, store.get().etaMs)

    store.apply(snap(3000, 250, 1000))
    assertEquals(8235, store.get().etaMs)
}

@Test
fun setBlockCounts_eases_a_one_second_speed_swing() {
    val store = createWalletTxsStore()
    store.setParsingActive(true)
    store.setBlockCounts(100, 1000, at = 1000)
    store.setBlockCounts(200, 1000, at = 2000)
    assertEquals(8000, store.get().etaMs)

    store.setBlockCounts(250, 1000, at = 3000)
    assertEquals(8235, store.get().etaMs)
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.WalletTxsStoreEtaTest.apply_eases_a_one_second_speed_swing --tests io.bluewallet.blueberry.WalletTxsStoreEtaTest.setBlockCounts_eases_a_one_second_speed_swing`

Expected: FAIL. Expected `8235`, actual `10000`, for both.

- [ ] **Step 3: Write the minimal implementation**

Do not change `setParsingActive`. It already stores null and clears samples when parsing stops.

In `setBlockCounts`, keep the sample update. Replace the `etaMs` `when` with:

```kotlin
val reset = parsed < snap.blocksParsed || (wasDone && !isDone)
val raw =
    when {
        !cur.parsingActive -> null
        isDone -> 0L
        else -> estimateEtaMs(samples, total)
    }
val etaMs =
    when {
        !cur.parsingActive -> null
        isDone -> 0L
        at == null -> raw
        else ->
            smoothEtaMs(
                previous = if (reset) null else snap.etaMs,
                previousAt = if (reset) null else snap.at,
                raw = raw,
                at = at,
            )
    }
```

`estimateEtaMs` already returns null when `samples.size < 2`, so the old `samples.size < 2 -> null` branch is covered. A call with no `at` keeps today's raw value because the clock did not move.

In `nextState`, replace the `else` branch body with:

```kotlin
val prev = cur.snapshot
val backlogAgain = snapshot.blocksTotal > snapshot.blocksParsed
val wasDone = prev.blocksTotal > 0 && prev.blocksParsed >= prev.blocksTotal
val reset = snapshot.blocksParsed < prev.blocksParsed || (wasDone && backlogAgain)
val nextSamples =
    nextProgressSamples(
        cur.samples,
        prev.blocksParsed,
        prev.blocksTotal,
        at,
        snapshot.blocksParsed,
        snapshot.blocksTotal,
    )
val raw = if (backlogAgain) estimateEtaMs(nextSamples, snapshot.blocksTotal) else null
val nextEta =
    if (!backlogAgain) {
        null
    } else {
        smoothEtaMs(
            previous = if (reset) null else prev.etaMs,
            previousAt = if (reset) null else prev.at,
            raw = raw,
            at = at,
        )
    }
cur.copy(snapshot = snapshot.copy(etaMs = nextEta), samples = nextSamples)
```

A finished backlog still stores null, not `0`. That keeps `setBlockCounts_drops_the_speed_when_a_new_backlog_starts` green. Do not hold the previous time when the backlog is gone.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.WalletTxsStoreEtaTest --tests io.bluewallet.blueberry.WalletTxsHydrateTest`

Expected: PASS. The first revealed parse estimates stay `6000`, `8000`, `5000`, `4000`, `1000`, and `98_000`.

- [ ] **Step 5: Run the full gate and commit**

Run: `./gradlew ktlintCheck detekt :shared:jvmTest`

If `ktlintCheck` fails, run `./gradlew ktlintFormat` and then `./gradlew ktlintCheck` again. Do not format `vendor/*`. Fix new detekt findings. Do not edit `config/detekt/baselines/*`.

Expected: PASS.

```bash
git add shared/src/commonMain/kotlin/io/bluewallet/blueberry/WalletTxsStore.kt \
  shared/src/commonTest/kotlin/io/bluewallet/blueberry/WalletTxsStoreEtaTest.kt
git commit -m "$(cat <<'EOF'
Keep the transaction parse time from jumping.

EOF
)"
```

If format changed another file in this task, add that file too. Do not add `AGENTS.md` or `vendor/*`.

## Review changes

Four reviews after Task 5 replaced parts of this plan. The design spec has the current rules.

- The four copies of the speed sample code moved to one shared file, `ProgressEta.kt`. `smoothEtaMs` moved there from `ProgressFormat.kt`.
- Chain tip, filters download, blocks download, and transaction parsing smooth the speed, not the shown time. The speed is recent work over recent time, and older work fades over 8 seconds. Each gap counts once, by its length. The first speed is the average of 7 gaps. A pause keeps the speed, but three pauses in a row start the speed again.
- The shown time never shows done while work is left.
- Filters match keeps `smoothEtaMs`. It waits for 2 seconds of run time before it gives a time.
- The wallet store keeps no extra time field. Its speed state replaces the old sample list.
- The spec lists the known trade-offs: sparse updates, a sudden large slowdown, and stops that repeat between short bursts.
- The detekt baseline lost 10 entries for code that no longer exists or is no longer complex. No entry was added.
