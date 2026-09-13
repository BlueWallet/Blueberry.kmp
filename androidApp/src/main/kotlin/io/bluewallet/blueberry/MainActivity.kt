package io.bluewallet.blueberry

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.bluewallet.blueberry.boot.blueberrySqlitePath

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val path = blueberrySqlitePath(filesDir.absolutePath)
        setContent {
            App(databasePath = path)
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App(databasePath = ":memory:")
}
