package io.bluewallet.blueberry.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager

/** Taps that no child handles clear focus, which drops the keyboard. */
@Composable
fun Modifier.dismissKeyboardOnTap(): Modifier {
    val focus = LocalFocusManager.current
    return pointerInput(focus) { detectTapGestures { focus.clearFocus() } }
}
