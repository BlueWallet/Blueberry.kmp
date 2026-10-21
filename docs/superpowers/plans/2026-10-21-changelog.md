# Changelog in About Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Changelog row first in About opens a screen of the build's latest 20 non-merge commits, baked in at build time.

**Architecture:** `shared/build.gradle.kts` generates `BuildInfo.kt` (raw `git log -z --format=%H%n%s` output) next to `BuildSecrets.kt`; pure Kotlin parses it; Compose screens show it. Spec: `docs/superpowers/specs/2026-10-21-changelog-design.md`.

**Tech Stack:** Gradle Kotlin DSL (`ValueSource`, `ExecOperations`), Kotlin Multiplatform, Compose Multiplatform, `kotlin.test`.

## Global Constraints

- No network calls. No new dependencies.
- Configuration-cache and build-cache correct; never fail the build when git is missing.
- Do not commit (user instruction for this branch).

## Files

- Modify `shared/build.gradle.kts`: `GitLogSource` ValueSource, shared `kotlinString()` escaper, generator task writes `BuildSecrets.kt` and `BuildInfo.kt`.
- Create `shared/src/commonMain/kotlin/io/bluewallet/blueberry/Changelog.kt`: `ChangelogEntry(sha, title)`, `parseGitLog`, `changelogCommitUrl`.
- Create `shared/src/commonTest/kotlin/io/bluewallet/blueberry/ChangelogTest.kt`.
- Create `shared/src/commonMain/kotlin/io/bluewallet/blueberry/ChangelogScreen.kt`.
- Modify `shared/src/commonMain/kotlin/io/bluewallet/blueberry/AboutScreen.kt`: Changelog row first, `showChangelog` overlay, `LinkRow` onClick base (made `internal` for reuse).
- Modify `.github/workflows/{build,release,play-internal,testflight}.yml`: `fetch-depth: 50`.

## Task 1: Parse git log (TDD)

- [ ] Write `ChangelogTest` (two records in order with subjects; empty input → empty; blank / malformed records dropped; commit URL).
- [ ] Run `./gradlew :shared:jvmTest --tests '*ChangelogTest*'`; expect compile failure (missing `parseGitLog`).
- [ ] Implement `Changelog.kt` minimally; rerun; expect PASS.

## Task 2: Generate BuildInfo.kt

- [ ] Add `GitLogSource` + `kotlinString()`; rename the task to `generateBuildSources`; write `BuildInfo.kt` (`GIT_LOG`); switch BuildSecrets escaping to `kotlinString()`.
- [ ] Run `:shared:generateBuildSources`; inspect the file: 20 shas equal `git log --no-merges -20 --format=%H`.
- [ ] Throwaway clone: commit a message with `" $name ${x} \ tab Кирилица 🚀`, build `:shared:compileKotlinJvm`, check the newest entry; commit again, rerun, check it regenerated with configuration cache reused/invalidated correctly. Run once with `PATH` lacking git and once in a copy without `.git` → empty `GIT_LOG`, build passes.

## Task 3: UI

- [ ] `LinkRow(title, subtitle, onClick)` base + URL overload; Changelog row first in About; `showChangelog` + `ScreenPushOverlay(ChangelogScreen)`.
- [ ] `ChangelogScreen` (`remember { parseGitLog(BuildInfo.GIT_LOG) }`): `CardListScreen("Changelog")`, LazyColumn of rows (subject as title, no subtitle), tap → `changelogCommitUrl`; empty text "Changelog isn't available for this build".

## Task 4: CI

- [ ] `fetch-depth: 50` on every checkout in build, release, play-internal, testflight.

## Task 5: Verify and review

- [ ] Full gate command (see AGENTS.md / task); all green.
- [ ] Self-review for bloat, duplication, over-engineering; fix.
- [ ] One Limrun emulator run (About, Changelog, scrolled, tap → VIEW intent, back).
