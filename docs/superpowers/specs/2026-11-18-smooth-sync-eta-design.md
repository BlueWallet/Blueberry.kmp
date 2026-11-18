# Steady sync ETA — design

## Problem

Filters DL can show 50m one second and 60m the next. The other sync times use the same kind of short speed sample, so they can jump the same way.

The speed sample for chain tip, filters download, and blocks download is the last 8 increases. Those updates can arrive about 10 times a second, so the sample often covers less than a second. A small change in that moment moves a long remaining job by many minutes. Filters match uses one speed from the start of the run. That speed is steadier later, but the first readings can still jump. The top sync time is the longest of these, so it jumps with them.

## Decision

Keep the current speed sample. Ease the time on screen toward each new sample.

- The first estimate for a job shows at once.
- After that, the shown time follows the latest estimate with an 8 second time constant.
- A lasting change is about 85% complete after 15 seconds.
- A one-second swing of about 10 minutes moves the shown time by about one minute, then it settles if the swing was noise.
- Finished work clears the time at once. The shown time does not ease down through the minutes.
- A restarted job drops the old time. The next estimate shows at once.
- A tick with no new estimate keeps the last positive time.

This covers chain tip, filters download, filters match, blocks download, the top sync time, and transaction parsing. The top time stays the longest of the four sync bars. It gets no second ease, because the bars are already eased.

The partner swap time stays the value the partner sent.

The 8-increase speed sample stays as it is. A longer sample alone can still step when a burst enters or leaves the window, and a longer sample plus this ease would make a real change take more than 15 seconds.

## Behavior

`ETA_SMOOTH_TAU_MS` is `8_000`. `smoothEtaMs(previous, previousAt, raw, at, tauMs = ETA_SMOOTH_TAU_MS)` lives in `shared/src/commonMain/kotlin/io/bluewallet/blueberry/ProgressFormat.kt` and uses `kotlin.math.round`.

1. `raw == 0` returns `0`.
2. `raw == null` returns `previous` when `previous` is positive. Otherwise it returns null.
3. `previous == null` or `previous <= 0` returns `raw`.
4. `previousAt == null` or `at <= previousAt` returns `raw`.
5. Otherwise `dt = at - previousAt`, `alpha = 1 - exp(-dt / tauMs)`, and the result is `previous + alpha * (raw - previous)`, rounded with `kotlin.math.round`.

These stores save that result in the `etaMs` the screen already reads:

- `HeadersProgressStore`
- `FiltersProgressStore`
- `MatchingProgressStore`
- `BlocksMatchedStore`
- `WalletTxsStore`, both in `setBlockCounts` and in `nextState`

On a restart, the store passes no previous time, so rule 3 shows the next raw estimate at once. A finished job stores `0` and does not ease from the old time.

Restart means the count went backwards, or finished work started again. Parsing uses the same rule. Stopping parsing still clears the time and the sample history at once. The next run starts clean.

## Testing

- `smoothEtaMs` shows the first raw value as-is.
- A raw value of 0 snaps to 0 from a large previous time.
- A missing raw value with no positive previous time returns null.
- A missing raw value keeps a positive previous time.
- A non-positive previous time returns the new raw value.
- A clock that does not move forward returns the raw value.
- One second of easing from 50 minutes toward a raw 60 minutes changes the shown time by less than 2 minutes.
- A raw value held at half the shown time for 15 seconds moves the shown time at least 80% of the way.
- The shown time starts at 50 minutes. Ten one-second updates then alternate the raw time between 60 and 50 minutes. Each update changes the shown time by less than 2 minutes, and the shown time stays under 56 minutes.
- The `smoothEtaMs` checks live in `HeadersProgressStoreTest`.
- Filters download, after it already has an estimate, does not store the raw jump on the next increase. The stored time is the eased time. That check lives in `FiltersProgressStoreTest`.
- The existing store tests still pass. Their first revealed estimate is the raw speed, and a finished or restarted job still clears it.
