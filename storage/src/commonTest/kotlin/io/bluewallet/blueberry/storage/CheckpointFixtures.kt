package io.bluewallet.blueberry.storage

import com.ionspin.kotlin.bignum.integer.BigInteger
import io.bluewallet.headers.CHECKPOINT_HEADER
import io.bluewallet.headers.checkpointSeedRecord

fun checkpointDbRecord(): HeaderRecord {
    val seed = checkpointSeedRecord()
    return HeaderRecord(
        height = seed.height.toInt(),
        hashInternalHex = seed.hashInternalHex,
        header = CHECKPOINT_HEADER.copyOf(),
    )
}

fun testHeader(
    height: Int,
    suffix: String,
    cumulativeWork: BigInteger,
): HeaderWrite =
    HeaderWrite(
        height = height,
        hashInternalHex = "i".repeat(64 - suffix.length) + suffix,
        header = ByteArray(80) { 0xab.toByte() },
        cumulativeWork = cumulativeWork,
    )
