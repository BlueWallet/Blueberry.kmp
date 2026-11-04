package io.bluewallet.blueberry.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Animates the sheet away, then runs the given action. */
typealias SheetClose = (after: () -> Unit) -> Unit

/**
 * False while a sheet is short of full height. Body scroll stays pinned to the top until then,
 * so a drag grows the sheet instead of moving the content.
 */
val LocalSheetScrollEnabled = staticCompositionLocalOf { true }

/**
 * The platform's own modal sheet while composed: a UIKit page sheet on iOS, Material
 * `ModalBottomSheet` elsewhere. [onDismiss] runs once the user dismisses it (swipe, scrim, back)
 * or after [SheetClose] finishes. Content gets the [dark] palette whatever the app theme is.
 */
@Composable
expect fun PlatformModalSheet(
    onDismiss: () -> Unit,
    background: Color,
    dark: Boolean,
    content: @Composable (close: SheetClose) -> Unit,
)

/** One trailing header action: [text] where a text action is drawn, [iosSymbol] on the iOS 26 glass button. */
class SheetHeaderAction(
    val text: String,
    val accessibilityLabel: String,
    val iosSymbol: String,
    val onClick: () -> Unit,
)

/**
 * Sheet title bar. An empty [title] draws no title. iOS: leading close, optional centered
 * title, trailing action button. Material: title row under the drag handle with a trailing
 * text action; the handle dismisses. An empty title and no action draws nothing.
 */
@Composable
expect fun PlatformSheetHeader(
    title: String,
    onClose: () -> Unit,
    action: SheetHeaderAction?,
    modifier: Modifier = Modifier,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MaterialModalSheet(
    onDismiss: () -> Unit,
    background: Color,
    dark: Boolean,
    content: @Composable (close: SheetClose) -> Unit,
) {
    val state = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val latestDismiss by rememberUpdatedState(onDismiss)
    val closing = remember { booleanArrayOf(false) }
    val close: SheetClose = { after ->
        if (!closing[0]) {
            closing[0] = true
            scope.launch { state.hide() }.invokeOnCompletion {
                latestDismiss()
                after()
            }
        }
    }
    BwPaletteScope(dark) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = state,
            containerColor = background,
        ) {
            content(close)
        }
    }
}

@Composable
internal fun MaterialSheetHeader(
    title: String,
    action: SheetHeaderAction?,
    modifier: Modifier = Modifier,
) {
    if (title.isEmpty() && action == null) return
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 24.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (title.isNotEmpty()) {
            Text(
                text = title,
                modifier = Modifier.weight(1f).semantics { heading() },
                color = BwColors.Ink,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (action != null) {
            TextAction(
                text = action.text,
                onClick = action.onClick,
                modifier = Modifier.semantics { contentDescription = action.accessibilityLabel },
            )
        }
    }
}
