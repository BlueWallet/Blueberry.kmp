package io.bluewallet.blueberry

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluewallet.blueberry.ui.Pressable
import kotlinx.coroutines.delay

private const val COPIED_VISIBLE_MS = 2_000L
private const val COPIED_VEIL_ALPHA = 0.36f

/**
 * Tapping [content] copies it. A soft green veil covers that area for two seconds,
 * with a white pill in the middle: a solid green check and "Copied".
 */
@Composable
internal fun CopyableArea(
    label: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var copies by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copies) {
        if (copies > 0) {
            copied = true
            delay(COPIED_VISIBLE_MS)
            copied = false
        }
    }
    Box(modifier) {
        Pressable(
            onClick = {
                onCopy()
                copies++
            },
            role = Role.Button,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        ) {
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { content() }
        }
        AnimatedVisibility(
            visible = copied,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(220)),
            modifier = Modifier.matchParentSize(),
        ) {
            CopiedVeil()
        }
    }
}

@Composable
private fun CopiedVeil() {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .background(SheetPalette.green.copy(alpha = COPIED_VEIL_ALPHA), shape)
            .semantics { contentDescription = "Copied" },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SheetGlyphView(SheetGlyph.Check, SheetPalette.green, Modifier.size(16.dp))
            SheetText("Copied", 15.sp, SheetPalette.green, FontWeight.Bold)
        }
    }
}
