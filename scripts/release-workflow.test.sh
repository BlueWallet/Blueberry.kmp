#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
workflow="$root/.github/workflows/release.yml"

if [[ ! -f "$workflow" ]]; then
  echo "missing $workflow" >&2
  exit 1
fi

require() {
  local needle="$1"
  if ! grep -F -q -- "$needle" "$workflow"; then
    echo "release.yml missing: $needle" >&2
    exit 1
  fi
}

require 'v[0-9][0-9][0-9][0-9].[0-9][0-9].[0-9][0-9]'
require 'cancel-in-progress: false'
require 'contents: read'
require 'contents: write'
require 'bash scripts/release-version.sh'
require 'ROCKETX_API_KEY'
require ':androidApp:assembleRelease'
require '-PversionName='
require '-PversionCode='
require '-PpackageVersion='
require 'apksigner'
require 'verify "$signed"'
require 'blueberry-upload'
require 'ANDROID_UPLOAD_KEYSTORE'
require 'rm -f'
require ':desktopApp:packageDeb'
require ':desktopApp:packageDmg'
require ':desktopApp:packageMsi'
require ':desktopApp:createDistributable'
require 'macos-latest'
require 'macos-15-intel'
require 'windows-latest'
require 'ubuntu-latest'
require 'fakeroot'
require 'WiX Toolset'
require 'actions/upload-artifact@v7'
require 'actions/download-artifact@v7'
require 'softprops/action-gh-release@v2'
require 'generate_release_notes: true'
require 'fail_on_unmatched_files: true'
require 'if-no-files-found: error'
require 'submodules: recursive'
require 'release-android'
require 'release-linux'
require 'release-macos-arm64'
require 'release-macos-x64'
require 'release-windows'
require 'android.apk'
require 'linux-x64.deb'
require 'linux-x64.zip'
require 'macos-arm64.dmg'
require 'macos-arm64.zip'
require 'macos-x64.dmg'
require 'macos-x64.zip'
require 'windows-x64.msi'
require 'windows-x64.zip'
require 'merge-multiple: true'

echo "release-workflow.test.sh passed"
