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
    val title = "Version ${version.trim().ifEmpty { "dev" }}"
    val commit = sha.trim()
    return if (FULL_SHA.matches(commit)) {
        BuildInfoCard(title, "Built from ${commit.take(7)}", changelogCommitUrl(commit))
    } else {
        BuildInfoCard(title, "Commit unknown", null)
    }
}
