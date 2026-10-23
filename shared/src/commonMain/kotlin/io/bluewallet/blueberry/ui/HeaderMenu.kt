package io.bluewallet.blueberry.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

class HeaderMenuItem(
    val title: String,
    val onClick: () -> Unit,
)

/** Material "⋯" overflow button for [MaterialScreenHeader]. iOS uses a native pull-down menu. */
@Composable
internal fun HeaderOverflowMenu(menu: List<HeaderMenuItem>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "More" }) {
            Text("⋯", color = BwColors.Action, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (item in menu) {
                DropdownMenuItem(
                    text = { Text(item.title) },
                    onClick = {
                        open = false
                        item.onClick()
                    },
                )
            }
        }
    }
}
