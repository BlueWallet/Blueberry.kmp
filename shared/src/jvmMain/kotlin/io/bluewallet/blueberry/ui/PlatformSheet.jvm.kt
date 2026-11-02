package io.bluewallet.blueberry.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
actual fun PlatformModalSheet(
    onDismiss: () -> Unit,
    background: Color,
    dark: Boolean,
    content: @Composable (close: SheetClose) -> Unit,
) = MaterialModalSheet(onDismiss, background, dark, content)

@Suppress("UnusedParameter")
@Composable
actual fun PlatformSheetHeader(
    title: String,
    onClose: () -> Unit,
    action: SheetHeaderAction?,
    modifier: Modifier,
) = MaterialSheetHeader(title, action, modifier)
