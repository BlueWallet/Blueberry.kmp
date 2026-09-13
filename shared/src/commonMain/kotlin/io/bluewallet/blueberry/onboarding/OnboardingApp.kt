package io.bluewallet.blueberry.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.QrScanOverlay
import io.bluewallet.blueberry.boot.listCheckpointYears
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwType

@Composable
fun OnboardingApp(
    startAtYearStep: Boolean,
    onFinished: () -> Unit,
    persistImportedSecret: (String) -> Unit,
    persistCreatedWallet: (String) -> Unit,
    persistSyncYear: (Int) -> Unit,
) {
    var state by remember(startAtYearStep) {
        mutableStateOf(initialOnboardingState(startAtYearStep))
    }
    var scanning by remember { mutableStateOf(false) }

    fun dispatch(event: OnboardingEvent) {
        val reduction = reduceOnboarding(state, event)
        state = reduction.state
        when (val effect = reduction.effect) {
            is OnboardingEffect.PersistImportedSecret -> {
                try {
                    persistImportedSecret(effect.raw)
                    state = reduceOnboarding(state, OnboardingEvent.PersistImportOk).state
                } catch (err: Exception) {
                    state =
                        reduceOnboarding(
                            state,
                            OnboardingEvent.PersistFailed(err.message ?: err.toString()),
                        ).state
                }
            }
            is OnboardingEffect.PersistCreatedWallet -> {
                try {
                    persistCreatedWallet(effect.mnemonic)
                    onFinished()
                } catch (err: Exception) {
                    state =
                        reduceOnboarding(
                            state,
                            OnboardingEvent.PersistFailed(err.message ?: err.toString()),
                        ).state
                }
            }
            is OnboardingEffect.PersistYear -> {
                try {
                    persistSyncYear(effect.year)
                    onFinished()
                } catch (err: Exception) {
                    state =
                        reduceOnboarding(
                            state,
                            OnboardingEvent.PersistFailed(err.message ?: err.toString()),
                        ).state
                }
            }
            null -> Unit
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .safeContentPadding()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state.step) {
            OnboardingStep.Choose -> {
                Text("Wallet")
                Text("Create a new wallet or import an existing one")
                Button(
                    onClick = { dispatch(OnboardingEvent.ChooseCreate) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Create new wallet")
                }
                Button(
                    onClick = { dispatch(OnboardingEvent.ChooseImport) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Import wallet")
                }
            }
            OnboardingStep.Import -> {
                Text("Import")
                if (scanning) {
                    QrScanOverlay(
                        modifier = Modifier.weight(1f),
                        onResult = { payload ->
                            dispatch(OnboardingEvent.ImportChanged(payload))
                            scanning = false
                        },
                    )
                } else {
                    Text("Enter BIP39 seed, account zpub, WIF private key, or address")
                    OutlinedTextField(
                        value = state.importValue,
                        onValueChange = { dispatch(OnboardingEvent.ImportChanged(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy,
                        singleLine = true,
                        placeholder = { Text("seed words, zpub, WIF, or address…") },
                        trailingIcon = {
                            Text(
                                text = "Scan",
                                color = BwColors.Link,
                                fontFamily = BwFontFamily,
                                fontSize = BwType.CaptionSize,
                                modifier =
                                    Modifier.clickable(
                                        enabled = !state.busy,
                                        onClick = { scanning = true },
                                    ),
                            )
                        },
                    )
                    Text(state.error ?: "")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            if (scanning) {
                                scanning = false
                            } else {
                                dispatch(OnboardingEvent.Back)
                            }
                        },
                        enabled = !state.busy,
                    ) { Text("Back") }
                    if (!scanning) {
                        Button(
                            onClick = { dispatch(OnboardingEvent.SubmitImport) },
                            enabled = !state.busy,
                        ) { Text("Continue") }
                    }
                }
            }
            OnboardingStep.Create -> {
                Text("New seed")
                Text("Write down these 12 words. Anyone with them can spend your bitcoin.")
                val words = state.mnemonic?.split(" ").orEmpty()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    words.chunked(3).forEachIndexed { row, rowWords ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            rowWords.forEachIndexed { col, word ->
                                val n = row * 3 + col + 1
                                Text(
                                    "$n. $word",
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
                Text(if (state.busy) "Saving…" else state.error ?: "")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { dispatch(OnboardingEvent.Back) },
                        enabled = !state.busy,
                    ) { Text("Back") }
                    Button(
                        onClick = { dispatch(OnboardingEvent.ConfirmCreate) },
                        enabled = !state.busy,
                    ) { Text("Continue") }
                }
            }
            OnboardingStep.Year -> {
                Text("Sync from")
                Text("What year was the first transaction for this wallet?")
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(listCheckpointYears()) { year ->
                        val label = if (year == state.selectedYear) "• $year" else "$year"
                        Text(
                            label,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !state.busy) {
                                        dispatch(OnboardingEvent.SelectYear(year))
                                    }.padding(vertical = 8.dp),
                        )
                    }
                }
                Text(if (state.busy) "Saving…" else state.error ?: "")
                Button(
                    onClick = { dispatch(OnboardingEvent.ConfirmYear) },
                    enabled = !state.busy,
                ) { Text("Continue") }
            }
        }
    }
}
