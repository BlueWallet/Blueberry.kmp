package io.bluewallet.blueberry

import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwType

/** The build's latest non-merge commits, baked in at build time; each opens on GitHub. */
@Composable
internal fun ChangelogScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val commits = remember { parseGitLog(BuildInfo.GIT_LOG) }
    CardListScreen(title = "Changelog", onBack = onBack) {
        if (commits.isEmpty()) {
            item { Text("Changelog isn't available for this build", color = BwColors.InkMuted, fontFamily = BwFontFamily, fontSize = BwType.BodySize) }
        }
        items(commits, key = { it.sha }) { entry ->
            LinkRow(entry.title, "", onClick = { runCatching { uriHandler.openUri(changelogCommitUrl(entry.sha)) } })
        }
    }
}
