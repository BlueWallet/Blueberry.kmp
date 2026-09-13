package io.bluewallet.blueberry.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import blueberry.shared.generated.resources.Res
import blueberry.shared.generated.resources.bluewallet_logo
import org.jetbrains.compose.resources.painterResource

/** Tiny sister-project mark, cut from the dashboard design. */
@Composable
fun BwWordmark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Res.drawable.bluewallet_logo),
        contentDescription = "bluewallet",
        modifier = modifier.height(28.dp),
        contentScale = ContentScale.FillHeight,
    )
}
