package io.bluewallet.blueberry

import androidx.compose.ui.uikit.ComposeUIViewControllerDelegate
import androidx.compose.ui.window.ComposeUIViewController
import io.bluewallet.blueberry.boot.blueberrySqlitePath
import io.bluewallet.blueberry.ui.iosPreferredStatusBarStyle
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIStatusBarStyle

fun MainViewController() =
    iosDatabasePath().let { databasePath ->
        ComposeUIViewController(
            configure = {
                delegate =
                    object : ComposeUIViewControllerDelegate {
                        override val preferredStatusBarStyle: UIStatusBarStyle?
                            get() = iosPreferredStatusBarStyle()
                    }
            },
        ) {
            App(databasePath = databasePath)
        }
    }

@OptIn(ExperimentalForeignApi::class)
private fun iosDatabasePath(): String {
    val url =
        NSFileManager.defaultManager.URLForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )
    val directory = requireNotNull(url?.path) { "Application Support directory is missing" }
    return blueberrySqlitePath(directory)
}
