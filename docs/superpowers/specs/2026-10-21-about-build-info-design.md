# Build info card in About

Date: 2026-10-21  
Status: approved (conversation)

## Goal

About gets a new card at the top of its list, above Changelog, that shows the version the app was built as and the commit it was built from. Tapping it opens that commit on GitHub in the external browser, like a Changelog row.

## Context

- `shared/build.gradle.kts` task `generateBuildSources` already writes `BuildInfo.kt` (`GIT_LOG`, the last 20 non-merge commits) into a `commonMain` source dir, using the `GitOutput` `ValueSource` and the `gitRev` provider (`GIT_COMMIT` env var, else `HEAD`). Android, desktop and iOS (Xcode runs `:shared:embedAndSignAppleFrameworkForXcode`) all compile it.
- The configuration cache and build cache are on, so every value the task reads must be a task input.
- Version today, per platform: Android reads Gradle property `versionName` (CI passes `-PversionName=YYYY.MM.DD`, local falls back to `1.0`); iOS uses Xcode's `MARKETING_VERSION` (CI passes it to `xcodebuild`, `iosApp/Configuration/Config.xcconfig` sets `1.0`); desktop reads `-PpackageVersion` (Windows-safe `YY.M.D`).
- About rows are `LinkRow` cards (blue title, muted subtitle, chevron); `changelogCommitUrl(sha)` builds the GitHub commit URL.

## Non-goals

- Reading the version from the OS at runtime.
- Build number / version code, dates, branch names, dirty-tree markers.
- A separate footer (layerzwallet style); the card replaces it.

## Decisions

| Topic | Choice |
| --- | --- |
| Placement | New first item of the About list, above Changelog. |
| Title | `Version <version>`, e.g. `Version 2026.10.21`; `Version dev` when no version was supplied. |
| Subtitle | `Built from <first 7 chars of sha>`; `Commit unknown` when the sha is empty or not a hex sha. |
| Tap | Opens `changelogCommitUrl(sha)` through `LocalUriHandler`. Not tappable and no chevron when the commit is unknown. |
| `GIT_SHA` | `git rev-parse --verify <gitRev>^{commit}` through `GitOutput`, trimmed; includes merge commits (a PR build's merge commit is what it was built from). `""` on any failure. |
| `VERSION` | First non-blank of: Gradle property `versionName` (`-P` is global, so the Android workflows already pass it), env var `MARKETING_VERSION` (Xcode exports build settings to the run-script phase that calls Gradle), else `dev`. Trimmed. |
| Gradle inputs | `buildVersion` and `gitSha` are registered with `inputs.property`, so a new commit, a changed `GIT_COMMIT`, `versionName` or `MARKETING_VERSION` regenerates `BuildInfo.kt`. Values are escaped with the existing `kotlinString()`. |
| Desktop release | `release.yml` desktop job gets `VERSION_NAME: ${{ needs.version.outputs.version_name }}` and its Package step passes `-PversionName="${{ env.VERSION_NAME }}"`, so desktop shows `2026.10.21`, not the Windows-mangled `26.10.21`. |
| Logic | Pure `buildInfoCard(version, sha): BuildInfoCard` in `commonMain` (`BuildInfoCard.kt`) returns title, subtitle and nullable url; unit-tested in `commonTest`. |
| Row | `LinkRow`'s `onClick` becomes nullable; `null` drops `clickable` and the chevron and merges the texts into one screen-reader node. Existing callers are unchanged. |

## Workflow coverage

| Workflow | Version shown |
| --- | --- |
| `release.yml` android | `-PversionName` already passed. |
| `release.yml` ios | `MARKETING_VERSION="${VERSION_NAME}"` already passed to `xcodebuild`. |
| `release.yml` desktop | Added `-PversionName`. |
| `play-internal.yml` | `-PversionName` already passed. |
| `testflight.yml` | `MARKETING_VERSION` already passed. |
| `build.yml` (PR builds) | android/desktop: none, shows `dev`; ios: xcconfig `1.0`. Debug-only artifacts, acceptable. |

## Testing

- `BuildInfoCardTest` (commonTest): version shown, blank version becomes `dev`, sha shortened to 7 chars, URL is the commit URL, empty/garbage sha gives `Commit unknown` and null URL, whitespace trimmed.
- Generated file check: `-PversionName=2026.10.21` gives `VERSION = "2026.10.21"`; no property gives `"dev"`; `GIT_COMMIT=<merge sha>` gives that sha in `GIT_SHA`.
- Android emulator screenshot of the top of About.
