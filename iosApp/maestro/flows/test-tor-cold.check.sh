#!/usr/bin/env bash
# Post-flow check: the cold tap must have produced a real HTTP 2xx over Tor. Prints its elapsed time.
set -euo pipefail
ok="$(grep -c 'TORCHECK http status=2' "$APP_LOG" || true)"
elapsed="$(grep -o 'TORCHECK done ok elapsed=[0-9.]*s' "$APP_LOG" | sed -n 1p | sed 's/.*=//' || true)"
echo "Tor HTTP 2xx so far: $ok; cold elapsed: ${elapsed:-unknown}"
[ "$ok" -ge 1 ]
