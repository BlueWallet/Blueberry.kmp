package io.bluewallet.blueberry.headers

import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.HeaderWrite
import io.bluewallet.blueberry.storage.PeerWrite
import io.bluewallet.headers.BlockHeader
import io.bluewallet.headers.HeaderConsensusParams
import io.bluewallet.headers.HeaderRecord
import io.bluewallet.headers.MAX_UINT256
import io.bluewallet.headers.TrustedHeaderCheckpoint
import io.bluewallet.headers.decodeCompactTarget
import io.bluewallet.headers.encodeBlockHeader
import io.bluewallet.headers.hashToUint256
import io.bluewallet.headers.headerHashDisplay
import io.bluewallet.headers.headerHashInternal
import io.bluewallet.headers.hexToBytes
import io.bluewallet.headers.storedHeaderFromBlockHeader

const val EASY_BITS = 0x207fffff
val EASY_LIMIT = decodeCompactTarget(EASY_BITS.toLong() and 0xffffffffL, MAX_UINT256)

fun mineHeader(
    previousHash: ByteArray? = null,
    bits: Long = EASY_BITS.toLong() and 0xffffffffL,
    timestamp: Long,
    marker: Int,
    powLimit: com.ionspin.kotlin.bignum.integer.BigInteger = EASY_LIMIT,
): BlockHeader {
    val target = decodeCompactTarget(bits, powLimit)
    val header =
        BlockHeader(
            version = marker,
            previousBlockHash = previousHash?.copyOf() ?: ByteArray(32),
            merkleRoot = ByteArray(32) { (marker and 0xff).toByte() },
            timestamp = timestamp,
            bits = bits,
            nonce = 0,
        )
    var nonce = 0L
    while (nonce <= 0xffffffffL) {
        val candidate = header.copy(nonce = nonce)
        if (hashToUint256(headerHashInternal(candidate)) <= target) return candidate
        nonce++
    }
    error("unable to mine deterministic test header")
}

fun record(
    height: Int,
    header: BlockHeader,
): HeaderRecord = storedHeaderFromBlockHeader(height.toLong(), header)

fun easyConsensus(checkpoint: BlockHeader): HeaderConsensusParams =
    HeaderConsensusParams(
        powLimit = EASY_LIMIT,
        targetSpacingSeconds = 10,
        targetTimespanSeconds = 40,
        retargetInterval = 4,
        medianTimeSpan = 11,
        maxFutureSeconds = 7_200,
        checkpoint =
            TrustedHeaderCheckpoint(
                height = 0,
                headerBytes = encodeBlockHeader(checkpoint),
                hashDisplay = headerHashDisplay(checkpoint),
                previousTimestamps = emptyList(),
            ),
    )

fun persistRecords(
    db: Database,
    records: List<HeaderRecord>,
) {
    db.headers.append(
        records.map { r ->
            HeaderWrite(
                height = r.height.toInt(),
                hashInternalHex = r.hashInternalHex,
                header = hexToBytes(r.headerHex),
            )
        },
    )
}

fun upsertPeer(
    db: Database,
    host: String,
) {
    db.peers.upsert(
        PeerWrite(
            host = host,
            port = 8333,
            services = 0uL,
            alive = true,
            usedForBlocks = false,
            lastProbedAt = null,
        ),
    )
}

class ReorgFixture(
    val params: HeaderConsensusParams,
    val canonical: List<HeaderRecord>,
    val heavierFork: List<BlockHeader>,
    val weakerFork: List<BlockHeader>,
    val nextCanonical: BlockHeader,
)

fun buildReorgFixture(): ReorgFixture {
    val checkpoint = mineHeader(timestamp = 1_000, marker = 1)
    val params = easyConsensus(checkpoint)
    var tip = checkpoint
    val canonical = mutableListOf(record(0, checkpoint))
    for ((i, ts) in listOf(1_010L, 1_020L, 1_040L).withIndex()) {
        tip = mineHeader(previousHash = headerHashInternal(tip), timestamp = ts, marker = i + 2)
        canonical.add(record(i + 1, tip))
    }
    val forkParent = canonical[1]
    val forkA =
        mineHeader(
            previousHash = hexToBytes(forkParent.hashInternalHex),
            timestamp = 1_030,
            marker = 20,
        )
    val forkB = mineHeader(previousHash = headerHashInternal(forkA), timestamp = 1_041, marker = 21)
    val forkC = mineHeader(previousHash = headerHashInternal(forkB), timestamp = 1_051, marker = 22)
    val nextCanonical =
        mineHeader(
            previousHash = hexToBytes(canonical.last().hashInternalHex),
            timestamp = 1_060,
            marker = 5,
        )
    return ReorgFixture(params, canonical, listOf(forkA, forkB, forkC), listOf(forkA), nextCanonical)
}

fun mineEasyChain(count: Int): Pair<HeaderConsensusParams, List<HeaderRecord>> {
    val checkpoint = mineHeader(timestamp = 1_000, marker = 1)
    val params = easyConsensus(checkpoint)
    var tip = checkpoint
    val records = mutableListOf(record(0, checkpoint))
    for (i in 1 until count) {
        tip =
            mineHeader(
                previousHash = headerHashInternal(tip),
                timestamp = 1_000L + i * 10,
                marker = i + 1,
            )
        records.add(record(i, tip))
    }
    return params to records
}
