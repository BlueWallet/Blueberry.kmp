#!/usr/bin/env bash
# Prints the TestFlight "What to Test" text for the build commit.
# Usage: testflight_notes.sh <before-sha or empty> <head-sha>
# Lists non-merge commit subjects in before..head. Falls back to the latest
# 10 non-merge subjects when before is empty, all zeros or not in the history.
set -euo pipefail

BEFORE="${1:-}"
HEAD_SHA="${2:-HEAD}"

SHORT="$(git rev-parse --short "$HEAD_SHA")"
if [[ -n "$BEFORE" && ! "$BEFORE" =~ ^0+$ ]] && git cat-file -e "${BEFORE}^{commit}" 2>/dev/null; then
  SUBJECTS="$(git log --no-merges --format='- %s' "${BEFORE}..${HEAD_SHA}")"
else
  SUBJECTS="$(git log --no-merges --format='- %s' -n 10 "$HEAD_SHA")"
fi
if [[ -z "$SUBJECTS" ]]; then
  SUBJECTS="- $(git log -1 --format='%s' "$HEAD_SHA")"
fi

printf 'Build commit %s\n\n%s\n' "$SHORT" "$SUBJECTS"
