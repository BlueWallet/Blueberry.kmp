#!/usr/bin/env bash
# Boots an iPhone simulator, installs Blueberry.app, captures the app's stdout (Tor timing lines),
# and drives Settings > Test Tor with Maestro. Usage: run-test-tor.sh <path/to/Blueberry.app> <out-dir>
set -euo pipefail

APP="$1"
OUT="$(mkdir -p "$2" && cd "$2" && pwd)"
HERE="$(cd "$(dirname "$0")" && pwd)"
BUNDLE=io.bluewallet.blueberry

UDID="$(xcrun simctl list devices available -j | python3 -c '
import json, re, sys
data = json.load(sys.stdin)["devices"]
best = None
for runtime, devices in data.items():
    m = re.search(r"iOS-(\d+)-(\d+)", runtime)
    if not m:
        continue
    version = (int(m.group(1)), int(m.group(2)))
    for d in devices:
        if not d["name"].startswith("iPhone") or "Pro" in d["name"] or "Plus" in d["name"]:
            continue
        key = (version, d["name"])
        if best is None or key > best[0]:
            best = (key, d["udid"], d["name"], runtime)
print(best[1]); print(best[2] + " / " + best[3], file=sys.stderr)
')"
echo "Simulator: $UDID"
xcrun simctl boot "$UDID" || true
xcrun simctl bootstatus "$UDID" -b
xcrun simctl install "$UDID" "$APP"

# The app logs with println (stdout); a pty keeps it line-buffered.
xcrun simctl launch --console-pty "$UDID" "$BUNDLE" > "$OUT/app-stdout.log" 2>&1 &
LAUNCH_PID=$!
sleep 5

elapsed_on_screen() {
  maestro --udid "$UDID" hierarchy > "$OUT/hierarchy-$1.json" 2>/dev/null || true
  python3 - "$OUT/hierarchy-$1.json" <<'PY'
import json, re, sys
raw = open(sys.argv[1]).read()
start = raw.find("{")
found = []
def walk(node):
    if isinstance(node, dict):
        for k, v in node.items():
            if isinstance(v, str) and re.fullmatch(r"\d+(\.\d)?s", v.strip()):
                found.append(v.strip())
            walk(v)
    elif isinstance(node, list):
        for v in node:
            walk(v)
if start >= 0:
    walk(json.loads(raw[start:]))
print(found[0] if found else "unknown")
PY
}

STATUS=0
maestro --udid "$UDID" test --test-output-dir "$OUT/maestro" "$HERE/test-tor-cold.yaml" || STATUS=$?
COLD="unknown"; WARM="unknown"
if [ "$STATUS" -eq 0 ]; then
  COLD="$(elapsed_on_screen cold)"
  maestro --udid "$UDID" test --test-output-dir "$OUT/maestro-warm" "$HERE/test-tor-warm.yaml" || STATUS=$?
  [ "$STATUS" -eq 0 ] && WARM="$(elapsed_on_screen warm)"
fi
xcrun simctl io "$UDID" screenshot "$OUT/final.png" || true
find "$OUT" -name '*.png' -path '*maestro*' -exec cp {} "$OUT/" \; || true
kill "$LAUNCH_PID" 2>/dev/null || true

echo "---- Tor lines from the app ----"
grep -E "TORCHECK|echalote\.[a-z.]+\.timing" "$OUT/app-stdout.log" | tail -n 80 || true
OK_LINES="$(grep -c 'TORCHECK http status=2' "$OUT/app-stdout.log" || true)"
{
  echo "### iOS simulator Test Tor"
  echo ""
  echo "| | value |"
  echo "|---|---|"
  echo "| Maestro flow | $([ "$STATUS" -eq 0 ] && echo passed || echo "failed ($STATUS)") |"
  echo "| Test Tor, cold (first tap) | $COLD |"
  echo "| Test Tor, warm (second tap) | $WARM |"
  echo "| HTTP 2xx over Tor (app log) | $OK_LINES |"
} | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
echo "TEST_TOR_COLD=$COLD TEST_TOR_WARM=$WARM"
exit "$STATUS"
