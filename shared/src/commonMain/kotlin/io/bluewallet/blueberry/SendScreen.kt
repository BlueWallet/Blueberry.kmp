@file:OptIn(ExperimentalComposeUiApi::class)

package io.bluewallet.blueberry

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.ui.BwColors
import io.bluewallet.blueberry.ui.BwSpace
import io.bluewallet.blueberry.ui.ScreenHeader
import io.bluewallet.blueberry.ui.TintedPillButton
import io.bluewallet.blueberry.ui.overlayBackStack
import io.bluewallet.blueberry.ui.screenPushTransform
import io.bluewallet.blueberry.wallet.PsbtSendResult
import io.bluewallet.blueberry.wallet.SignedSendResult
import kotlinx.coroutines.launch

@Composable
@Suppress("LongParameterList")
fun SendScreen(
    runtime: PeersRuntime,
    db: Database,
    onBack: () -> Unit,
    initialSelectedKeys: Set<String> = emptySet(),
    onSelectedKeysChange: (Set<String>) -> Unit = {},
    onFeeRateChange: (Double?) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val latestOnBack by rememberUpdatedState(onBack)
    val flow = remember { SendFlow(runtime, db, scope, close = { latestOnBack() }, initialSelectedKeys = initialSelectedKeys) }
    DisposableEffect(runtime.walletTxsStore) {
        val applySnap = { scope.launch { flow.reloadHeld() } }
        applySnap()
        val off = runtime.walletTxsStore.subscribe { applySnap() }
        onDispose { off() }
    }
    LaunchedEffect(flow.selectedKeys) { onSelectedKeysChange(flow.selectedKeys) }
    LaunchedEffect(flow.feeRate) { onFeeRateChange(flow.feeRate) }
    DisposableEffect(runtime.broadcastStore) {
        var seenLive: Set<String>? = null
        val off =
            runtime.broadcastStore.subscribe {
                scope.launch {
                    flow.refreshBroadcast()
                    val ids = liveBroadcastIds(runtime.broadcastStore)
                    if (ids == seenLive) return@launch
                    seenLive = ids
                    flow.reloadHeld()
                }
            }
        onDispose { off() }
    }
    LaunchedEffect(runtime.walletRevision) { flow.loadChangeScript() }
    val reviewing = flow.step == SendStep.Review
    LaunchedEffect(
        reviewing,
        flow.feeRate,
        flow.selectedKeys,
        flow.address,
        flow.amount,
        flow.reviewEpoch,
        flow.selectionNeedsAck,
        runtime.walletRevision,
    ) {
        if (reviewing && !flow.selectionNeedsAck) flow.rebuild()
    }
    BackHandler { flow.goBack() }
    DisposableEffect(flow) {
        val cb: () -> Unit = { flow.goBack() }
        overlayBackStack.push(cb)
        onDispose { overlayBackStack.pop(cb) }
    }
    Box(modifier = Modifier.fillMaxSize().background(BwColors.Paper)) {
        SendStepContent(flow)
        SendFeeOverlay(flow)
    }
}

@Composable
private fun SendStepContent(flow: SendFlow) {
    AnimatedContent(
        targetState = flow.step,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { screenPushTransform(forward = flow.navForward) },
    ) { step ->
        SendStepScreen(flow, step)
    }
}

@Composable
private fun SendStepScreen(
    flow: SendFlow,
    step: SendStep,
) {
    when (step) {
        SendStep.Address ->
            if (flow.scanning) {
                SendScan(flow)
            } else {
                SendAddressStep(
                    address = flow.address,
                    label = flow.label,
                    labelError = flow.labelError,
                    actions =
                        SendAddressActions(
                            onAddress = flow::applyPayload,
                            onLabel = {
                                flow.label = it
                                if (it.isNotBlank()) flow.labelError = false
                            },
                            onScan = { flow.scanning = true },
                            onContinue = flow::continueFromAddress,
                            onBack = flow::goBack,
                        ),
                )
            }
        SendStep.Amount ->
            SendAmountStep(
                to = flow.recipient,
                amount = flow.amount,
                balance = SendAmountBalance(flow.walletTotalSats, flow.sendingSats, flow.coinAvailability),
                actions =
                    SendAmountActions(
                        onAmount = { flow.amount = it },
                        onContinue = flow::continueFromAmount,
                        onBack = flow::goBack,
                        ready = flow.heldReady,
                    ),
            )
        SendStep.Coins -> SendCoins(flow)
        SendStep.Review -> SendReview(flow)
        SendStep.PrivateSend -> SendPrivate(flow)
        SendStep.Psbt -> SendPsbt(flow)
        SendStep.Broadcast -> SendBroadcast(flow)
    }
}

@Composable
private fun SendScan(flow: SendFlow) {
    SendStepFrame(title = "Scan", caption = "Point at a Bitcoin QR code", onBack = flow::goBack, bottom = {}) {
        QrScanOverlay(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            onResult = { payload ->
                flow.applyPayload(payload)
                flow.scanning = false
            },
        )
    }
}

@Composable
private fun SendCoins(flow: SendFlow) {
    val control = remember(flow.snap, flow.feeRate, flow.heldOutpoints) { coinControlSnapshotFrom(flow.snap, flow.feeRate, flow.heldOutpoints) }
    SendCoinsStep(
        to = flow.recipient,
        utxos = flow.snap.utxos,
        control = control,
        selectedKeys = flow.selectedKeys,
        coverage = flow.coverage(),
        rate = flow.feeRate,
        actions =
            SendCoinsActions(
                onToggle = flow::toggleCoin,
                onClear = { flow.selectedKeys = emptySet() },
                onReplaceSelection = flow::replaceSelection,
                onContinue = flow::continueFromCoins,
                onBack = flow::goBack,
                ready = flow.heldReady,
            ),
    )
}

@Composable
private fun SendPsbt(flow: SendFlow) {
    val psbt = (flow.build as? ReviewBuild.Built)?.result as? PsbtSendResult
    if (psbt != null) {
        SendPsbtStep(flow.recipient, psbt.psbtHex, onDone = flow::finish, onBack = flow::goBack)
    } else {
        SendStepFrame(title = "Sign", caption = null, onBack = flow::goBack, bottom = {}) {
            HelperText("The transaction isn't ready to sign.", error = true)
        }
    }
}

@Composable
private fun SendBroadcast(flow: SendFlow) {
    val sent = flow.sent
    if (sent == null) {
        SendStepFrame(title = "Send", caption = null, onBack = flow::goBack, bottom = {}) {
            HelperText("Nothing is waiting to be sent.", error = true)
        }
        return
    }
    val owns = broadcastOwns(flow.broadcast, sent.signed.txHex)
    val status =
        if (flow.sendError != null && (!owns || flow.broadcast.phase == "error")) {
            BroadcastStatusUi("Not sent yet", flow.sendError!!, null, BroadcastTone.Failed)
        } else {
            ownedBroadcastStatus(flow.broadcast, sent.signed.txHex)
        }
    SendBroadcastStep(
        receipt = sent.receipt,
        status = status,
        actions =
            SendBroadcastActions(
                finish =
                    BroadcastFinishActions(
                        onDone = flow::finish,
                        onRetry = flow::broadcastSent,
                        onStop = if (owns && broadcastCanBeStopped(flow.broadcast)) flow::stopSending else null,
                    ),
                onClose = flow::goBack,
                onForget = if (owns && broadcastCanBeForgotten(flow.broadcast.phase)) flow::forgetFailed else null,
            ),
    )
}

@Composable
private fun SendReview(flow: SendFlow) {
    val amount = flow.amountSats
    val blocked = sendAmountUnavailable(flow.amount, flow.walletTotalSats, flow.coinAvailability)
    if (amount == null || blocked != null) {
        SendStepFrame(
            title = "Review",
            caption = "Step 4 of 4",
            onBack = flow::goBack,
            bottom = {
                TintedPillButton(text = "Review coins", onClick = flow::addCoins, modifier = Modifier.fillMaxWidth())
            },
        ) {
            HelperText(blocked ?: "This amount can't be sent.", error = true)
        }
        return
    }
    val clipboard = LocalClipboardManager.current
    SendReviewStep(
        state =
            SendReviewState(
                to = flow.recipient,
                coins = flow.selectedRows,
                amount = amount,
                rate = flow.feeRate,
                build = flow.build,
                alerts =
                    ReviewAlerts(
                        privateSendBlock = flow.privateSendBlock,
                        selectionNeedsAck = flow.selectionNeedsAck,
                        selectionNotice = flow.selectionNotice,
                        sendError = flow.sendError,
                    ),
            ),
        actions =
            SendReviewActions(
                onEditFee = flow::openFeeSheet,
                onAddCoins = flow::addCoins,
                onPrivateSend = flow::openPrivateSend,
                onCopyHex = {
                    val signed = (flow.build as? ReviewBuild.Built)?.result as? SignedSendResult
                    if (signed != null) clipboard.setText(AnnotatedString(signed.txHex))
                },
                onSend = flow::send,
                onBack = flow::goBack,
            ),
        showEdges = !flow.feeSheetOpen,
    )
}

@Composable
private fun SendPrivate(flow: SendFlow) {
    val session = flow.privateSession ?: return
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = BwSpace.ScreenX, vertical = BwSpace.ScreenY),
        verticalArrangement = Arrangement.spacedBy(BwSpace.Gap),
    ) {
        ScreenHeader(title = "Private Send", onBack = flow::goBack, action = if (flow.privateSendLive) "Close" else "Back")
        flow.sendError?.let { HelperText(it, error = true) }
        PrivateSendStep(
            session = session,
            host =
                PrivateSendHost(
                    db = flow.db,
                    wallet = flow.wallet,
                    refundAddress = session.refundAddress,
                    broadcast = flow.broadcast,
                    callbacks =
                        PrivateSendCallbacks(
                            onSigned = { flow.privateSigned = it },
                            onBroadcast = flow::privateBroadcast,
                            finish =
                                BroadcastFinishActions(
                                    onDone = flow::finish,
                                    onRetry = flow::privateRetry,
                                    onStop = if (broadcastCanBeStopped(flow.broadcast)) flow::stopSending else null,
                                ),
                            sign = flow::signPrivate,
                        ),
                ),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
private fun SendFeeOverlay(flow: SendFlow) {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = flow.feeSheetOpen,
            enter = fadeIn(animationSpec = tween(220)),
            exit = fadeOut(animationSpec = tween(180)),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f))
                        .clickable { flow.feeSheetOpen = false },
            )
        }
        AnimatedVisibility(
            visible = flow.feeSheetOpen,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            enter =
                slideInVertically(
                    animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                    initialOffsetY = { it },
                ) + fadeIn(animationSpec = tween(200)),
            exit =
                slideOutVertically(
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                    targetOffsetY = { it },
                ) + fadeOut(animationSpec = tween(160)),
        ) {
            SendFeeSheet(
                draft = flow.feeDraft,
                check = flow.feeCheck(),
                actions =
                    SendFeeSheetActions(
                        onDraft = { flow.feeDraft = it },
                        onApply = flow::applyFee,
                        onAddCoins = flow::addCoins,
                        onDismiss = { flow.feeSheetOpen = false },
                    ),
            )
        }
    }
}
