package io.bluewallet.blueberry

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.bluewallet.blueberry.peers.DEFAULT_BLOCKS_PER_PEER
import io.bluewallet.blueberry.peers.MAX_BLOCKS_PER_PEER
import io.bluewallet.blueberry.peers.loadBlocksPerPeer
import io.bluewallet.blueberry.peers.saveBlocksPerPeer
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.MetricCard
import io.bluewallet.blueberry.ui.PillButton

@Composable
internal fun BlocksPerPeerCard(db: Database) {
    var value by remember(db) { mutableStateOf(loadBlocksPerPeer(db)) }
    var expanded by remember { mutableStateOf(false) }
    MetricCard(
        label = "Blocks per peer",
        value = value.toString(),
        caption = "1 is most private",
        modifier = Modifier.fillMaxWidth(),
        trailing = {
            Box {
                PillButton(
                    text = "Change",
                    onClick = { expanded = true },
                    modifier = Modifier.semantics { contentDescription = "Change blocks per peer" },
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    containerColor = BwColors.Card,
                ) {
                    for (limit in DEFAULT_BLOCKS_PER_PEER..MAX_BLOCKS_PER_PEER) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = limit.toString(),
                                    color = BwColors.Ink,
                                    fontFamily = BwFontFamily,
                                )
                            },
                            onClick = {
                                saveBlocksPerPeer(db, limit)
                                value = loadBlocksPerPeer(db)
                                expanded = false
                            },
                        )
                    }
                }
            }
        },
    )
}
