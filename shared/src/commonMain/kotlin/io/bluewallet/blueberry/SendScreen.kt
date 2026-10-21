@file:OptIn(ExperimentalComposeUiApi::class)

package io.bluewallet.blueberry

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
import androidx.compose.runtime.mutableStateOf
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
import io.bluewallet.blueberry.ui.overlayBackStack
import io.bluewallet.blueberry.wallet.PsbtSendResult
import io.bluewallet.blueberry.wallet.SignedSendResult
import kotlinx.coroutines.launch

@Composable
fun SendScreen(
    runtime: PeersRuntime,
    db: Database,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val latestOnBack by rememberUpdatedState(onBack)
    val flow = remember { SendFlow(runtime, db, scope) { latestOnBack() } }
    DisposableEffect(runtime.walletTxsStore) {
        val off = runtime.walletTxsStore.subscribe { scope.launch { flow.snap = runtime.walletTxsStore.get() } }
        onDispose { off() }
    }
    DisposableEffect(runtime.broadcastStore) {
        val off = runtime.broadcastStore.subscribe { scope.launch { flow.broadcast = runtime.broadcastStore.get() } }
        onDispose { off() }
    }
    LaunchedEffect(flow) { flow.loadChangeScript() }
    val reviewing = flow.step == SendStep.Review
    LaunchedEffect(reviewing, flow.feeRate, flow.selectedKeys, flow.address, flow.amount) {
        if (reviewing) flow.rebuild()
    }
    LaunchedEffect(reviewing) {
        if (reviewing && flow.feeRate == null) flow.openFeeSheet()
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
    when (flow.step) {
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
                walletTotalSats = flow.walletTotalSats,
                actions =
                    SendAmountActions(
                        onAmount = { flow.amount = it },
                        onContinue = { if (flow.amountSats != null) flow.step = SendStep.Coins },
                        onBack = flow::goBack,
                    ),
            )
        SendStep.Coins -> SendCoins(flow)
        SendStep.Review -> SendReview(flow)
        SendStep.PrivateSend -> SendPrivate(flow)
        SendStep.Psbt -> {
            val psbt = (flow.build as? ReviewBuild.Built)?.result as? PsbtSendResult
            if (psbt != null) SendPsbtStep(flow.recipient, psbt.psbtHex, onDone = flow::finish, onBack = flow::goBack)
        }
        SendStep.Broadcast -> {
            val sent = flow.sent
            if (sent != null) {
                SendBroadcastStep(
                    receipt = sent.receipt,
                    status = broadcastStatus(flow.broadcast, flow.cancelling()),
                    actions =
                        SendBroadcastActions(
                            finish = BroadcastFinishActions(onDone = flow::finish, onRetry = flow::broadcastSent),
                            onCancel = flow::goBack,
                        ),
                )
            }
        }
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
    SendCoinsStep(
        to = flow.recipient,
        utxos = flow.snap.utxos,
        selectedKeys = flow.selectedKeys,
        coverage = flow.coverage(),
        rate = flow.feeRate,
        actions =
            SendCoinsActions(
                onToggle = flow::toggleCoin,
                onSelectAll = {
                    flow.selectedKeys =
                        flow.snap.utxos
                            .map { it.key }
                            .toSet()
                },
                onClear = { flow.selectedKeys = emptySet() },
                onContinue = { if (flow.coverage().covered) flow.step = SendStep.Review },
                onBack = flow::goBack,
            ),
    )
}

@Composable
private fun SendReview(flow: SendFlow) {
    val clipboard = LocalClipboardManager.current
    var hexCopied by remember(flow.build) { mutableStateOf(false) }
    val amount = flow.amountSats ?: return
    SendReviewStep(
        state =
            SendReviewState(
                to = flow.recipient,
                coins = flow.selectedRows,
                amount = amount,
                rate = flow.feeRate,
                build = flow.build,
                privateSendBlock = flow.privateSendBlock,
            ),
        actions =
            SendReviewActions(
                onEditFee = flow::openFeeSheet,
                onAddCoins = flow::addCoins,
                onPrivateSend = flow::openPrivateSend,
                onCopyHex = {
                    val signed = (flow.build as? ReviewBuild.Built)?.result as? SignedSendResult
                    if (signed != null) {
                        clipboard.setText(AnnotatedString(signed.txHex))
                        hexCopied = true
                    }
                },
                onSend = flow::send,
                onBack = flow::goBack,
            ),
        hexCopied = hexCopied,
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
        ScreenHeader(title = "Private Send", onBack = flow::goBack)
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
                            finish = BroadcastFinishActions(onDone = flow::finish, onRetry = flow::privateRetry),
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
