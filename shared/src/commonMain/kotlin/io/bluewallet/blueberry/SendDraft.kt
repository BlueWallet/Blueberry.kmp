package io.bluewallet.blueberry

/** Send inputs shared by Send and coin control; lives as long as the Send entry. */
internal data class SendDraft(
    val selectedKeys: Set<String> = emptySet(),
    val feeRateSatPerVb: Double? = null,
) {
    fun withSelection(keys: Set<String>): SendDraft = copy(selectedKeys = keys)

    fun withFeeRate(rate: Double?): SendDraft = copy(feeRateSatPerVb = rate)

    fun frozen(key: String): SendDraft = copy(selectedKeys = selectedKeys - key)

    companion object {
        val Empty = SendDraft()

        /** Home coin control hands a fresh set to Send; any fee from an earlier draft is stale. */
        fun fromStandalone(keys: Set<String>): SendDraft = SendDraft(selectedKeys = keys)
    }
}

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
