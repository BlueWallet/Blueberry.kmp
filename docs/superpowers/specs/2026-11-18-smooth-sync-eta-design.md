# Steady sync ETA — design

## Problem

Filters DL can show 50m one second and 60m the next. The other sync times use the same kind of short speed sample, so they can jump the same way.

The speed sample for chain tip, filters download, and blocks download is the last 8 increases. Those updates can arrive about 10 times a second, so the sample often covers less than a second. A small change in that moment moves a long remaining job by many minutes. Filters match uses one speed from the start of the run. That speed is steadier later, but the first readings can still jump. The top sync time is the longest of these, so it jumps with them.

## Decision

Measure speed only over real work, and smooth the speed, not the shown time.

- The speed is recent work over recent time. Each gap between increases adds its work and its length, and older work fades with an 8 second time constant. Each gap counts once, by its length, so a short stall moves the time a little and the next normal gaps move it back. Work and time are added, not speeds, so the speed stays correct on average when updates come at irregular times.
- The first speed is the average of the first 7 gaps, that is 8 increases. The first gap starts at the first increase, so a wait for peers or for the first batch does not count as work. A job shows no time before that. The first speed counts as at least 8 seconds of work, so the first seconds stay calm. A long wait right after the first increase still counts in the first average, because no stored gap shows that it is a pause. The same is true for the first gap after three pauses in a row.
- On a stream with gaps of up to about 1 second, when the speed halves and stays low, the shown time covers about 75% of the change after 15 seconds and about 85% after 20 seconds. When the speed doubles, it covers about 90% after 15 seconds. A one-second swing of about 10 minutes moves the shown time by about one minute.
- No single gap fades older work by more than 2 seconds, so one slow gap cannot replace the history. On a fast stream, one slow gap raises the time by at most about a third.
- A pause does not count. A pause is a gap longer than 2 seconds and longer than 8 usual gaps. The usual gap comes from the last 7 gaps that were not pauses, so a second wait right after a pause is a pause too. Work that gets a little slower is not a pause.
- Three pauses in a row mean the work slowed down for good. The speed starts again from the third pause, so the job shows no time for the next 7 increases.
- The shown time is the work left at this speed. Finished work shows done at once. A job with work left never shows done. Transaction parsing keeps one old exception: a full rebuild with no work left shows no time.
- A count that goes back keeps the speed. Finished work that grows again drops the speed, so the job shows no time until 8 new increases.
- Filters match keeps its whole-run speed. It gives a time only after 2 seconds of run time, so the first reading is not noise. Its shown time counts down with the clock and moves toward each new estimate with the same 8 second time constant. A count that goes back starts a new run and shows no time until the new run gives a time. The count goes back when new wallet addresses need a rescan, and then the old time is wrong.

Trade-off: when updates are sparse and irregular, 8 seconds hold little data. An example is one saved block every 1.5 seconds on a slow link. The shown time stays correct on average, but it moves more: about 25% up or down, and a rare long gap can raise it up to about 2 times for a short time. More updates make the time calmer. At about 10 updates a second, as in filters download on a good link, the time moves by about 7%. Sparse updates also show a lasting change more slowly, because each gap fades older work by at most 2 seconds. With 3 second gaps, about half of a halving shows after 15 seconds, and about 90% after a minute.

Trade-off: when the work suddenly gets so much slower that the new gaps are longer than 2 seconds and longer than 8 usual gaps, and it stays slow, most new gaps are pauses. If the new gaps are steady, three pauses in a row start the speed again, and the right time shows after about 20 to 30 seconds. If the new gaps vary a lot, some of them count, and the time catches up over about a minute.

Trade-off: when the work keeps stopping for more than 2 seconds between short bursts, each stop is a pause, so the stops never count and the shown time is too short. An example is a weak mobile link that stops for 3 seconds after each second of work. There the time shows about a quarter of the real time. A stop that counts as up to the pause limit fixes only short stops, and then one single wait raises the time by up to about 2.5 times. A single wait is more common, so stops stay out.

Edge: the times come from the wall clock. If the clock steps back, the work done until the clock catches up counts as one very short gap. After a 60 second step back, the time shows about an eighth of the real time at first, and it comes back within 10% after about 35 seconds. Late updates that carry an older time are normal, and they must add their work to the next gap, so the speed does not try to detect a clock step.

This covers chain tip, filters download, filters match, blocks download, the top sync time, and transaction parsing. The top time stays the longest of the four sync bars. It gets no second smoothing, because the bars are already smooth.

The partner swap time stays the value the partner sent.

## Behavior

`shared/src/commonMain/kotlin/io/bluewallet/blueberry/ProgressEta.kt` holds the shared code:

- `ProgressSpeed(last, gaps, recentCount, recentMs, pauses)` holds the last increase, the last 7 gaps that were not pauses, the faded sums of work and time, and the number of pauses in a row. The speed is `recentCount / recentMs`, in counts per ms.
- `ProgressSpeed.next(prevCount, count, at, restarted)`:
  1. `restarted` returns an empty speed.
  2. A count lower than `prevCount` keeps the speed and the gaps, and forgets the last increase.
  3. No increase, or no time, changes nothing.
  4. The first increase becomes the last increase and keeps the speed.
  5. An increase no later than the last increase changes nothing. The next gap includes it.
  6. An increase after a pause becomes the last increase, keeps the speed, and adds 1 to `pauses`. The third pause in a row returns an empty speed with this increase as the last increase.
  7. Otherwise the gap joins the last 7 gaps, and `pauses` goes back to 0. With a speed, both sums fade by `exp(-min(gap, 2000) / 8000)` and add the work and the length of the gap. With no speed yet and 7 gaps, the sums are the work and the length of the 7 gaps, scaled so that the time is at least 8000 ms.
- `ProgressSpeed.etaMs(count, total)` returns null with no speed, 0 when the count reached the total, and else the work left at the speed, at least 1.
- `smoothEtaMs(previous, previousAt, raw, at)`:
  1. `raw == null` keeps a positive `previous`. Otherwise it returns null.
  2. `raw <= 0` returns `raw`.
  3. No positive `previous`, or no `previousAt`, returns `raw`.
  4. Otherwise `elapsed = max(0, at - previousAt)`, `left = previous - elapsed`, and `alpha = 1 - exp(-elapsed / 8000)`. The result is `left + alpha * (raw - left)`, rounded, and at least 1.

`HeadersProgressStore`, `FiltersProgressStore`, `BlocksMatchedStore`, and `WalletTxsStore` keep a `ProgressSpeed` and show its `etaMs`. `MatchingProgressStore` uses `smoothEtaMs` with its run speed, and its run estimate is at least 1 while work is left.

`WalletTxsStore` feeds the speed from full rebuilds and from count updates, only while parsing is active. A count update with no time uses the known speed with the new counts. A metadata update installs the same counts again and changes nothing. Stopping parsing clears the time and the speed at once.

## Testing

- `ProgressEtaTest` covers the speed: first increase, the average of 7 gaps, a later gap with fading, a short stall, the 2 second fade limit, a pause and a second wait right after it, a third pause in a row, slow steady work, an increase at the same time, no time, restart, a count that goes back, and the time left. It also covers `smoothEtaMs`: first time, done, missing estimate, a clock that does not move forward, a steady countdown with no lag, a one-second swing under 2 minutes, at least 80% of a lasting change after 15 seconds, an alternating swing, and no done while work is left.
- Each store test checks the wiring: no time before 8 increases, done, and new work after done. The chain tip, filters download, and transaction parse tests also check the time after a one-second speed swing.
- The chain tip, filters download, and blocks download tests check that the wait before the first increase does not count.
- `FiltersProgressStoreTest` checks that a short stall counts once, and that the speed holds through a pause and a second wait right after it.
- `BlocksMatchedStoreTest` checks that a stale lower reading keeps the speed.
- `MatchingProgressStoreTest` checks the 2 second warm-up, that a count that goes back shows no time until the new run gives one, and that it never shows done while work is left.
- `WalletTxsStoreEtaTest` checks a mix of full rebuilds and count updates, a count update with no time, and a metadata update.
