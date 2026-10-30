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
 * Picker selection after the filter or the snapshot changes. Coins the wallet no longer holds,
 * and coins that are frozen or already sending, leave the set. A filter only hides rows.
 */
internal fun pickerSelection(
    selected: Set<String>,
    liveKeys: Set<String>,
    frozenKeys: Set<String>,
): Set<String> = selected.intersect(liveKeys) - frozenKeys
