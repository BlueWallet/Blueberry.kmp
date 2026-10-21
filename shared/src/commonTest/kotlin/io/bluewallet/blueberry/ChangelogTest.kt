package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChangelogTest {
    private val shaA = "8a28cc8f2589d8e37a61c1cb956008591db5898d"
    private val shaB = "945b9200000000000000000000000000000000ab"

    @Test
    fun parses_records_in_order() {
        val raw = "$shaA\nFIX: ANR \"quoted\" \$x (#44)\u0000$shaB\nREF: home view style\u0000"
        assertEquals(
            listOf(
                ChangelogEntry(shaA, "FIX: ANR \"quoted\" \$x (#44)"),
                ChangelogEntry(shaB, "REF: home view style"),
            ),
            parseGitLog(raw),
        )
    }

    @Test
    fun empty_log_gives_empty_changelog() {
        assertTrue(parseGitLog("").isEmpty())
    }

    @Test
    fun drops_blank_and_malformed_records() {
        val raw = "\n\u0000not-a-sha\nmessage\u0000$shaA\nKeep me\n\u0000  \u0000"
        assertEquals(listOf(ChangelogEntry(sha = shaA, title = "Keep me")), parseGitLog(raw))
    }

    @Test
    fun commit_url_points_at_the_repo() {
        assertEquals("https://github.com/BlueWallet/Blueberry.kmp/commit/$shaA", changelogCommitUrl(shaA))
    }
}
