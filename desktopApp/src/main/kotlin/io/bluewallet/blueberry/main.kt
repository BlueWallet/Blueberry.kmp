package io.bluewallet.blueberry

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.bluewallet.blueberry.boot.blueberrySqlitePath
import java.io.File

fun main() = application {
    val dir = File("blueberry.data")
    dir.mkdirs()
    val path = blueberrySqlitePath(dir.absolutePath)
    Window(
        onCloseRequest = ::exitApplication,
        title = "Blueberry",
        state = rememberWindowState(size = DpSize(1100.dp, 780.dp)),
    ) {
        App(databasePath = path)
    }
}