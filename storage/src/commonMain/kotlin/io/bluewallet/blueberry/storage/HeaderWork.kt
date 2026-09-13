package io.bluewallet.blueberry.storage

import com.ionspin.kotlin.bignum.integer.BigInteger
import io.bluewallet.headers.MAINNET_POW_LIMIT
import io.bluewallet.headers.decodeBlockHeader
import io.bluewallet.headers.decodeCompactTarget
import io.bluewallet.headers.headerWork

internal fun headerWorkFromBytes(header: ByteArray): BigInteger =
    try {
        val decoded = decodeBlockHeader(header)
        val target = decodeCompactTarget(decoded.bits, MAINNET_POW_LIMIT)
        headerWork(target)
    } catch (_: Exception) {
        BigInteger.ONE
    }
