#!/usr/bin/env bash
# Post-flow check: cold + warm taps must each have produced their own HTTP 2xx over Tor (>= 2 total),
# so a stale "OK" from the cold tap can't pass the warm flow. Prints the warm elapsed time.
set -euo pipefail
ok="$(grep -c 'TORCHECK http status=2' "$APP_LOG" || true)"
elapsed="$(grep -o 'TORCHECK done ok elapsed=[0-9.]*s' "$APP_LOG" | sed -n 2p | sed 's/.*=//' || true)"
echo "Tor HTTP 2xx so far: $ok; warm elapsed: ${elapsed:-unknown}"
[ "$ok" -ge 2 ]
