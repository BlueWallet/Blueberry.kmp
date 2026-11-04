package io.bluewallet.blueberry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.isActive
import platform.UIKit.UIModalPresentationPageSheet
import platform.UIKit.UIPresentationController
import platform.UIKit.UISheetPresentationController
import platform.UIKit.UISheetPresentationControllerDelegateProtocol
import platform.UIKit.UISheetPresentationControllerDetent
import platform.UIKit.UISheetPresentationControllerDetentIdentifierLarge
import platform.UIKit.UISheetPresentationControllerDetentIdentifierMedium
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIViewController
import platform.UIKit.presentationController
import platform.UIKit.sheetPresentationController
import platform.darwin.NSObject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Native page sheet: system grabber, opens at the medium detent, swipe down to dismiss.
 * Compose scrolling is invisible to UIKit, so an upward drag below full height grows the sheet.
 * The body stays pinned to the top until the sheet has finished arriving at the large detent.
 */
@Composable
actual fun PlatformModalSheet(
    onDismiss: () -> Unit,
    background: Color,
    dark: Boolean,
    content: @Composable (close: SheetClose) -> Unit,
) {
    val host = LocalUIViewController.current
    val latestDismiss = rememberUpdatedState(onDismiss)
    val latestContent = rememberUpdatedState(content)
    val latestDark = rememberUpdatedState(dark)
    val latestBackground = rememberUpdatedState(background)
    val session = remember(host) { SheetSession { latestDismiss.value() } }
    SideEffect { session.style(dark, background) }
    DisposableEffect(host) {
        val expandOnScroll = ExpandOnScroll(session)
        val close: SheetClose = session::close
        val controller =
            ComposeUIViewController {
                BwPaletteScope(latestDark.value) {
                    ExpandWhenKeyboardShows(session)
                    Box(Modifier.fillMaxSize().background(latestBackground.value).nestedScroll(expandOnScroll)) {
                        SheetScrollGate(session) { latestContent.value(close) }
                    }
                }
            }
        session.controller = controller
        controller.modalPresentationStyle = UIModalPresentationPageSheet
        session.style(dark, background)
        controller.sheetPresentationController?.let { sheet ->
            sheet.detents = listOf(UISheetPresentationControllerDetent.mediumDetent(), UISheetPresentationControllerDetent.largeDetent())
            sheet.selectedDetentIdentifier = UISheetPresentationControllerDetentIdentifierMedium
            sheet.prefersGrabberVisible = true
        }
        controller.presentationController?.delegate = session
        topmost(host).presentViewController(controller, animated = true, completion = null)
        onDispose { session.release() }
    }
}

/** Publishes [LocalSheetScrollEnabled] once the sheet is resting at full height. */
@Composable
private fun SheetScrollGate(
    session: SheetSession,
    content: @Composable () -> Unit,
) {
    var enabled by remember { mutableStateOf(false) }
    LaunchedEffect(session) {
        while (isActive) {
            withFrameNanos {
                val next = session.contentMayScroll()
                if (enabled != next) enabled = next
            }
        }
    }
    CompositionLocalProvider(LocalSheetScrollEnabled provides enabled, content = content)
}

/** Grows a medium sheet when the keyboard appears so the focused field is not covered. */
@Composable
private fun ExpandWhenKeyboardShows(session: SheetSession) {
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val keyboardUp by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    LaunchedEffect(keyboardUp) { if (keyboardUp) session.expandFromMedium() }
}

private fun topmost(root: UIViewController): UIViewController {
    var top = root
    while (true) {
        val next = top.presentedViewController ?: return top
        if (next.isBeingDismissed()) return top
        top = next
    }
}

/**
 * Below full height, vertical drags grow the sheet and never move the body.
 * Scrolling starts only once the large detent has finished settling.
 */
private class ExpandOnScroll(
    private val session: SheetSession,
) : NestedScrollConnection {
    override fun onPreScroll(
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (source != NestedScrollSource.UserInput || session.contentMayScroll()) return Offset.Zero
        if (available.y < 0f) session.expandFromMedium()
        return Offset(x = 0f, y = available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity = if (session.contentMayScroll()) Velocity.Zero else available
}

private val sheetExpandSettle = 450.milliseconds

/** Tracks one presentation so dismissal reports exactly once, however it happens. */
private class SheetSession(
    private val onDismissed: () -> Unit,
) : NSObject(),
    UISheetPresentationControllerDelegateProtocol {
    var controller: UIViewController? = null
    private var finished = false
    private var scrollHeldAt = TimeSource.Monotonic.markNow()

    /** Keeps the system chrome (grabber, glass buttons) and the backing view on the app theme. */
    fun style(
        dark: Boolean,
        background: Color,
    ) {
        val presented = controller ?: return
        presented.overrideUserInterfaceStyle =
            if (dark) UIUserInterfaceStyle.UIUserInterfaceStyleDark else UIUserInterfaceStyle.UIUserInterfaceStyleLight
        presented.view.backgroundColor = background.toUiColor()
    }

    /** True once the sheet is resting on the large detent, so the body may leave the top. */
    fun contentMayScroll(): Boolean {
        val sheet = controller?.sheetPresentationController
        val atLarge = sheet?.selectedDetentIdentifier == UISheetPresentationControllerDetentIdentifierLarge
        return scrollHeldAt.elapsedNow() >= sheetExpandSettle && atLarge
    }

    /** Animates medium to large. The body stays pinned until that animation settles. */
    fun expandFromMedium() {
        val sheet =
            controller
                ?.sheetPresentationController
                ?.takeIf { it.selectedDetentIdentifier == UISheetPresentationControllerDetentIdentifierMedium }
                ?: return
        sheet.animateChanges { sheet.selectedDetentIdentifier = UISheetPresentationControllerDetentIdentifierLarge }
        holdContentScroll()
    }

    override fun sheetPresentationControllerDidChangeSelectedDetentIdentifier(
        sheetPresentationController: UISheetPresentationController,
    ) {
        if (sheetPresentationController.selectedDetentIdentifier == UISheetPresentationControllerDetentIdentifierLarge) {
            holdContentScroll()
        }
    }

    private fun holdContentScroll() {
        scrollHeldAt = TimeSource.Monotonic.markNow()
    }

    fun close(after: () -> Unit) {
        val presented = controller ?: return
        if (finished) return
        finished = true
        presented.dismissViewControllerAnimated(true) {
            onDismissed()
            after()
        }
    }

    /** The host left composition (screen popped): take the sheet down without reporting. */
    fun release() {
        if (finished) return
        finished = true
        controller?.dismissViewControllerAnimated(true, completion = null)
    }

    override fun presentationControllerDidDismiss(presentationController: UIPresentationController) {
        if (finished) return
        finished = true
        onDismissed()
    }
}

/**
 * iOS 26 sheet bar: leading glass close, optional centered title, trailing glass action.
 * An empty [title] draws no title. A direct button, not a pull-down menu: a menu presented
 * over a medium sheet expands it to large.
 */
@Composable
actual fun PlatformSheetHeader(
    title: String,
    onClose: () -> Unit,
    action: SheetHeaderAction?,
    modifier: Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp)
            .padding(vertical = 8.dp)
            .height(44.dp),
    ) {
        if (title.isNotEmpty()) {
            IosBarTitle(
                title = title,
                modifier = Modifier.align(Alignment.Center).fillMaxSize().padding(horizontal = 64.dp),
            )
        }
        IosGlassCloseButton(onClose = onClose, enabled = true, modifier = Modifier.align(Alignment.CenterStart))
        if (action != null) {
            val end = Modifier.align(Alignment.CenterEnd)
            if (rememberSupportsIosLiquidGlass()) {
                IosGlassSymbolButton(action.iosSymbol, action.accessibilityLabel, action.onClick, end)
            } else {
                IosOverlayTextAction(text = action.text, onClick = action.onClick, modifier = end)
            }
        }
    }
}
