@file:OptIn(ExperimentalComposeUiApi::class)

package io.bluewallet.blueberry.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.QrScanOverlay
import io.bluewallet.blueberry.boot.listCheckpointYears
import io.bluewallet.blueberry.ui.AlwaysBounceScroll
import io.bluewallet.blueberry.ui.BlueberryLogo
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwFontFamily
import io.bluewallet.blueberry.ui.BwType
import io.bluewallet.blueberry.ui.DarkArtworkContent
import io.bluewallet.blueberry.ui.DarkSceneChrome
import io.bluewallet.blueberry.ui.PillButton
import io.bluewallet.blueberry.ui.PlainPillButton
import io.bluewallet.blueberry.ui.PlatformBodyStyle
import io.bluewallet.blueberry.ui.PlatformLargeTitle
import io.bluewallet.blueberry.ui.PlatformScreenHeader
import io.bluewallet.blueberry.ui.PlatformScrollEdge
import io.bluewallet.blueberry.ui.ProminentButtonHeight
import io.bluewallet.blueberry.ui.SCREEN_PUSH_DURATION_MS
import io.bluewallet.blueberry.ui.SCREEN_PUSH_FADE_MS
import io.bluewallet.blueberry.ui.TintedPillButton
import io.bluewallet.blueberry.ui.dither.DitherTreeBackground
import io.bluewallet.blueberry.ui.platformBottomEdgeClearance
import io.bluewallet.blueberry.ui.platformHeaderClearance
import io.bluewallet.blueberry.ui.screenPushOffsetPx

class OnboardingPersist(
    val importedSecret: (String) -> Unit,
    val createdWallet: (String) -> Unit,
    val syncYear: (Int) -> Unit,
    val forgetSecret: () -> Unit,
)

@Composable
fun OnboardingApp(
    startAtYearStep: Boolean,
    onFinished: () -> Unit,
    persist: OnboardingPersist,
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
                    persist.importedSecret(effect.raw)
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
                    persist.createdWallet(effect.mnemonic)
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
                    persist.syncYear(effect.year)
                    onFinished()
                } catch (err: Exception) {
                    state =
                        reduceOnboarding(
                            state,
                            OnboardingEvent.PersistFailed(err.message ?: err.toString()),
                        ).state
                }
            }
            OnboardingEffect.ForgetImportedSecret -> {
                try {
                    persist.forgetSecret()
                    state = reduceOnboarding(state, OnboardingEvent.ForgetSecretOk).state
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

    BackHandler(
        enabled =
            state.step == OnboardingStep.Import ||
                state.step == OnboardingStep.Create ||
                (state.step == OnboardingStep.Year && !startAtYearStep),
    ) {
        if (scanning) {
            scanning = false
        } else {
            dispatch(OnboardingEvent.Back)
        }
    }

    val pages = updateTransition(targetState = state, label = "onboarding-step")
    // Follow the page on screen, not the destination, so the chrome flips once the slide ends.
    DarkSceneChrome(darkArtwork = pages.currentState.step == OnboardingStep.Choose) {
        Box(Modifier.fillMaxSize()) {
            val safe = WindowInsets.safeDrawing.asPaddingValues()
            val showsHeader =
                state.step == OnboardingStep.Create ||
                    state.step == OnboardingStep.Import ||
                    (state.step == OnboardingStep.Year && !startAtYearStep)
            val topChrome =
                safe.calculateTopPadding() +
                    if (showsHeader) platformHeaderClearance() else 0.dp
            PlatformScrollEdge(
                modifier = Modifier.fillMaxSize(),
                top = topChrome,
                bottom = safe.calculateBottomPadding(),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    pages.AnimatedContent(
                        modifier = Modifier.fillMaxSize(),
                        contentKey = { it.step },
                        transitionSpec = {
                            val forward = onboardingNavigatesForward(initialState.step, targetState.step)
                            val enter =
                                slideInHorizontally(
                                    animationSpec = tween(SCREEN_PUSH_DURATION_MS, easing = FastOutSlowInEasing),
                                    initialOffsetX = { if (forward) screenPushOffsetPx(it) else -screenPushOffsetPx(it) / 4 },
                                ) + fadeIn(animationSpec = tween(SCREEN_PUSH_FADE_MS))
                            val exit =
                                slideOutHorizontally(
                                    animationSpec = tween(SCREEN_PUSH_DURATION_MS, easing = FastOutSlowInEasing),
                                    targetOffsetX = { if (forward) -screenPushOffsetPx(it) / 4 else screenPushOffsetPx(it) },
                                ) + fadeOut(animationSpec = tween(SCREEN_PUSH_FADE_MS))
                            enter togetherWith exit
                        },
                    ) { page ->
                        if (page.step == OnboardingStep.Create) {
                            CreateSeedPage(
                                mnemonic = page.mnemonic,
                                busy = page.busy,
                                error = page.error,
                                onContinue = { dispatch(OnboardingEvent.ConfirmCreate) },
                            )
                        } else if (page.step == OnboardingStep.Choose) {
                            DarkArtworkContent {
                                ChooseWelcome(
                                    onCreate = { dispatch(OnboardingEvent.ChooseCreate) },
                                    onImport = { dispatch(OnboardingEvent.ChooseImport) },
                                )
                            }
                        } else if (page.step == OnboardingStep.Import) {
                            ImportWalletPage(
                                state = page,
                                scanning = scanning,
                                dispatch = { dispatch(it) },
                                onScanningChange = { scanning = it },
                            )
                        } else {
                            SyncYearPage(
                                selectedYear = page.selectedYear,
                                busy = page.busy,
                                error = page.error,
                                showsHeader = !startAtYearStep,
                                onSelectYear = { dispatch(OnboardingEvent.SelectYear(it)) },
                                onContinue = { dispatch(OnboardingEvent.ConfirmYear) },
                            )
                        }
                    }
                }
            }
            if (showsHeader) {
                PlatformScreenHeader(
                    title =
                        when (state.step) {
                            OnboardingStep.Create -> "New seed"
                            OnboardingStep.Import -> "Import"
                            else -> "Sync from"
                        },
                    onBack = {
                        if (state.step == OnboardingStep.Import && scanning) {
                            scanning = false
                        } else {
                            dispatch(OnboardingEvent.Back)
                        }
                    },
                    enabled = !state.busy,
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(
                                top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding(),
                                start = 16.dp,
                                end = 16.dp,
                            ),
                )
            }
        }
    }
}

@Composable
private fun EdgeToEdgeScroll(
    extraTop: Dp = 0.dp,
    extraBottom: Dp = 0.dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    AlwaysBounceScroll(modifier.fillMaxSize(), growWithContent = true) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(safe.calculateTopPadding() + extraTop))
            content()
            Spacer(
                Modifier.height(
                    (safe.calculateBottomPadding() + platformBottomEdgeClearance() + extraBottom)
                        .coerceAtLeast(0.dp),
                ),
            )
        }
    }
}

@Composable
private fun ChooseWelcome(
    onCreate: () -> Unit,
    onImport: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        DitherTreeBackground()
        EdgeToEdgeScroll(extraBottom = (-16).dp) {
            BlueberryLogo()
            Spacer(Modifier.weight(1f))
            TintedPillButton(
                text = "Create new wallet",
                onClick = onCreate,
                modifier = Modifier.fillMaxWidth(),
            )
            PlainPillButton(
                text = "Import wallet",
                onClick = onImport,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CreateSeedPage(
    mnemonic: String?,
    busy: Boolean,
    error: String?,
    onContinue: () -> Unit,
) {
    val status = if (busy) "Saving…" else error
    EdgeToEdgeScroll(extraTop = platformHeaderClearance()) {
        PlatformLargeTitle(title = "New seed")
        Text(
            text = "Write down these 12 words. Anyone with them can spend your bitcoin.",
            style = PlatformBodyStyle(),
        )
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            SeedWords(mnemonic)
        }
        if (!status.isNullOrEmpty()) {
            Text(
                text = status,
                style = PlatformBodyStyle(),
                color = if (busy) BwColors.Ink else BwColors.Danger,
            )
        }
        PillButton(
            text = "Continue",
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            height = ProminentButtonHeight,
            pressable = true,
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun SyncYearPage(
    selectedYear: Int,
    busy: Boolean,
    error: String?,
    showsHeader: Boolean,
    onSelectYear: (Int) -> Unit,
    onContinue: () -> Unit,
) {
    var dialSettling by remember { mutableStateOf(false) }
    val saving = if (busy) "Saving…" else null
    val message = if (busy) null else error
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val headerGap = if (showsHeader) platformHeaderClearance() + 24.dp else 0.dp
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(
                    top = safe.calculateTopPadding() + headerGap,
                    bottom = safe.calculateBottomPadding() + platformBottomEdgeClearance(),
                ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlatformLargeTitle(title = "Sync from")
        Text(
            text = "What year was the first transaction for this wallet?",
            style = PlatformBodyStyle(),
        )
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            YearSlider(
                years = listCheckpointYears(),
                selected = selectedYear,
                enabled = !busy,
                onSelect = onSelectYear,
                onSettlingChange = { dialSettling = it },
            )
        }
        if (saving != null) {
            Text(text = saving, style = PlatformBodyStyle(), color = BwColors.Ink)
        }
        if (!message.isNullOrEmpty()) {
            Text(text = message, style = PlatformBodyStyle(), color = BwColors.Danger)
        }
        PillButton(
            text = "Continue",
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && !dialSettling,
            height = ProminentButtonHeight,
            pressable = true,
        )
    }
}

@Composable
private fun ImportWalletPage(
    state: OnboardingState,
    scanning: Boolean,
    dispatch: (OnboardingEvent) -> Unit,
    onScanningChange: (Boolean) -> Unit,
) {
    val saving = if (state.busy) "Saving…" else null
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val focusManager = LocalFocusManager.current
    val dismissKeyboard = remember { { focusManager.clearFocus() } }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .dismissFocusOnOutsideTap(dismissKeyboard)
                .padding(horizontal = 16.dp)
                .padding(
                    top = safe.calculateTopPadding() + platformHeaderClearance() + 24.dp,
                    bottom = safe.calculateBottomPadding() + platformBottomEdgeClearance(),
                ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlatformLargeTitle(title = "Import")
        Text(
            text = "Enter BIP39 seed, account zpub, WIF private key, or address",
            style = PlatformBodyStyle(),
        )
        ImportEntry(
            state = state,
            scanning = scanning,
            dispatch = dispatch,
            onScanningChange = onScanningChange,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        if (saving != null) {
            Text(
                text = saving,
                style = PlatformBodyStyle(),
                color = BwColors.Ink,
            )
        }
        if (!scanning) {
            PillButton(
                text = "Continue",
                onClick = {
                    focusManager.clearFocus()
                    dispatch(OnboardingEvent.SubmitImport)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
                height = ProminentButtonHeight,
                pressable = true,
            )
        }
    }
}

@Composable
private fun ImportEntry(
    state: OnboardingState,
    scanning: Boolean,
    dispatch: (OnboardingEvent) -> Unit,
    onScanningChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = if (state.busy) null else state.error
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (scanning) {
            QrScanOverlay(
                modifier = Modifier.fillMaxSize(),
                onResult = { payload ->
                    dispatch(OnboardingEvent.ImportChanged(payload))
                    onScanningChange(false)
                },
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ImportSecretField(
                    value = state.importValue,
                    enabled = !state.busy,
                    onValueChange = { dispatch(OnboardingEvent.ImportChanged(it)) },
                    onScan = { onScanningChange(true) },
                )
                if (!error.isNullOrEmpty()) {
                    Text(text = error, style = PlatformBodyStyle(), color = BwColors.Danger)
                }
            }
        }
    }
}

/** Clears focus when a tap is not handled by the text field, which dismisses the keyboard. */
private fun Modifier.dismissFocusOnOutsideTap(onDismiss: () -> Unit): Modifier =
    pointerInput(onDismiss) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val up = waitForUpOrCancellation(pass = PointerEventPass.Main)
            if (up != null && !up.isConsumed) onDismiss()
        }
    }

@Composable
private fun ImportSecretField(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onScan: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors()
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            interactionSource = interactionSource,
            minLines = 6,
            decorationBox = { innerTextField ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = value,
                    innerTextField = innerTextField,
                    enabled = enabled,
                    singleLine = false,
                    visualTransformation = VisualTransformation.None,
                    interactionSource = interactionSource,
                    placeholder = { Text("seed words, zpub, WIF, or address…") },
                    colors = colors,
                    contentPadding =
                        OutlinedTextFieldDefaults.contentPadding(
                            bottom = 40.dp,
                        ),
                    container = {
                        OutlinedTextFieldDefaults.Container(
                            enabled = enabled,
                            isError = false,
                            interactionSource = interactionSource,
                            colors = colors,
                            shape = shape,
                        )
                    },
                )
            },
        )
        Text(
            text = "Scan",
            color = BwColors.Link,
            fontFamily = BwFontFamily,
            fontSize = BwType.CaptionSize,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 12.dp)
                    .clickable(enabled = enabled, onClick = onScan),
        )
    }
}

@Composable
private fun SeedWords(mnemonic: String?) {
    val words = mnemonic?.split(" ")?.filter { it.isNotEmpty() }.orEmpty()
    val style = PlatformBodyStyle().copy(fontWeight = FontWeight.SemiBold)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        words.chunked(3).forEachIndexed { row, rowWords ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowWords.forEachIndexed { col, word ->
                    Text(
                        text = "${row * 3 + col + 1}. $word",
                        modifier = Modifier.weight(1f),
                        style = style,
                        textAlign = TextAlign.Center,
                    )
                }
                repeat(3 - rowWords.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
