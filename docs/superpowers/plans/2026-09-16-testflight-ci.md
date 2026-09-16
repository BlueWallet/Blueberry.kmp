# TestFlight CI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merge-to-master GitHub Action that archives Blueberry for iOS and uploads it to TestFlight, with date-based versions at build time.

**Architecture:** Dedicated `.github/workflows/testflight.yml` on `macos-latest`. Signing material is GitHub secrets plus a one-time App Store Connect cert/profile. Versions come from `scripts/app-version.py` and are passed into `xcodebuild` / Gradle; they are not committed.

**Tech Stack:** GitHub Actions, Xcode `xcodebuild`, App Store Connect API 4.4.1, Gradle Kotlin DSL.

**Spec:** `docs/superpowers/specs/2026-09-16-testflight-ci-design.md`

## Global Constraints

- Do not commit `.p8`, `.p12`, or `.mobileprovision`.
- Do not use Fastlane or BlueWallet match.
- Do not upload on pull_request.
- Android `versionCode` must stay ≤ 2_100_000_000.
- Leave the delta uncommitted unless asked for a commit/PR.
- After Kotlin/Gradle edits: `./gradlew ktlintCheck detekt`.

---

### Task 1: Version helper

**Files:**
- Create: `scripts/app-version.py`
- Modify: `androidApp/build.gradle.kts`

**Interfaces:**
- Produces: `MARKETING_VERSION`, `CURRENT_PROJECT_VERSION`, `ANDROID_VERSION_CODE` on stdout as `KEY=value`

- [ ] **Step 1:** Script prints UTC marketing `YYYY.MM.DD`, iOS build `YYYYMMDDHHMM`, Android `YYYYMMDDHH`
- [ ] **Step 2:** Verify a fixed epoch (1789566628 → 2026-09-16 13:50 UTC)
- [ ] **Step 3:** Gradle reads `-PversionName` / `-PversionCode` with fallback `1.0` / `1`

### Task 2: Store listing blockers

**Files:**
- Modify: `iosApp/iosApp/Info.plist`
- Modify: `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png` if it still has an alpha channel

- [ ] **Step 1:** Set `ITSAppUsesNonExemptEncryption` to `false`
- [ ] **Step 2:** Flatten the 1024 icon to opaque RGB if needed

### Task 3: CI certificate and profile

**Files:** outside the repo (`bluewalletsecure`); GitHub secrets only

- [ ] **Step 1:** Create `IOS_DISTRIBUTION` or `DISTRIBUTION` cert via `POST /v1/certificates`
- [ ] **Step 2:** Create `IOS_APP_STORE` profile `Blueberry App Store` for bundle `AJYD9AKQ98`
- [ ] **Step 3:** Store p12/profile/password in `bluewalletsecure`; `gh secret set` on `BlueWallet/Blueberry.kmp`

### Task 4: Workflow

**Files:**
- Create: `.github/workflows/testflight.yml`
- Create: `iosApp/ExportOptions.plist`

- [ ] **Step 1:** ExportOptions: method `app-store-connect`, manual signing, team `A7W54YZ4WU`, profile `Blueberry App Store` for `io.bluewallet.blueberry`
- [ ] **Step 2:** Workflow on `master` push + `workflow_dispatch`; import cert/profile; archive; export; upload
- [ ] **Step 3:** `ktlintCheck` / `detekt` for Gradle edits; confirm workflow YAML parses
