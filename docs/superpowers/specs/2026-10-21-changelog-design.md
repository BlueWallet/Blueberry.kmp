# Changelog in About

Date: 2026-10-21  
Status: approved (conversation)

## Goal

About gets a "Changelog" row, first in its list. It opens a Changelog screen listing the subjects of the latest 20 non-merge commits of the build, newest first. Tapping a commit opens it on GitHub in the external browser. The app makes no network call for this.

## Context

- `shared/build.gradle.kts` already generates `BuildSecrets.kt` into `build/generated/buildSecrets/kotlin`, a `commonMain` source dir, before every compile and ktlint task. Android, desktop and iOS (Xcode runs `:shared:embedAndSignAppleFrameworkForXcode`) all compile it.
- The configuration cache and build cache are on (`gradle.properties`).
- CI checks out with `actions/checkout@v7`, default depth 1.
- About (`AboutScreen.kt`) is a `LazyColumn` of `LinkRow` cards (blue title, muted subtitle, chevron) that open a URL through `LocalUriHandler`.
- Sub-screens are pushed with `ScreenPushOverlay` (back and iOS edge-swipe via `OverlayBackEffects`), as Settings does for About.

## Non-goals

- Any network call (GitHub API, Atom feed, Tor).
- Dates, authors or hashes on screen.
- Showing the app version.
- Filtering by message prefix (`--no-merges` is the only filter).

## Decisions

| Topic | Choice |
| --- | --- |
| Source | `git log -z --no-merges -20 --format=%H%n%s <rev>`, run in the repo root. `<rev>` is the `GIT_COMMIT` env var when set, else `HEAD`. `-z` separates commits with NUL, which a commit message cannot contain; within a record the first line is the sha and the second is the subject (bodies are not baked in). |
| Gradle | A `ValueSource` runs git through `ExecOperations` and returns `""` on any failure (no git, no repo, unknown rev, shallow history ends early just gives fewer commits). The task takes it as an input property, so a new commit or a changed `GIT_COMMIT` env var regenerates the file and changes the build-cache key; nothing runs git at configuration time. |
| Generated file | The existing generator task (renamed `generateBuildSources`) writes `BuildInfo.kt` next to `BuildSecrets.kt`: `internal object BuildInfo { val GIT_LOG = "<raw git output>" }` (no separate commit constant: nothing reads it). One shared `kotlinString()` escapes every char outside printable ASCII, plus `"`, `\` and `$`, as `\uXXXX`, so quotes, templates, newlines, NUL and any Unicode are safe. BuildSecrets uses the same helper. |
| Parsing | Pure Kotlin in `commonMain` (`Changelog.kt`): `parseGitLog(raw): List<ChangelogEntry(sha, title)>`. Blank records and records without a 40-hex sha are dropped; subjects are trimmed. Unit-tested. |
| CI | `fetch-depth: 50` on checkout in `build.yml`, `release.yml`, `play-internal.yml` and `testflight.yml` (all ship artifacts). `submodules: recursive` stays. `test.yml` is unchanged. |
| About row | First item: title "Changelog", subtitle "Recent changes in this build", chevron, same card as the other rows. The card is shared: `LinkRow(title, subtitle, onClick)` is the base; the URL variant calls it with `uriHandler.openUri`. |
| Screen | `ChangelogScreen(onBack)` with `ScreenHeader("Changelog")` (About's scaffold, extracted as `CardListScreen`), pushed from About with a `showChangelog` flag and `ScreenPushOverlay`. One card per commit showing only the subject as the blue title, no subtitle *(changed 2026-10-21: titles only; only subjects are baked in)*. Tapping opens `https://github.com/BlueWallet/Blueberry.kmp/commit/<sha>` via `LocalUriHandler` (the external browser on Android, iOS and desktop). |
| Empty | An empty list shows "Changelog isn't available for this build" (local builds without git, or git failure). |

## Testing (TDD)

- `parseGitLog`: two records give two entries in order with their subjects; empty input gives an empty list; blank and malformed records are dropped.
- `changelogCommitUrl(sha)` builds the GitHub commit URL.
- Generator: verified by compiling a throwaway clone whose commit message contains `"`, `$name`, `${x}`, `\`, a tab, Cyrillic and an emoji and by checking the file regenerates after a new commit there (configuration cache on).
- Android emulator: About shows Changelog first; the screen lists commits and scrolls; a tap fires an external VIEW intent for the commit URL; back returns to About.
