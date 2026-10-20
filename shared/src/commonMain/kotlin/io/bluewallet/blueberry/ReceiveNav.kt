package io.bluewallet.blueberry

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.parse.ReceiveChain
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.NavArrow
import io.bluewallet.blueberry.ui.NavArrowDirection

/** Stands in for the derivation path while the address is covered, like the dotted address. */
internal const val PLACEHOLDER_PATH = "•••• •••• ••••"

/** The path caption, or its placeholder while the address itself is hidden behind the label prompt. */
fun receivePathCaption(
    path: String,
    hidden: Boolean,
): String = if (hidden) PLACEHOLDER_PATH else path

fun canStepReceive(
    index: Int,
    delta: Int,
    lastIndex: Int,
): Boolean = index + delta in 0..lastIndex

internal fun receiveNav(
    chain: ReceiveChain?,
    index: Int,
    onIndex: (Int) -> Unit,
): ReceiveNav? =
    chain?.let {
        ReceiveNav(
            path = it.addresses[index].path,
            index = index,
            lastIndex = it.lastIndex,
            used = index in it.used,
            onIndex = onIndex,
        )
    }

/** Position on the receive chain and how to move along it; null for wallets without one. */
internal class ReceiveNav(
    val path: String,
    private val index: Int,
    private val lastIndex: Int,
    val used: Boolean,
    private val onIndex: (Int) -> Unit,
) {
    val canBack: Boolean get() = canStepReceive(index, -1, lastIndex)
    val canForward: Boolean get() = canStepReceive(index, 1, lastIndex)

    /**
     * Moves to the neighbour of the address this nav was built for. Absolute, so taps or key repeats
     * landing before the next frame repeat the same move instead of running past the bound.
     */
    fun step(delta: Int): Boolean = canStepReceive(index, delta, lastIndex).also { if (it) onIndex(index + delta) }

    /** Steps for the Left/Right arrow keys; false (unhandled) for other keys or a disabled direction. */
    fun onArrowKey(key: Key): Boolean =
        when (key) {
            Key.DirectionLeft -> step(-1)
            Key.DirectionRight -> step(1)
            else -> false
        }
}

/** Left/Right arrow keys step [nav] while this node (the Receive root) holds focus. */
internal fun Modifier.receiveArrowKeys(
    nav: ReceiveNav?,
    focus: FocusRequester,
): Modifier =
    onKeyEvent { event -> event.type == KeyEventType.KeyDown && nav?.onArrowKey(event.key) == true }
        .focusRequester(focus)
        .focusable()

/** QR with the step arrows either side and the address's derivation path (or its placeholder when [hidden]) underneath. */
@Composable
internal fun ReceiveQrRow(
    nav: ReceiveNav?,
    hidden: Boolean,
    modifier: Modifier = Modifier,
    qr: @Composable () -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (nav != null) NavArrow(NavArrowDirection.Left, "Previous address", onClick = { nav.step(-1) }, enabled = nav.canBack)
        qr()
        if (nav != null) NavArrow(NavArrowDirection.Right, "Next address", onClick = { nav.step(1) }, enabled = nav.canForward)
    }
    if (nav != null) {
        Text(
            text = receivePathCaption(nav.path, hidden),
            color = if (hidden) BwColors.Ink else BwColors.InkMuted,
            fontFamily = BwFontFamily,
            fontSize = BwType.CaptionSize,
            fontWeight = BwType.Caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(if (hidden) PLACEHOLDER_ALPHA else 1f),
        )
    }
}
