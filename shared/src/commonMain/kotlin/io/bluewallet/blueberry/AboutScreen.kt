package io.bluewallet.blueberry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import io.bluewallet.blueberry.ui.AlwaysBounceScroll
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.Chevron
import io.bluewallet.blueberry.ui.ScreenHeader
import io.bluewallet.blueberry.ui.alwaysBounceTail

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenChangelog: () -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    val build = remember { buildInfoCard(BuildInfo.VERSION, BuildInfo.GIT_SHA) }
    CardListScreen(title = "About", onBack = onBack) {
        item {
            LinkRow(build.title, build.subtitle, onClick = build.url?.let { url -> { runCatching { uriHandler.openUri(url) } } })
        }
        item { LinkRow("Changelog", "Recent changes in this build", onClick = onOpenChangelog) }
        item { LinkRow("GitHub", ABOUT_GITHUB_URL.removePrefix("https://"), ABOUT_GITHUB_URL) }
        item { LinkRow("MIT License", "Blueberry is open source under the MIT License", ABOUT_LICENSE_URL) }
        item { SectionLabel("Open-source libraries") }
        items(aboutDependencies) { DependencyRow(it) }
        item {
            HorizontalDivider(
                color = BwColors.InkMuted.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = BwSpace.Gap),
            )
        }
        item { SectionLabel("Transitive dependencies") }
        items(transitiveDependencies) { DependencyRow(it) }
    }
}

/** A titled, bouncing list of cards on paper: the About and Changelog layout. */
@Composable
internal fun CardListScreen(
    title: String,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    AlwaysBounceScroll(
        Modifier
            .fillMaxSize()
            .background(BwColors.Paper)
            .safeDrawingPadding(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = BwSpace.ScreenX, vertical = BwSpace.ScreenY),
            verticalArrangement = Arrangement.spacedBy(BwSpace.Gap),
        ) {
            ScreenHeader(title = title, onBack = onBack)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(BwSpace.Gap),
            ) {
                content()
                alwaysBounceTail(listState)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = BwColors.InkSecondary,
        fontFamily = BwFontFamily,
        fontSize = BwType.LabelSize,
        fontWeight = BwType.Label,
        modifier = Modifier.padding(top = BwSpace.Gap),
    )
}

@Composable
private fun DependencyRow(dep: AppDependency) {
    LinkRow(dep.name, "${dep.version} · ${dep.license}", "https://github.com/${dep.name}")
}

@Composable
private fun LinkRow(
    title: String,
    subtitle: String,
    url: String,
) {
    val uriHandler = LocalUriHandler.current
    LinkRow(title, subtitle, onClick = { runCatching { uriHandler.openUri(url) } })
}

/** A card: blue [title], muted [subtitle] (hidden when empty) and, when [onClick] is set, tappable with a chevron. */
@Composable
internal fun LinkRow(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(BwSpace.Radius)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(BwColors.Card, shape)
                .border(BwSpace.Hairline, BwColors.Border, shape)
                // clickable merges the texts for screen readers; an inert card has to ask for it.
                .then(
                    if (onClick != null) {
                        Modifier.clickable(role = Role.Button, onClick = onClick)
                    } else {
                        Modifier.semantics(mergeDescendants = true) {}
                    },
                ).padding(BwSpace.Card),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BwSpace.Gap),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = BwColors.Link, fontFamily = BwFontFamily, fontSize = BwType.BodySize, fontWeight = BwType.Body)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, color = BwColors.InkMuted, fontFamily = BwFontFamily, fontSize = BwType.CaptionSize)
            }
        }
        if (onClick != null) Chevron()
    }
}
