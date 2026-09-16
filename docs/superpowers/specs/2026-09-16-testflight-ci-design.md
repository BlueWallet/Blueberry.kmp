# TestFlight CI

Date: 2026-09-16
Status: approved (conversation)

## Goal

On every merge to `master`, GitHub Actions archives the iOS app and uploads it to TestFlight. Versioning is date-based at build time, not committed.

## Non-goals

- Play Store upload
- Fastlane / match
- Uploading unsigned PR builds
- Changing local Xcode automatic signing for day-to-day development

## Decisions

| Topic | Choice |
| --- | --- |
| Trigger | `push` to `master` (PR merge) and `workflow_dispatch` |
| Runner | `macos-latest`, same JDK/Gradle/Konan setup as `build.yml` ios |
| Tooling | `xcodebuild` archive + export; no Fastlane |
| Signing | Isolated GitHub secrets. New CI `IOS_DISTRIBUTION` (or `DISTRIBUTION`) cert + `IOS_APP_STORE` profile for `io.bluewallet.blueberry` |
| Marketing version | `YYYY.MM.DD` UTC, e.g. `2026.09.16` |
| iOS build | `YYYYMMDDHHMM` UTC, e.g. `202609161515` (`CURRENT_PROJECT_VERSION`) |
| Android `versionCode` | `YYYYMMDDHH` UTC, e.g. `2026091613` (Play max is 2_100_000_000; minutes/seconds do not fit) |
| Android `versionName` | Same `YYYY.MM.DD` when `-PversionName` / `-PversionCode` are passed; local/PR debug stays `1.0` / `1` |
| App ID | `6812759936`, bundle `io.bluewallet.blueberry`, team `A7W54YZ4WU` |
| Export compliance | `ITSAppUsesNonExemptEncryption=false` (same as BlueWallet) |
| Icon | Existing `app-icon-1024.png` (flatten to RGB if needed; App Store rejects alpha) |

## Secrets (GitHub, not git)

- `APP_STORE_CONNECT_ISSUER_ID`
- `APP_STORE_CONNECT_KEY_ID`
- `APP_STORE_CONNECT_API_KEY` (`.p8` PEM)
- `IOS_DIST_P12` (base64)
- `IOS_DIST_P12_PASSWORD`
- `IOS_APPSTORE_PROFILE` (base64 `.mobileprovision`)

Private materials live under `bluewalletsecure`, not this repo.

## Flow

1. Compute versions from UTC now.
2. Import the p12 into a temporary keychain; install the App Store profile.
3. `xcodebuild archive` Release, generic iOS device, manual signing, version overrides.
4. Export App Store IPA and upload with the App Store Connect API key.
5. One upload at a time (`concurrency` group `testflight-ios`, do not cancel in-flight uploads).
