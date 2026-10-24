package io.bluewallet.blueberry

/** Send inputs shared by Send, the coin picker, and freeze; lives as long as the Send entry. */
internal data class SendDraft(
    val selectedKeys: Set<String> = emptySet(),
    val feeRateSatPerVb: Double? = null,
    val selectionNeedsAck: Boolean = false,
    /** Set by a non-empty picker commit; Send consumes it on its next composition, even a recreated one. */
    val advanceToDetails: Boolean = false,
) {
    fun withSelection(keys: Set<String>): SendDraft = copy(selectedKeys = keys)

    fun withFeeRate(rate: Double?): SendDraft = copy(feeRateSatPerVb = rate)

    fun withSelectionNeedsAck(value: Boolean): SendDraft = copy(selectionNeedsAck = value)

    /**
     * The subject reviewed this set in the picker, which answers an earlier vanished-coin warning.
     * Coins that vanished while the picker was open were never seen, so they raise the warning again
     * and keep Send on the coin step where it is shown.
     */
    fun pickerCommitted(
        keys: Set<String>,
        coinsVanished: Boolean = false,
    ): SendDraft =
        copy(
            selectedKeys = keys,
            selectionNeedsAck = coinsVanished,
            advanceToDetails = keys.isNotEmpty() && !coinsVanished,
        )

    fun detailsAdvanceConsumed(): SendDraft = copy(advanceToDetails = false)

    fun frozen(key: String): SendDraft = copy(selectedKeys = selectedKeys - key)

    companion object {
        val Empty = SendDraft()

        /** Home coin control hands a fresh set to Send; any fee from an earlier draft is stale. */
        fun fromStandalone(keys: Set<String>): SendDraft = SendDraft(selectedKeys = keys)
    }
}

internal data class SendStepResolution(
    val step: SendStep,
    /** True when a pending advance was read and must be cleared from the draft. */
    val consumeAdvance: Boolean,
)

/**
 * The single rule for where Send stands after a selection change, a picker commit, or recreation.
 * A pending advance moves the coin step to Details; Details with no inputs, or with a vanished-coin
 * warning only the coin step shows, falls back to choosing coins; later steps are never moved.
 */
internal fun resolveSendStep(
    step: SendStep,
    selected: Set<String>,
    advanceToDetails: Boolean,
    selectionNeedsAck: Boolean = false,
): SendStepResolution {
    val next =
        when {
            advanceToDetails && step == SendStep.Utxos && selected.isNotEmpty() -> SendStep.Details
            step == SendStep.Details && (selected.isEmpty() || selectionNeedsAck) -> SendStep.Utxos
            else -> step
        }
    return SendStepResolution(next, consumeAdvance = advanceToDetails)
}

/** Leaving Send drops a finished broadcast so the next payment starts clean; a live job is kept. */
internal fun broadcastClearedOnLeave(phase: String?): Boolean = phase == "success" || phase == "error"

/** Continue on the coin step also answers a vanished-coin warning, so it stays live while one is shown. */
internal fun utxosContinueEnabled(
    selected: Set<String>,
    selectionNeedsAck: Boolean,
): Boolean = selected.isNotEmpty() || selectionNeedsAck

/**
 * Picker selection after the filter or the snapshot changes. Only coins the wallet still holds and
 * can spend survive, and a narrowed filter also drops hidden coins, so the weight bar, Clear, the
 * notice, and the commit all read one set.
 */
internal fun pickerSelection(
    selected: Set<String>,
    liveKeys: Set<String>,
    visibleKeys: Set<String>,
    frozenKeys: Set<String>,
    showsAll: Boolean,
): Set<String> {
    val held = selected.intersect(liveKeys) - frozenKeys
    return if (showsAll) held else held.intersect(visibleKeys)
}
