package io.bluewallet.blueberry

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.bluewallet.blueberry.ui.TintedPillButton
import io.bluewallet.blueberry.wallet.PsbtSendResult

internal enum class ReviewButtonAction { Send, EditFee, AddCoins }

internal class ReviewButton(
    val label: String,
    val action: ReviewButtonAction,
    val enabled: Boolean,
)

/** What the Review primary button does. The label names that action, including while send is blocked. */
internal fun reviewButton(
    rate: Double?,
    build: ReviewBuild,
    selectionNeedsAck: Boolean,
): ReviewButton =
    when {
        selectionNeedsAck -> ReviewButton("Review the selection", ReviewButtonAction.AddCoins, true)
        rate == null -> ReviewButton("Enter a fee rate", ReviewButtonAction.EditFee, true)
        build is ReviewBuild.Built && build.result is PsbtSendResult ->
            ReviewButton("Show PSBT to sign", ReviewButtonAction.Send, true)
        build is ReviewBuild.Built -> ReviewButton("Send via Tor", ReviewButtonAction.Send, true)
        build is ReviewBuild.Failed && build.isShort() ->
            ReviewButton("Add coins or lower the fee", ReviewButtonAction.AddCoins, true)
        build is ReviewBuild.Failed && build.message == SELECTION_CHANGED ->
            ReviewButton("Review the selection", ReviewButtonAction.AddCoins, true)
        build is ReviewBuild.Failed && build.message == WALLET_NOT_LOADED ->
            ReviewButton("Waiting for the wallet", ReviewButtonAction.Send, false)
        build is ReviewBuild.Failed -> ReviewButton("Can't send this", ReviewButtonAction.Send, false)
        else -> ReviewButton("Building…", ReviewButtonAction.Send, false)
    }

@Composable
internal fun ReviewPrimaryButton(
    state: SendReviewState,
    actions: SendReviewActions,
) {
    val button = reviewButton(state.rate, state.build, state.alerts.selectionNeedsAck)
    TintedPillButton(
        text = button.label,
        onClick = {
            when (button.action) {
                ReviewButtonAction.Send -> actions.onSend()
                ReviewButtonAction.EditFee -> actions.onEditFee()
                ReviewButtonAction.AddCoins -> actions.onAddCoins()
            }
        },
        enabled = button.enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun ReviewAlertText(alerts: ReviewAlerts) {
    if (alerts.selectionNeedsAck) HelperText(SELECTION_CHANGED, error = true)
    alerts.sendError?.let { HelperText(it, error = true) }
}
