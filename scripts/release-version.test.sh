#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
script="$root/scripts/release-version.sh"

assert_eq() {
  local got="$1"
  local expected="$2"
  if [[ "$got" != "$expected" ]]; then
    echo "expected [$expected] got [$got]" >&2
    exit 1
  fi
}

assert_fail() {
  local stderr
  stderr="$(mktemp)"
  if "$script" "$@" >"/dev/null" 2>"$stderr"; then
    echo "expected failure: $*" >&2
    rm -f "$stderr"
    exit 1
  fi
  if [[ ! -s "$stderr" ]]; then
    echo "expected stderr for: $*" >&2
    rm -f "$stderr"
    exit 1
  fi
  rm -f "$stderr"
}

out="$("$script" v2026.10.08 20)"
assert_eq "$out" "$(printf '%s\n' \
  'VERSION_NAME=2026.10.08' \
  'VERSION_CODE=2026100820' \
  'PACKAGE_VERSION=26.10.8' \
  'FILE_DATE=2026-10-08')"

out="$("$script" v2026.01.08 00)"
assert_eq "$out" "$(printf '%s\n' \
  'VERSION_NAME=2026.01.08' \
  'VERSION_CODE=2026010800' \
  'PACKAGE_VERSION=26.1.8' \
  'FILE_DATE=2026-01-08')"

assert_fail v2026.10.8 20
assert_fail 2026.10.08 20
assert_fail v2026.10.08 24
assert_fail v2026.10.08 8
assert_fail

echo "release-version.test.sh passed"
