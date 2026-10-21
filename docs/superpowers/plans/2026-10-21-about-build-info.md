# Build Info Card Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show "Version <version>" / "Built from <short sha>" as the first About card, opening the commit on GitHub.

**Architecture:** `generateBuildSources` bakes `VERSION` and `GIT_SHA` into the generated `BuildInfo` object. A pure `buildInfoCard()` in commonMain turns them into title, subtitle and an optional URL; `AboutScreen` renders it with `LinkRow`, whose `onClick` becomes nullable.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Gradle (Kotlin DSL, configuration cache), GitHub Actions.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-10-21-about-build-info-design.md`.
- Never `git commit` (user rule); leave changes in the working tree. Commit steps are replaced by "leave uncommitted".
- Version fallback is the literal `dev`; unknown commit subtitle is `Commit unknown`; short sha is 7 chars.
- Gate: `JAVA_HOME=/home/box/jdk/current ANDROID_HOME=/home/box/android-sdk ./gradlew --max-workers=2 ktlintCheck detekt :wallet:jvmTest :parse:jvmTest :labels:jvmTest :storage:jvmTest :shared:jvmTest :androidApp:assembleDebug :desktopApp:compileKotlin :shared:compileIosMainKotlinMetadata :shared:compileTestKotlinIosSimulatorArm64` (with `ANDROID_SDK_ROOT` unset).

---

### Task 1: Card logic

**Files:**
- Create: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/BuildInfoCard.kt`
- Test: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/BuildInfoCardTest.kt`

**Interfaces:**
- Consumes: `changelogCommitUrl(sha: String): String` (Changelog.kt).
- Produces: `internal data class BuildInfoCard(val title: String, val subtitle: String, val url: String?)` and `internal fun buildInfoCard(version: String, sha: String): BuildInfoCard`.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals

class BuildInfoCardTest {
    private val sha = "10f154f0b6a7e3d2c1b0a9f8e7d6c5b4a3928170"

    @Test
    fun shows_version_and_short_sha_and_links_the_commit() {
        assertEquals(
            BuildInfoCard("Version 2026.10.21", "Built from 10f154f", "https://github.com/BlueWallet/Blueberry.kmp/commit/$sha"),
            buildInfoCard("2026.10.21", sha),
        )
    }

    @Test
    fun blank_version_reads_dev() {
        assertEquals("Version dev", buildInfoCard("  ", sha).title)
    }

    @Test
    fun trims_baked_values() {
        val card = buildInfoCard(" 2026.10.21\n", "$sha\n")
        assertEquals("Version 2026.10.21", card.title)
        assertEquals(changelogCommitUrl(sha), card.url)
    }

    @Test
    fun empty_sha_is_unknown_and_not_tappable() {
        assertEquals(BuildInfoCard("Version dev", "Commit unknown", null), buildInfoCard("dev", ""))
    }

    @Test
    fun non_sha_is_unknown_and_not_tappable() {
        assertEquals(BuildInfoCard("Version dev", "Commit unknown", null), buildInfoCard("dev", "fatal: bad revision"))
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew --max-workers=2 :shared:jvmTest --tests '*BuildInfoCardTest*'`
Expected: compilation FAIL, `Unresolved reference 'buildInfoCard'`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package io.bluewallet.blueberry

/** The About build card: [url] is null when the commit is unknown, which makes the card inert. */
internal data class BuildInfoCard(
    val title: String,
    val subtitle: String,
    val url: String?,
)

private val FULL_SHA = Regex("[0-9a-f]{40,64}")

/** Text and link for the build [version] and the full commit [sha] it was built from, as baked into BuildInfo. */
internal fun buildInfoCard(
    version: String,
    sha: String,
): BuildInfoCard {
    val name = version.trim().ifEmpty { "dev" }
    val commit = sha.trim()
    return if (FULL_SHA.matches(commit)) {
        BuildInfoCard("Version $name", "Built from ${commit.take(7)}", changelogCommitUrl(commit))
    } else {
        BuildInfoCard("Version $name", "Commit unknown", null)
    }
}
```

- [ ] **Step 4: Run the tests and make sure they pass** (same command; expected PASS, 5 tests).
- [ ] **Step 5: Leave uncommitted.**

### Task 2: Bake VERSION and GIT_SHA

**Files:**
- Modify: `shared/build.gradle.kts` (`generateBuildSources`)

**Interfaces:**
- Produces: `BuildInfo.VERSION: String`, `BuildInfo.GIT_SHA: String` (full sha or `""`).

- [ ] **Step 1:** Inside `tasks.register("generateBuildSources")`, next to `gitLog`, add:

```kotlin
        // The exact commit built, merge commits included; "" when git fails.
        val gitSha =
            providers.of(GitOutput::class) {
                parameters.repoDir.set(rootProject.layout.projectDirectory)
                parameters.args.addAll("rev-parse", "--verify")
                parameters.args.add(gitRev.map { "$it^{commit}" })
            }
        // -PversionName (Android/desktop CI), else Xcode's MARKETING_VERSION, else "dev".
        val buildVersion =
            providers
                .gradleProperty("versionName")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .orElse(providers.environmentVariable("MARKETING_VERSION").map { it.trim() }.filter { it.isNotEmpty() })
                .orElse("dev")
        inputs.property("gitSha", gitSha)
        inputs.property("buildVersion", buildVersion)
```

- [ ] **Step 2:** In the `BuildInfo.kt` template add:

```kotlin
                    val VERSION = ${kotlinString(buildVersion.get())}
                    val GIT_SHA = ${kotlinString(gitSha.get().trim())}
```

- [ ] **Step 3:** Verify generated file:
  - `./gradlew :shared:generateBuildSources -PversionName=2026.10.21` then read `shared/build/generated/buildSecrets/kotlin/io/bluewallet/blueberry/BuildInfo.kt`: `VERSION = "2026.10.21"`, `GIT_SHA` = `git rev-parse HEAD`.
  - Without `-P`: `VERSION = "dev"`.
  - `GIT_COMMIT=<merge sha from git log --merges -1> ./gradlew :shared:generateBuildSources`: `GIT_SHA` equals that sha.
- [ ] **Step 4: Leave uncommitted.**

### Task 3: Render the card

**Files:**
- Modify: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/AboutScreen.kt`

**Interfaces:**
- Consumes: `buildInfoCard`, `BuildInfo.VERSION`, `BuildInfo.GIT_SHA`.

- [ ] **Step 1:** Make `LinkRow`'s `onClick: (() -> Unit)?`; apply `.clickable(role = Role.Button, onClick = onClick)` only when non-null (`.then(if (onClick != null) Modifier.clickable(...) else Modifier)`) and render `Chevron()` only when non-null. Update its KDoc.
- [ ] **Step 2:** In `AboutScreen`, before the Changelog item:

```kotlin
    val uriHandler = LocalUriHandler.current
    val build = remember { buildInfoCard(BuildInfo.VERSION, BuildInfo.GIT_SHA) }
    ...
            item {
                LinkRow(build.title, build.subtitle, onClick = build.url?.let { url -> { runCatching { uriHandler.openUri(url) } } })
            }
```

- [ ] **Step 3:** Run the gate. Expected BUILD SUCCESSFUL.
- [ ] **Step 4: Leave uncommitted.**

### Task 4: Desktop release version

**Files:**
- Modify: `.github/workflows/release.yml` (desktop job env and Package step)

- [ ] **Step 1:** Add `VERSION_NAME: ${{ needs.version.outputs.version_name }}` under the desktop job `env:`.
- [ ] **Step 2:** Package step: `run: ./gradlew ${{ matrix.gradle }} --no-daemon -PpackageVersion="${{ env.PACKAGE_VERSION }}" -PversionName="${{ env.VERSION_NAME }}"`.
- [ ] **Step 3:** Confirm android/ios/play-internal/testflight already pass a version (see spec table).
- [ ] **Step 4: Leave uncommitted.**

### Task 5: Android check

- [ ] Build `:androidApp:assembleDebug -PversionName=2026.10.21`, run on one Limrun emulator, restore via Import, open Settings → About, screencap the top (UI dump checked first), tap the card and look for the VIEW intent, delete the emulator, confirm with `lim android list --all`. Screenshots to `/workspace/pr-shots/about-build-info/`.
