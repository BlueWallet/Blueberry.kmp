package io.bluewallet.blueberry

import io.bluewallet.blueberry.storage.Database

const val SEND_FEE_RATE_KEY = "send_fee_rate"

/** Rate of the last send the user committed to, or null before the first one. */
fun loadSendFeeRate(db: Database): Double? = db.keyValue.get(SEND_FEE_RATE_KEY)?.let(::parseFeeRateSatPerVb)

fun saveSendFeeRate(
    db: Database,
    rate: Double,
) {
    db.keyValue.set(SEND_FEE_RATE_KEY, formatFeeRate(rate))
}

/** "3" for whole rates, "1.5" otherwise. */
fun formatFeeRate(rate: Double): String = if (rate % 1.0 == 0.0) rate.toLong().toString() else rate.toString()
