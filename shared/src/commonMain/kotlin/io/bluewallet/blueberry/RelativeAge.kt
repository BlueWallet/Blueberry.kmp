package io.bluewallet.blueberry

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.parse.formatBlockTimeLabel
import io.bluewallet.blueberry.parse.formatRelativeAge
import io.bluewallet.blueberry.parse.msUntilNextBlockTimeLabelChange
import io.bluewallet.blueberry.parse.msUntilNextRelativeAgeChange
import kotlinx.coroutines.delay

/** Live tx-list block time; sleeps until the next string change, then recomposes once. */
@Composable
fun rememberBlockTimeLabel(unixSeconds: Long?): String? {
    if (unixSeconds == null) return null
    var nowMs by remember(unixSeconds) { mutableStateOf(nowMillis()) }
    val label = remember(unixSeconds, nowMs) { formatBlockTimeLabel(unixSeconds, nowMs) }
    LaunchedEffect(unixSeconds) {
        while (true) {
            val wait = msUntilNextBlockTimeLabelChange(unixSeconds, nowMillis()) ?: return@LaunchedEffect
            delay(wait)
            nowMs = nowMillis()
        }
    }
    return label
}

/** Live relative-age label; sleeps until the next string change, then recomposes once. */
@Composable
fun rememberRelativeAge(unixSeconds: Long?): String {
    var nowMs by remember(unixSeconds) { mutableStateOf(nowMillis()) }
    val label = remember(unixSeconds, nowMs) { formatRelativeAge(unixSeconds, nowMs) }
    LaunchedEffect(unixSeconds) {
        while (true) {
            val wait = msUntilNextRelativeAgeChange(unixSeconds, nowMillis()) ?: return@LaunchedEffect
            delay(wait)
            nowMs = nowMillis()
        }
    }
    return label
}
