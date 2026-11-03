#!/usr/bin/env bash
# Runs every Maestro flow in iosApp/maestro/flows against Blueberry.app on an iPhone simulator.
# Usage: run-e2e.sh <path/to/Blueberry.app> <out-dir> [flows-dir]
#
# One Maestro invocation runs all flows (the XCTest driver takes minutes to start on CI, so it is
# started once). The app is installed fresh, launched once with its stdout captured, and flows share
# that app process in the order set by flows/config.yaml. After Maestro, each flow's optional
# <flow>.check.sh runs with APP_LOG and FLOW_OUT set. Output: <out-dir>/flows/<flow>/ (screenshots,
# Maestro commands and logs, check.log), <out-dir>/app-stdout.log, <out-dir>/report.xml.
set -euo pipefail

APP="$1"
OUT="$(mkdir -p "$2" && cd "$2" && pwd)"
HERE="$(cd "$(dirname "$0")" && pwd)"
FLOWS="$(cd "${3:-$HERE/flows}" && pwd)"
BUNDLE=io.bluewallet.blueberry

UDID="${SIM_UDID:-}"
if [ -z "$UDID" ]; then
  # Prefer a plain "iPhone N" on the newest iOS runtime.
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
        name = d["name"]
        if not name.startswith("iPhone"):
            continue
        plain = re.fullmatch(r"iPhone (\d+)", name)
        key = (plain is not None, version, int(plain.group(1)) if plain else 0)
        if best is None or key > best[0]:
            best = (key, d["udid"], d["name"], runtime)
print(best[1]); print(best[2] + " / " + best[3], file=sys.stderr)
')"
fi
echo "Simulator: $UDID"
xcrun simctl boot "$UDID" || true
xcrun simctl bootstatus "$UDID" -b
xcrun simctl uninstall "$UDID" "$BUNDLE" || true
xcrun simctl install "$UDID" "$APP"

# The app logs with println (stdout); a pty keeps it line-buffered.
xcrun simctl launch --console-pty "$UDID" "$BUNDLE" > "$OUT/app-stdout.log" 2>&1 &
LAUNCH_PID=$!
sleep 5

# The first XCTest driver start on a fresh runner can take minutes.
export MAESTRO_DRIVER_STARTUP_TIMEOUT="${MAESTRO_DRIVER_STARTUP_TIMEOUT:-600000}"

STATUS=0
maestro --udid "$UDID" test \
  --test-output-dir "$OUT/maestro" \
  --format junit --output "$OUT/report.xml" \
  "$FLOWS" || STATUS=$?
xcrun simctl io "$UDID" screenshot "$OUT/final.png" || true
kill "$LAUNCH_PID" 2>/dev/null || true
grep -E "TORCHECK|echalote\.[a-z.]+\.timing" "$OUT/app-stdout.log" > "$OUT/tor-lines.log" || true

# Per-flow Maestro result from the JUnit report: "<flow> <passed|failed|missing>".
flow_result() {
  python3 - "$OUT/report.xml" "$1" <<'PY'
import sys, xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception:
    print("missing"); sys.exit()
for tc in root.iter("testcase"):
    if tc.get("name") == sys.argv[2] or tc.get("id") == sys.argv[2]:
        bad = tc.find("failure") is not None or tc.find("error") is not None
        print("failed" if bad else "passed"); sys.exit()
print("missing")
PY
}

SUMMARY="$OUT/summary.md"
{
  echo "### E2E iOS simulator"
  echo ""
  echo "| flow | maestro | check |"
  echo "|---|---|---|"
} > "$SUMMARY"
mkdir -p "$OUT/flows"
for f in "$FLOWS"/*.yaml; do
  flow="$(basename "$f" .yaml)"
  [ "$flow" = config ] && continue
  dest="$OUT/flows/$flow"
  mkdir -p "$dest"
  src="$(find "$OUT/maestro" -mindepth 2 -maxdepth 2 -type d -name "$flow" 2>/dev/null | head -n 1 || true)"
  [ -n "$src" ] && cp -R "$src/." "$dest/"
  result="$(flow_result "$flow")"
  check="-"
  if [ -x "$FLOWS/$flow.check.sh" ]; then
    if APP_LOG="$OUT/app-stdout.log" FLOW_OUT="$dest" "$FLOWS/$flow.check.sh" > "$dest/check.log" 2>&1; then
      check="ok: $(tail -n 1 "$dest/check.log")"
    else
      check="FAILED: $(tail -n 1 "$dest/check.log")"
      STATUS=1
    fi
  fi
  [ "$result" = passed ] || STATUS=1
  echo "| $flow | $result | $check |" >> "$SUMMARY"
done
cat "$SUMMARY" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
echo "---- Tor lines from the app ----"
tail -n 80 "$OUT/tor-lines.log" || true
exit "$STATUS"
