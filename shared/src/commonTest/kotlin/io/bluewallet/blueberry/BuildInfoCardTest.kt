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
