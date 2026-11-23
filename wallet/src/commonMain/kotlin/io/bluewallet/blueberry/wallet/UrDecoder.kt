package io.bluewallet.blueberry.wallet

import kotlin.math.floor

/** UR types that carry a PSBT as one CBOR byte string: BCR-2020-006 `crypto-psbt` and the newer `psbt`. */
val PSBT_UR_TYPES: Set<String> = setOf("crypto-psbt", "psbt")

const val UR_NOT_UR = "Not a UR QR code."
const val UR_WRONG_TYPE = "This QR code isn't a PSBT."
const val UR_OTHER_ANIMATION = "This QR code is from a different animation."
const val UR_CORRUPT = "Couldn't read this QR frame."
const val UR_CHECKSUM_FAILED = "The scanned parts don't fit together. Start over."

private const val MAX_FRAGMENT_COUNT = 10_000
private const val PERCENT = 100
private const val MAX_ESTIMATE = 0.99
private const val FOUNTAIN_OVERHEAD = 1.75

/** What one scanned string did to a [UrDecoder]. */
sealed class UrReceive {
    /** Accepted (new or repeated part). */
    data class Progress(
        val percent: Int,
    ) : UrReceive()

    /** The whole CBOR message, its checksum verified. */
    class Complete(
        val message: ByteArray,
    ) : UrReceive()

    /** Left the decoder unchanged. */
    data class Ignored(
        val reason: String,
    ) : UrReceive()

    /** The parts reassembled into a message that fails its checksum. Only [UrDecoder.reset] recovers. */
    data class Failed(
        val reason: String,
    ) : UrReceive()
}

/**
 * Reassembles a BC-UR (BCR-2020-005/006) message one scanned string at a time, in any order and with
 * repeats, as a camera reads an animated QR. The first accepted part locks the type and the animation;
 * anything else is ignored so a stray QR can't spoil a scan in progress.
 */
class UrDecoder(
    private val acceptedTypes: Set<String>,
) {
    private var type: String? = null
    private var fountain: FountainDecoder? = null
    private val seen = HashSet<Long>()
    private var message: ByteArray? = null
    private var failed = false

    /** Null before the first accepted part, 100 once complete. */
    val percent: Int?
        get() {
            if (message != null) return PERCENT
            val decoder = fountain ?: return null
            val count = decoder.expectedCount.toDouble()
            val recovered = decoder.recoveredCount / count
            val estimate = minOf(MAX_ESTIMATE, seen.size / (count * FOUNTAIN_OVERHEAD))
            return minOf(PERCENT - 1, floor(maxOf(recovered, estimate) * PERCENT).toInt())
        }

    fun reset() {
        type = null
        fountain = null
        seen.clear()
        message = null
        failed = false
    }

    fun receive(text: String): UrReceive {
        val done = message
        return when {
            done != null -> UrReceive.Complete(done)
            failed -> UrReceive.Failed(UR_CHECKSUM_FAILED)
            else -> receivePart(runCatching { parseUrPart(text) }.getOrNull())
        }
    }

    private fun receivePart(part: ParsedUrPart?): UrReceive {
        val seqNum = part?.seqNum
        return when {
            part == null -> UrReceive.Ignored(UR_NOT_UR)
            part.type !in acceptedTypes || (type != null && part.type != type) -> UrReceive.Ignored(UR_WRONG_TYPE)
            seqNum == null -> receiveSingle(part)
            else -> receiveFountain(part, seqNum)
        }
    }

    private fun receiveSingle(part: ParsedUrPart): UrReceive {
        val body = if (fountain == null) runCatching { bytewordsDecodeMinimal(part.body) }.getOrNull() else null
        return when {
            fountain != null -> UrReceive.Ignored(UR_OTHER_ANIMATION)
            body == null -> UrReceive.Ignored(UR_CORRUPT)
            else -> {
                type = part.type
                message = body
                UrReceive.Complete(body)
            }
        }
    }

    private fun receiveFountain(
        part: ParsedUrPart,
        seqNum: Long,
    ): UrReceive {
        val decoded =
            runCatching { cborDecodeFountainPart(bytewordsDecodeMinimal(part.body)) }
                .getOrNull()
                ?.takeIf { it.isSane(seqNum, part.seqCount) }
        val decoder = fountain ?: FountainDecoder()
        val accepted =
            decoded != null &&
                decoder.receivePart(
                    seqNum = seqNum,
                    seqLength = decoded.seqLength.toInt(),
                    messageLength = decoded.messageLength.toInt(),
                    checksum = decoded.checksum,
                    fragment = decoded.fragment,
                )
        return when {
            decoded == null -> UrReceive.Ignored(UR_CORRUPT)
            !accepted -> UrReceive.Ignored(UR_OTHER_ANIMATION)
            else -> accept(decoder, part.type, seqNum)
        }
    }

    private fun accept(
        decoder: FountainDecoder,
        partType: String,
        seqNum: Long,
    ): UrReceive {
        fountain = decoder
        type = partType
        seen += seqNum
        return when {
            decoder.hasFailed -> {
                failed = true
                UrReceive.Failed(UR_CHECKSUM_FAILED)
            }
            decoder.isSuccess() -> UrReceive.Complete(decoder.resultMessage().also { message = it })
            else -> UrReceive.Progress(percent ?: 0)
        }
    }
}

/**
 * The fountain header agrees with the `/seq-count/` path and is small enough to decode. Sequence numbers start
 * at 1: a 0 would be filed as fragment -1 and block completion for good.
 */
private fun DecodedFountainPart.isSane(
    seqNum: Long,
    seqCount: Int?,
): Boolean =
    seqNum >= 1 &&
        seqNum == this.seqNum &&
        seqCount?.toLong() == seqLength &&
        seqLength in 1..MAX_FRAGMENT_COUNT &&
        fragment.isNotEmpty() &&
        messageLength in 1..(seqLength * fragment.size)

/** The PSBT inside a `crypto-psbt` / `psbt` UR message (a CBOR byte string). */
fun psbtFromUrMessage(message: ByteArray): ByteArray = cborDecodeByteString(message)
