package io.bluewallet.blueberry

/** Send inputs shared by Send, the coin picker, and freeze; lives as long as the Send entry. */
internal data class SendDraft(
    val selectedKeys: Set<String> = emptySet(),
    val feeRateSatPerVb: Double? = null,
    val selectionNeedsAck: Boolean = false,
) {
    fun withSelection(keys: Set<String>): SendDraft = copy(selectedKeys = keys)

    fun withFeeRate(rate: Double?): SendDraft = copy(feeRateSatPerVb = rate)

    fun withSelectionNeedsAck(value: Boolean): SendDraft = copy(selectionNeedsAck = value)

    /** The subject just reviewed this set in the picker, so a vanished-coin warning is answered. */
    fun pickerCommitted(keys: Set<String>): SendDraft = copy(selectedKeys = keys, selectionNeedsAck = false)

    fun frozen(key: String): SendDraft = copy(selectedKeys = selectedKeys - key)

    companion object {
        val Empty = SendDraft()

        /** Home coin control hands a fresh set to Send; any fee from an earlier draft is stale. */
        fun fromStandalone(keys: Set<String>): SendDraft = SendDraft(selectedKeys = keys)
    }
}

/**
 * Picker selection after the filter or the snapshot changes: frozen coins always drop, and a
 * narrowed filter drops hidden coins so the weight bar, notice, and commit share one set.
 */
internal fun pickerSelection(
    selected: Set<String>,
    visibleKeys: Set<String>,
    frozenKeys: Set<String>,
    showsAll: Boolean,
): Set<String> {
    val shown = if (showsAll) selected else selected.intersect(visibleKeys)
    return shown - frozenKeys
}
