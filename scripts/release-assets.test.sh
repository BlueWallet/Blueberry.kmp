#!/usr/bin/env bash
# Contract for the GitHub Release asset list and the App Store IPA export.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
release="$root/.github/workflows/release.yml"
testflight="$root/.github/workflows/testflight.yml"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

step_text() {
  local file="$1"
  local name="$2"
  awk -v name="$name" '
    $0 ~ "^      - name: " name "$" { in_step = 1 }
    in_step && printed && /^      - / { exit }
    in_step { printed = 1; print }
  ' "$file"
}

assert_same_step() {
  local name="$1"
  local left right
  left="$(step_text "$testflight" "$name" | normalize_versions)"
  right="$(step_text "$release" "$name" | normalize_versions)"
  if [[ -z "$left" ]]; then
    fail "testflight.yml is missing step: $name"
  fi
  if [[ -z "$right" ]]; then
    fail "release.yml is missing step: $name"
  fi
  if [[ "$left" != "$right" ]]; then
    echo "testflight $name:" >&2
    printf '%s\n' "$left" >&2
    echo "release $name:" >&2
    printf '%s\n' "$right" >&2
    fail "step does not match testflight: $name"
  fi
}

normalize_versions() {
  sed \
    -e 's/\${MARKETING_VERSION}/${VERSION}/g' \
    -e 's/\${VERSION_NAME}/${VERSION}/g' \
    -e 's/\${CURRENT_PROJECT_VERSION}/${BUILD}/g' \
    -e 's/\${VERSION_CODE}/${BUILD}/g'
}

files="$(awk '
  /^          files: \|/ { grab = 1; next }
  grab && /^          [^ ]/ { exit }
  grab && /^  [A-Za-z]/ { exit }
  grab { sub(/^            /, ""); print }
' "$release")"

expected="dist/blueberry-\${{ env.FILE_DATE }}-android.apk
dist/blueberry-\${{ env.FILE_DATE }}-linux-x64.deb
dist/blueberry-\${{ env.FILE_DATE }}-macos-arm64.dmg
dist/blueberry-\${{ env.FILE_DATE }}-macos-x64.dmg
dist/blueberry-\${{ env.FILE_DATE }}-windows-x64.msi
dist/blueberry-\${{ env.FILE_DATE }}-ios.ipa"

if [[ "$files" != "$expected" ]]; then
  echo "publish files:" >&2
  printf '%s\n' "$files" >&2
  fail "publish files are not the six release assets"
fi

if grep -E -q 'zip-suffix|createDistributable|altool|fakeroot zip' "$release"; then
  fail "release.yml still publishes zips, builds a distributable zip input, or uploads to TestFlight"
fi

ios_job="$(awk '
  /^  ios:/ { grab = 1 }
  grab && /^  [a-z]/ && !/^  ios:/ { exit }
  grab { print }
' "$release")"

if [[ -z "$ios_job" ]]; then
  fail "release.yml has no ios job"
fi

printf '%s\n' "$ios_job" | grep -q 'ROCKETX_API_KEY: \${{ secrets.ROCKETX_API_KEY }}' \
  || fail "ios job does not pass ROCKETX_API_KEY"
printf '%s\n' "$ios_job" | grep -q 'date -u' \
  && fail "ios job must use the release version, not date -u"
printf '%s\n' "$ios_job" | grep -q 'APP_STORE_CONNECT' \
  && fail "ios job must not import the App Store Connect API key"

assert_same_step "Import signing certificate"
assert_same_step "Install provisioning profile"
assert_same_step "Archive iOS app"
assert_same_step "Export IPA"

echo "release-assets.test.sh passed"
