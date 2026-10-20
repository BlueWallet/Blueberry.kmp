package io.bluewallet.blueberry

/** Hoisted send-details fields so NavHost can leave Send without wiping the form. */
data class SendFormDraft(
    val address: String = "",
    val amount: String = "",
    val label: String = "",
    val feeRateText: String = "",
    val privateSend: Boolean = false,
)
