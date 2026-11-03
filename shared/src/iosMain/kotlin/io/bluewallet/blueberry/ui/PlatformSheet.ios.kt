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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIAdaptivePresentationControllerDelegateProtocol
import platform.UIKit.UIModalPresentationPageSheet
import platform.UIKit.UIPresentationController
import platform.UIKit.UISheetPresentationControllerDetent
import platform.UIKit.UISheetPresentationControllerDetentIdentifierLarge
import platform.UIKit.UISheetPresentationControllerDetentIdentifierMedium
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIViewController
import platform.UIKit.presentationController
import platform.UIKit.sheetPresentationController
import platform.darwin.NSObject

/**
 * Native page sheet: system grabber, opens at the medium detent, swipe down to dismiss.
 * Compose scrolling is invisible to UIKit, so an upward scroll at medium asks the sheet to
 * animate to large; content starts scrolling once it is there.
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
                        latestContent.value(close)
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

/** While the sheet sits at medium, an upward user scroll grows it instead of scrolling the content. */
private class ExpandOnScroll(
    private val session: SheetSession,
) : NestedScrollConnection {
    override fun onPreScroll(
        available: Offset,
        source: NestedScrollSource,
    ): Offset =
        if (source == NestedScrollSource.UserInput && available.y < 0f && session.expandFromMedium()) {
            Offset(0f, available.y)
        } else {
            Offset.Zero
        }
}

/** Tracks one presentation so dismissal reports exactly once, however it happens. */
private class SheetSession(
    private val onDismissed: () -> Unit,
) : NSObject(),
    UIAdaptivePresentationControllerDelegateProtocol {
    var controller: UIViewController? = null
    private var finished = false

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

    /** Animates medium to large. False when the sheet is already large (or gone). */
    fun expandFromMedium(): Boolean {
        val sheet =
            controller
                ?.sheetPresentationController
                ?.takeIf { it.selectedDetentIdentifier == UISheetPresentationControllerDetentIdentifierMedium }
                ?: return false
        sheet.animateChanges { sheet.selectedDetentIdentifier = UISheetPresentationControllerDetentIdentifierLarge }
        return true
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
 * iOS 26 sheet bar: leading glass close, centered title, trailing glass action. A direct button,
 * not a pull-down menu: a menu presented over a medium sheet expands it to large.
 */
@Composable
actual fun PlatformSheetHeader(
    title: String,
    onClose: () -> Unit,
    action: SheetHeaderAction?,
    modifier: Modifier,
) {
    Box(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp).height(44.dp)) {
        IosBarTitle(title = title, modifier = Modifier.align(Alignment.Center).fillMaxSize().padding(horizontal = 64.dp))
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
