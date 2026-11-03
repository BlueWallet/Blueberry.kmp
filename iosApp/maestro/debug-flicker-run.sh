#!/usr/bin/env bash
# DEBUG ONLY: run debug-flicker flows with screen recording. Usage: debug-flicker-run.sh <app> <out> <ios-major>
set -uo pipefail
APP="$1"; OUT="$(mkdir -p "$2" && cd "$2" && pwd)"; MAJOR="$3"
HERE="$(cd "$(dirname "$0")" && pwd)"
xcrun simctl list runtimes | tee "$OUT/runtimes.txt"
UDID="$(xcrun simctl list devices available -j | MAJOR="$MAJOR" python3 -c '
import json, os, re, sys
data = json.load(sys.stdin)["devices"]; want = int(os.environ["MAJOR"]); best = None
for rt, devs in data.items():
    m = re.search(r"iOS-(\d+)-(\d+)", rt)
    if not m or int(m.group(1)) != want: continue
    for d in devs:
        p = re.fullmatch(r"iPhone (\d+)( Pro)?", d["name"])
        if not p: continue
        key = (int(m.group(2)), p.group(2) is None, int(p.group(1)))
        if best is None or key > best[0]: best = (key, d["udid"], d["name"] + " / " + rt)
if not best: sys.exit(1)
print(best[1]); print(best[2], file=sys.stderr)
' 2>"$OUT/device.txt")" || { echo "no iOS $MAJOR simulator"; cat "$OUT/device.txt"; exit 0; }
cat "$OUT/device.txt"
xcrun simctl boot "$UDID" || true
xcrun simctl bootstatus "$UDID" -b
export SIM_UDID="$UDID"
# Start recording right before the flows; stop with SIGINT so the mp4 is finalized.
( sleep 20; xcrun simctl io "$UDID" recordVideo --codec h264 --force "$OUT/screen.mp4" ) > "$OUT/record.log" 2>&1 &
REC=$!
date -u +%FT%T.%3NZ > "$OUT/record-start.txt" || true
"$HERE/run-e2e.sh" "$APP" "$OUT/e2e" "$HERE/debug-flicker"; STATUS=$?
pkill -INT -f "recordVideo" || true
sleep 5
wait "$REC" 2>/dev/null || true
ls -la "$OUT"
exit 0
