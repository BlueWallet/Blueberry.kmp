package io.bluewallet.blueberry

/** One commit of the build's changelog: its full [sha] and subject line. */
internal data class ChangelogEntry(
    val sha: String,
    val title: String,
)

private val SHA = Regex("[0-9a-f]{40,64}")

/** Parses `git log -z --format=%H%n%s`: NUL-separated records of a sha line and a subject line. */
internal fun parseGitLog(raw: String): List<ChangelogEntry> =
    raw.split('\u0000').mapNotNull { record ->
        val lines = record.trim()
        val sha = lines.substringBefore('\n')
        if (SHA.matches(sha)) ChangelogEntry(sha, lines.substringAfter('\n', "").trim()) else null
    }

internal fun changelogCommitUrl(sha: String): String = "$ABOUT_GITHUB_URL/commit/$sha"
