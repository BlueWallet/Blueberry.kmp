package io.bluewallet.blueberry

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.bluewallet.blueberry.ui.BwSwitch
import io.bluewallet.blueberry.ui.MetricCard

// Tor switches on the Settings screen.

internal fun torWarmUpSlots(): SettingsMetricSlots =
    SettingsMetricSlots(
        label = "",
        value = "Warm up Tor at launch",
        caption = "Connects at launch, faster requests",
    )

internal fun torDirectRelaySlots(): SettingsMetricSlots =
    SettingsMetricSlots(
        label = "",
        value = "Connect to Tor directly",
        caption = "Faster, but your network sees Tor",
    )

@Composable
internal fun TorWarmUpCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) = TorToggleCard(torWarmUpSlots(), checked, onCheckedChange)

@Composable
internal fun TorToggleCard(
    slots: SettingsMetricSlots,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    MetricCard(
        label = slots.label,
        value = slots.value,
        caption = slots.caption,
        modifier = Modifier.fillMaxWidth(),
        trailing = {
            BwSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                // The card's title is separate text; name the switch for screen readers.
                modifier = Modifier.semantics { contentDescription = slots.value },
            )
        },
    )
}
