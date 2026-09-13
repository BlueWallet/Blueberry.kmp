package io.bluewallet.blueberry.headers

import io.bluewallet.blueberry.storage.StoredHeader
import io.bluewallet.headers.HeaderChainEntry
import io.bluewallet.headers.HeaderConsensusParams
import io.bluewallet.headers.HeaderRecord
import io.bluewallet.headers.ValidatedHeaderChain
import io.bluewallet.headers.bytesToHex
import io.bluewallet.headers.decodeBlockHeader
import io.bluewallet.headers.decodeCompactTarget
import io.bluewallet.headers.headerWork
import io.bluewallet.headers.hexToBytes

const val TRUSTED_CHAIN_WINDOW = 4_096

fun internalHexToDisplayHex(internalHex: String): String {
    val bytes = hexToBytes(internalHex)
    bytes.reverse()
    return bytesToHex(bytes)
}

fun trustedChainFromStored(
    records: List<StoredHeader>,
    params: HeaderConsensusParams,
): ValidatedHeaderChain {
    if (records.isEmpty()) {
        throw IllegalArgumentException("trusted chain is empty")
    }

    val headers = ArrayList<HeaderRecord>(records.size)
    val byHeight = LinkedHashMap<Long, HeaderRecord>()
    val heightByHashInternal = LinkedHashMap<String, Long>()
    val entriesByHeight = LinkedHashMap<Long, HeaderChainEntry>()
    val cumulativeWorkByHeight = LinkedHashMap<Long, com.ionspin.kotlin.bignum.integer.BigInteger>()

    var expectedHeight = records[0].height
    for (stored in records) {
        if (stored.height != expectedHeight) {
            throw IllegalStateException(
                "trusted chain gap at height ${stored.height}, expected $expectedHeight",
            )
        }
        expectedHeight++

        val header = decodeBlockHeader(stored.header)
        val hashInternal = hexToBytes(stored.hashInternalHex)
        val target = decodeCompactTarget(header.bits, params.powLimit)
        val work = headerWork(target)
        val record =
            HeaderRecord(
                height = stored.height.toLong(),
                hashDisplay = internalHexToDisplayHex(stored.hashInternalHex),
                hashInternalHex = stored.hashInternalHex,
                headerHex = bytesToHex(stored.header),
            )
        val entry =
            HeaderChainEntry(
                record = record,
                header =
                    header.copy(
                        previousBlockHash = header.previousBlockHash.copyOf(),
                        merkleRoot = header.merkleRoot.copyOf(),
                    ),
                hashInternal = hashInternal,
                target = target,
                work = work,
                cumulativeWork = stored.cumulativeWork,
            )

        headers.add(record)
        byHeight[record.height] = record
        heightByHashInternal[record.hashInternalHex] = record.height
        entriesByHeight[record.height] = entry
        cumulativeWorkByHeight[record.height] = stored.cumulativeWork
    }

    val tip = records.last()
    val tipRecord = headers.last()
    return ValidatedHeaderChain(
        headers = headers,
        tipHeight = tip.height.toLong(),
        tipHashInternal = hexToBytes(tip.hashInternalHex),
        tipHashDisplay = tipRecord.hashDisplay,
        chainWork = tip.cumulativeWork,
        params = params,
        byHeight = byHeight,
        heightByHashInternal = heightByHashInternal,
        entriesByHeight = entriesByHeight,
        cumulativeWorkByHeight = cumulativeWorkByHeight,
    )
}
