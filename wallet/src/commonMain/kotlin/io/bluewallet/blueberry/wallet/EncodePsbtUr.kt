package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.Crypto

/**
 * BC-UR v2 `crypto-psbt` encoding (BCR-2020-005 fountain codes / BCR-2020-006 URs /
 * BCR-2020-012 registry), ported from `@ngraveio/bc-ur` and `@keystonehq/bc-ur-registry`
 * (see `CryptoPSBT.toUREncoder`). No UR Maven dependency: CRC32, bytewords, the Xoshiro256**
 * fountain PRNG, and minimal CBOR are all implemented below.
 */
private const val CRYPTO_PSBT_UR_TYPE = "crypto-psbt"
private const val FOUNTAIN_MIN_FRAGMENT_LENGTH = 10

/** Encode a PSBT as BC-UR v2 `crypto-psbt` fragments (one string per `UREncoder.nextPart()`). */
fun encodeCryptoPsbtUrFragments(
    psbt: ByteArray,
    capacity: Int = BC_UR_PSBT_CAPACITY,
): List<String> {
    val message = cborEncodeByteString(psbt)
    val encoder = UrEncoder(message, CRYPTO_PSBT_UR_TYPE, capacity)
    return List(encoder.fragmentsLength) { encoder.nextPart() }
}

/** Hex-string overload of [encodeCryptoPsbtUrFragments]. */
fun encodeCryptoPsbtUrFragments(
    psbtHex: String,
    capacity: Int = BC_UR_PSBT_CAPACITY,
): List<String> = encodeCryptoPsbtUrFragments(hexToBytes(psbtHex), capacity)

/** Inverts [encodeCryptoPsbtUrFragments]: reassembles the PSBT bytes from its UR fragments. */
internal fun decodeCryptoPsbtUrFragments(parts: List<String>): ByteArray {
    require(parts.isNotEmpty()) { "no UR parts provided" }
    val parsed = parts.map { parseUrPart(it) }
    val type = parsed.first().type
    require(parsed.all { it.type == type }) { "UR parts have mixed types" }

    if (parsed.size == 1 && parsed[0].seqNum == null) {
        val message = bytewordsDecodeMinimal(parsed[0].body)
        return cborDecodeByteString(message)
    }

    val decoder = FountainDecoder()
    for (p in parsed) {
        val seqNum = requireNotNull(p.seqNum) { "multi-part UR is missing seq/count: $p" }
        val partCbor = bytewordsDecodeMinimal(p.body)
        val fountainPart = cborDecodeFountainPart(partCbor)
        decoder.receivePart(
            seqNum = seqNum,
            seqLength = fountainPart.seqLength.toInt(),
            messageLength = fountainPart.messageLength.toInt(),
            checksum = fountainPart.checksum,
            fragment = fountainPart.fragment,
        )
        if (decoder.isComplete()) break
    }
    require(decoder.isSuccess()) { "failed to reassemble UR fragments" }
    return cborDecodeByteString(decoder.resultMessage())
}

// ---------------------------------------------------------------------------------------------
// UR encode (ports urEncoder.ts)
// ---------------------------------------------------------------------------------------------

private class UrEncoder(
    private val message: ByteArray,
    private val urType: String,
    maxFragmentLength: Int,
    firstSeqNum: Long = 0L,
    minFragmentLength: Int = FOUNTAIN_MIN_FRAGMENT_LENGTH,
) {
    private val fountainEncoder = FountainEncoder(message, maxFragmentLength, firstSeqNum, minFragmentLength)

    val fragmentsLength: Int get() = fountainEncoder.fragmentsLength

    fun nextPart(): String {
        val part = fountainEncoder.nextPart()
        return if (fountainEncoder.isSinglePart()) {
            encodeSinglePart()
        } else {
            encodeMultiPart(part)
        }
    }

    private fun encodeSinglePart(): String {
        val body = bytewordsEncodeMinimal(message)
        return "ur:$urType/$body"
    }

    private fun encodeMultiPart(part: FountainEncoderPart): String {
        val seq = "${part.seqNum}-${part.seqLength}"
        val partCbor = cborEncodeFountainPart(part)
        val body = bytewordsEncodeMinimal(partCbor)
        return "ur:$urType/$seq/$body"
    }
}

private data class ParsedUrPart(
    val type: String,
    val seqNum: Long?,
    val seqCount: Int?,
    val body: String,
)

private fun parseUrPart(part: String): ParsedUrPart {
    val trimmed = part.trim()
    val schemeEnd = trimmed.indexOf(':')
    require(schemeEnd >= 0 && trimmed.substring(0, schemeEnd).lowercase() == "ur") {
        "not a UR string: $part"
    }
    val segments = trimmed.substring(schemeEnd + 1).split('/')
    return when (segments.size) {
        2 -> ParsedUrPart(segments[0].lowercase(), null, null, segments[1])
        3 -> {
            val seqParts = segments[1].split('-')
            require(seqParts.size == 2) { "invalid UR sequence component: ${segments[1]}" }
            ParsedUrPart(segments[0].lowercase(), seqParts[0].toLong(), seqParts[1].toInt(), segments[2])
        }
        else -> error("invalid UR string: $part")
    }
}

// ---------------------------------------------------------------------------------------------
// Fountain encoder (ports fountainEncoder.ts)
// ---------------------------------------------------------------------------------------------

private class FountainEncoderPart(
    val seqNum: Long,
    val seqLength: Int,
    val messageLength: Int,
    val checksum: Long,
    val fragment: ByteArray,
)

private class FountainEncoder(
    message: ByteArray,
    maxFragmentLength: Int,
    firstSeqNum: Long,
    minFragmentLength: Int,
) {
    private val messageLength: Int = message.size
    private val fragments: List<ByteArray>
    private val fragmentLength: Int
    private var seqNum: Long
    private val checksum: Long

    init {
        require(messageLength > 0) { "message must not be empty" }
        fragmentLength = findNominalFragmentLength(messageLength, minFragmentLength, maxFragmentLength)
        fragments = partitionMessage(message, fragmentLength)
        seqNum = toUint32(firstSeqNum)
        checksum = crc32(message)
    }

    val fragmentsLength: Int get() = fragments.size

    fun isSinglePart(): Boolean = fragments.size == 1

    private fun mix(indexes: List<Int>): ByteArray {
        val result = ByteArray(fragmentLength)
        for (index in indexes) {
            val fragment = fragments[index]
            for (i in 0 until fragmentLength) {
                result[i] = (result[i].toInt() xor fragment[i].toInt()).toByte()
            }
        }
        return result
    }

    fun nextPart(): FountainEncoderPart {
        seqNum = toUint32(seqNum + 1)
        val indexes = chooseFragments(seqNum, fragments.size, checksum)
        val mixed = mix(indexes)
        return FountainEncoderPart(seqNum, fragments.size, messageLength, checksum, mixed)
    }

    companion object {
        fun findNominalFragmentLength(
            messageLength: Int,
            minFragmentLength: Int,
            maxFragmentLength: Int,
        ): Int {
            require(messageLength > 0)
            require(minFragmentLength > 0)
            require(maxFragmentLength >= minFragmentLength)
            val maxFragmentCount = ceilDiv(messageLength, minFragmentLength)
            var fragmentLength = 0
            for (fragmentCount in 1..maxFragmentCount) {
                fragmentLength = ceilDiv(messageLength, fragmentCount)
                if (fragmentLength <= maxFragmentLength) break
            }
            return fragmentLength
        }

        fun partitionMessage(
            message: ByteArray,
            fragmentLength: Int,
        ): List<ByteArray> {
            val fragments = mutableListOf<ByteArray>()
            var offset = 0
            while (offset < message.size) {
                val chunkSize = minOf(fragmentLength, message.size - offset)
                val fragment = ByteArray(fragmentLength)
                message.copyInto(fragment, 0, offset, offset + chunkSize)
                fragments.add(fragment)
                offset += chunkSize
            }
            return fragments
        }

        private fun ceilDiv(
            a: Int,
            b: Int,
        ): Int = (a + b - 1) / b
    }
}

// ---------------------------------------------------------------------------------------------
// Fountain decoder (ports fountainDecoder.ts, inverse of FountainEncoder)
// ---------------------------------------------------------------------------------------------

private class FountainDecoderPart(
    val indexes: List<Int>,
    val fragment: ByteArray,
) {
    fun isSimple(): Boolean = indexes.size == 1
}

private class FountainDecoder {
    private var result: ByteArray? = null
    private var failed = false
    private var expectedMessageLength = 0
    private var expectedChecksum = 0L
    private var expectedFragmentLength = 0
    private var expectedPartIndexes: List<Int> = emptyList()
    private val queuedParts = ArrayDeque<FountainDecoderPart>()
    private val receivedPartIndexes = mutableListOf<Int>()
    private val mixedParts = mutableListOf<FountainDecoderPart>()
    private val simpleParts = mutableListOf<FountainDecoderPart>()

    fun isComplete(): Boolean = result != null

    fun isSuccess(): Boolean = !failed && isComplete()

    fun resultMessage(): ByteArray = if (isSuccess()) result!! else ByteArray(0)

    fun receivePart(
        seqNum: Long,
        seqLength: Int,
        messageLength: Int,
        checksum: Long,
        fragment: ByteArray,
    ): Boolean {
        if (isComplete()) return false
        if (!validatePart(seqLength, messageLength, checksum, fragment.size)) return false

        val indexes = chooseFragments(seqNum, seqLength, checksum)
        queuedParts.addLast(FountainDecoderPart(indexes, fragment))

        while (!isComplete() && queuedParts.isNotEmpty()) {
            processQueuedItem()
        }
        return true
    }

    private fun validatePart(
        seqLength: Int,
        messageLength: Int,
        checksum: Long,
        fragmentLength: Int,
    ): Boolean {
        if (expectedPartIndexes.isEmpty()) {
            expectedPartIndexes = (0 until seqLength).toList()
            expectedMessageLength = messageLength
            expectedChecksum = checksum
            expectedFragmentLength = fragmentLength
            return true
        }
        if (expectedPartIndexes.size != seqLength) return false
        if (expectedMessageLength != messageLength) return false
        if (expectedChecksum != checksum) return false
        if (expectedFragmentLength != fragmentLength) return false
        return true
    }

    private fun reducePartByPart(
        a: FountainDecoderPart,
        b: FountainDecoderPart,
    ): FountainDecoderPart {
        val bSet = b.indexes.toHashSet()
        return if (a.indexes.toHashSet().containsAll(bSet)) {
            val newIndexes = a.indexes.filter { it !in bSet }
            FountainDecoderPart(newIndexes, xorBytes(a.fragment, b.fragment))
        } else {
            a
        }
    }

    private fun reduceMixedBy(part: FountainDecoderPart) {
        val newMixed = mutableListOf<FountainDecoderPart>()
        for (mixed in mixedParts) {
            val reduced = reducePartByPart(mixed, part)
            if (reduced.isSimple()) queuedParts.addLast(reduced) else newMixed.add(reduced)
        }
        mixedParts.clear()
        mixedParts.addAll(newMixed)
    }

    private fun processSimplePart(part: FountainDecoderPart) {
        val fragmentIndex = part.indexes[0]
        if (fragmentIndex in receivedPartIndexes) return

        simpleParts.add(part)
        receivedPartIndexes.add(fragmentIndex)

        if (receivedPartIndexes.size == expectedPartIndexes.size &&
            receivedPartIndexes.toHashSet() == expectedPartIndexes.toHashSet()
        ) {
            val sorted = simpleParts.sortedBy { it.indexes[0] }
            val message = joinFragments(sorted.map { it.fragment }, expectedMessageLength)
            if (crc32(message) == expectedChecksum) {
                result = message
            } else {
                failed = true
            }
        } else {
            reduceMixedBy(part)
        }
    }

    private fun processMixedPart(part: FountainDecoderPart) {
        if (mixedParts.any { it.indexes.toHashSet() == part.indexes.toHashSet() }) return

        var reduced = simpleParts.fold(part) { acc, p -> reducePartByPart(acc, p) }
        reduced = mixedParts.fold(reduced) { acc, p -> reducePartByPart(acc, p) }

        if (reduced.isSimple()) {
            queuedParts.addLast(reduced)
        } else {
            reduceMixedBy(reduced)
            mixedParts.add(reduced)
        }
    }

    private fun processQueuedItem() {
        val part = queuedParts.removeFirstOrNull() ?: return
        if (part.isSimple()) processSimplePart(part) else processMixedPart(part)
    }

    companion object {
        fun joinFragments(
            fragments: List<ByteArray>,
            messageLength: Int,
        ): ByteArray {
            val out = ByteArray(fragments.sumOf { it.size })
            var offset = 0
            for (fragment in fragments) {
                fragment.copyInto(out, offset)
                offset += fragment.size
            }
            return out.copyOfRange(0, minOf(messageLength, out.size))
        }
    }
}

private fun xorBytes(
    a: ByteArray,
    b: ByteArray,
): ByteArray {
    val length = maxOf(a.size, b.size)
    val out = ByteArray(length)
    for (i in 0 until length) {
        val av = if (i < a.size) a[i].toInt() else 0
        val bv = if (i < b.size) b[i].toInt() else 0
        out[i] = (av xor bv).toByte()
    }
    return out
}

// ---------------------------------------------------------------------------------------------
// Fountain fragment selection (ports fountainUtils.ts + xoshiro.ts + @keystonehq/alias-sampling)
// ---------------------------------------------------------------------------------------------

private fun toUint32(value: Long): Long = value and 0xFFFFFFFFL

private fun intToBytesBE(value: Long): ByteArray {
    val v = toUint32(value)
    return byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
}

/**
 * The first `seqLength` parts are the "pure" fragments, not mixed with any others: generating
 * exactly `seqLength` parts from a [FountainEncoder] therefore always yields a decodable set.
 */
private fun chooseFragments(
    seqNum: Long,
    seqLength: Int,
    checksum: Long,
): List<Int> {
    if (seqNum <= seqLength) {
        return listOf((seqNum - 1).toInt())
    }
    val seed = intToBytesBE(seqNum) + intToBytesBE(checksum)
    val rng = Xoshiro256(seed)
    val degree = chooseDegree(seqLength, rng)
    val shuffled = shuffle((0 until seqLength).toList(), rng)
    return shuffled.take(degree)
}

private fun chooseDegree(
    seqLength: Int,
    rng: Xoshiro256,
): Int {
    val probabilities = DoubleArray(seqLength) { 1.0 / (it + 1) }
    val alias = AliasSampler(probabilities)
    return alias.draw(rng) + 1
}

private fun shuffle(
    items: List<Int>,
    rng: Xoshiro256,
): List<Int> {
    val remaining = items.toMutableList()
    val result = mutableListOf<Int>()
    while (remaining.isNotEmpty()) {
        val index = rng.nextInt(0, remaining.size - 1)
        result.add(remaining.removeAt(index))
    }
    return result
}

/** Walker's alias method, matching `@keystonehq/alias-sampling`. */
private class AliasSampler(
    probabilities: DoubleArray,
) {
    private val prob: DoubleArray
    private val alias: IntArray

    init {
        val n = probabilities.size
        val sum = probabilities.sum()
        require(sum > 0.0) { "probability sum must be greater than zero" }
        val scaled = DoubleArray(n) { probabilities[it] * n / sum }
        prob = DoubleArray(n)
        alias = IntArray(n)
        val small = ArrayDeque<Int>()
        val large = ArrayDeque<Int>()
        for (i in n - 1 downTo 0) {
            if (scaled[i] < 1.0) small.addLast(i) else large.addLast(i)
        }
        while (small.isNotEmpty() && large.isNotEmpty()) {
            val less = small.removeLast()
            val more = large.removeLast()
            prob[less] = scaled[less]
            alias[less] = more
            scaled[more] = (scaled[more] + scaled[less]) - 1.0
            if (scaled[more] < 1.0) small.addLast(more) else large.addLast(more)
        }
        while (large.isNotEmpty()) prob[large.removeLast()] = 1.0
        while (small.isNotEmpty()) prob[small.removeLast()] = 1.0
    }

    fun draw(rng: Xoshiro256): Int {
        val c = (rng.nextDouble() * prob.size).toInt().coerceIn(0, prob.size - 1)
        return if (rng.nextDouble() < prob[c]) c else alias[c]
    }
}

/** Xoshiro256** PRNG seeded by SHA-256(seed), matching `@ngraveio/bc-ur`'s `xoshiro.ts`. */
@OptIn(ExperimentalUnsignedTypes::class)
private class Xoshiro256(
    seed: ByteArray,
) {
    private val s = ULongArray(4)

    init {
        val digest = Crypto.sha256(seed)
        for (i in 0 until 4) {
            var v = 0uL
            for (n in 0 until 8) {
                v = (v shl 8) or digest[i * 8 + n].toUByte().toULong()
            }
            s[i] = v
        }
    }

    private fun roll(): ULong {
        val result = (s[1] * 5uL).rotateLeft(7) * 9uL
        val t = s[1] shl 17
        s[2] = s[2] xor s[0]
        s[3] = s[3] xor s[1]
        s[1] = s[1] xor s[2]
        s[0] = s[0] xor s[3]
        s[2] = s[2] xor t
        s[3] = s[3].rotateLeft(45)
        return result
    }

    fun nextDouble(): Double = roll().toDouble() / TWO_POW_64

    fun nextInt(
        low: Int,
        high: Int,
    ): Int = kotlin.math.floor(nextDouble() * (high - low + 1) + low).toInt()

    companion object {
        private const val TWO_POW_64 = 18446744073709551616.0
    }
}

// ---------------------------------------------------------------------------------------------
// CRC32 (matches the `crc` npm package's `crc32`: CRC-32/ISO-HDLC)
// ---------------------------------------------------------------------------------------------

private val CRC32_TABLE: IntArray =
    IntArray(256) { n ->
        var c = n
        repeat(8) {
            c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1)
        }
        c
    }

private fun crc32(bytes: ByteArray): Long {
    var crc = -1 // 0xFFFFFFFF
    for (b in bytes) {
        val index = (crc xor (b.toInt() and 0xFF)) and 0xFF
        crc = (crc ushr 8) xor CRC32_TABLE[index]
    }
    return (crc.toLong() xor 0xFFFFFFFFL) and 0xFFFFFFFFL
}

private fun crc32Bytes(bytes: ByteArray): ByteArray {
    val v = crc32(bytes)
    return byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
}

// ---------------------------------------------------------------------------------------------
// Bytewords, minimal style only (ports bytewords.ts)
// ---------------------------------------------------------------------------------------------

private const val BYTEWORDS =
    "ableacidalsoapexaquaarchatomauntawayaxisbackbaldbarnbeltbetabiasblue" +
        "bodybragbrewbulbbuzzcalmcashcatschefcityclawcodecolacookcostcruxcurlcuspcyandarkdatadaysdelidice" +
        "dietdoordowndrawdropdrumdulldutyeacheasyechoedgeepicevenexamexiteyesfactfairfernfigsfilmfishfizz" +
        "flapflewfluxfoxyfreefrogfuelfundgalagamegeargemsgiftgirlglowgoodgraygrimgurugushgyrohalfhanghard" +
        "hawkheathelphighhillholyhopehornhutsicedideaidleinchinkyintoirisironitemjadejazzjoinjoltjowljudo" +
        "jugsjumpjunkjurykeepkenokeptkeyskickkilnkingkitekiwiknoblamblavalazyleaflegsliarlimplionlistlogo" +
        "loudloveluaulucklungmainmanymathmazememomenumeowmildmintmissmonknailnavyneednewsnextnoonnotenumb" +
        "obeyoboeomitonyxopenovalowlspaidpartpeckplaypluspoempoolposepuffpumapurrquadquizraceramprealredo" +
        "richroadrockroofrubyruinrunsrustsafesagascarsetssilkskewslotsoapsolosongstubsurfswantacotasktaxi" +
        "tenttiedtimetinytoiltombtoystriptunatwinuglyundouniturgeuservastveryvetovialvibeviewvisavoidvows" +
        "wallwandwarmwaspwavewaxywebswhatwhenwhizwolfworkyankyawnyellyogayurtzapszerozestzinczonezoom"

private fun bytewordAt(index: Int): String {
    val start = index * 4
    return BYTEWORDS.substring(start, start + 4)
}

private fun minimalBytewordAt(index: Int): String {
    val w = bytewordAt(index)
    return "${w[0]}${w[3]}"
}

private val BYTEWORDS_MINIMAL_LOOKUP: IntArray by lazy {
    val dim = 26
    val table = IntArray(dim * dim) { -1 }
    for (i in 0 until 256) {
        val w = bytewordAt(i)
        val x = w[0] - 'a'
        val y = w[3] - 'a'
        table[y * dim + x] = i
    }
    table
}

private fun bytewordsEncodeMinimal(bytes: ByteArray): String {
    val withCrc = bytes + crc32Bytes(bytes)
    val sb = StringBuilder(withCrc.size * 2)
    for (b in withCrc) sb.append(minimalBytewordAt(b.toInt() and 0xFF))
    return sb.toString()
}

private fun bytewordsDecodeMinimal(input: String): ByteArray {
    require(input.length % 2 == 0) { "invalid bytewords length" }
    val dim = 26
    val bytes = ByteArray(input.length / 2)
    for (i in bytes.indices) {
        val x = input[i * 2].lowercaseChar() - 'a'
        val y = input[i * 2 + 1].lowercaseChar() - 'a'
        require(x in 0 until dim && y in 0 until dim) { "invalid byteword at offset $i" }
        val value = BYTEWORDS_MINIMAL_LOOKUP[y * dim + x]
        require(value != -1) { "invalid byteword at offset $i" }
        bytes[i] = value.toByte()
    }
    require(bytes.size >= 5) { "invalid bytewords: too short" }
    val body = bytes.copyOfRange(0, bytes.size - 4)
    val crc = bytes.copyOfRange(bytes.size - 4, bytes.size)
    require(crc.contentEquals(crc32Bytes(body))) { "invalid bytewords checksum" }
    return body
}

// ---------------------------------------------------------------------------------------------
// Minimal CBOR: byte strings, unsigned ints, and the fixed-shape 5-element fountain-part array.
// ---------------------------------------------------------------------------------------------

private fun cborHeader(
    majorType: Int,
    value: Long,
): ByteArray {
    val typeBits = majorType shl 5
    return when {
        value < 24 -> byteArrayOf((typeBits or value.toInt()).toByte())
        value < 256 -> byteArrayOf((typeBits or 24).toByte(), value.toByte())
        value < 65536 -> byteArrayOf((typeBits or 25).toByte(), (value shr 8).toByte(), value.toByte())
        else ->
            byteArrayOf(
                (typeBits or 26).toByte(),
                (value shr 24).toByte(),
                (value shr 16).toByte(),
                (value shr 8).toByte(),
                value.toByte(),
            )
    }
}

private fun cborEncodeByteString(bytes: ByteArray): ByteArray = cborHeader(2, bytes.size.toLong()) + bytes

private fun cborEncodeUInt(value: Long): ByteArray = cborHeader(0, value)

private fun cborEncodeFountainPart(part: FountainEncoderPart): ByteArray =
    cborHeader(4, 5) +
        cborEncodeUInt(part.seqNum) +
        cborEncodeUInt(part.seqLength.toLong()) +
        cborEncodeUInt(part.messageLength.toLong()) +
        cborEncodeUInt(part.checksum) +
        cborEncodeByteString(part.fragment)

private class CborReader(
    private val data: ByteArray,
) {
    private var pos = 0

    private fun readByte(): Int = data[pos++].toInt() and 0xFF

    fun readHeader(): Pair<Int, Int> {
        val b = readByte()
        return (b shr 5) to (b and 0x1F)
    }

    fun readLength(additionalInfo: Int): Long =
        when {
            additionalInfo < 24 -> additionalInfo.toLong()
            additionalInfo == 24 -> readByte().toLong()
            additionalInfo == 25 -> (readByte().toLong() shl 8) or readByte().toLong()
            additionalInfo == 26 -> {
                var v = 0L
                repeat(4) { v = (v shl 8) or readByte().toLong() }
                v
            }
            else -> error("unsupported CBOR additional info: $additionalInfo")
        }

    fun readBytes(length: Int): ByteArray {
        val out = data.copyOfRange(pos, pos + length)
        pos += length
        return out
    }

    fun readUInt(): Long {
        val (majorType, info) = readHeader()
        require(majorType == 0) { "expected CBOR unsigned int, got major type $majorType" }
        return readLength(info)
    }

    fun readByteString(): ByteArray {
        val (majorType, info) = readHeader()
        require(majorType == 2) { "expected CBOR byte string, got major type $majorType" }
        return readBytes(readLength(info).toInt())
    }
}

private fun cborDecodeByteString(bytes: ByteArray): ByteArray = CborReader(bytes).readByteString()

private data class DecodedFountainPart(
    val seqLength: Long,
    val messageLength: Long,
    val checksum: Long,
    val fragment: ByteArray,
)

private fun cborDecodeFountainPart(bytes: ByteArray): DecodedFountainPart {
    val reader = CborReader(bytes)
    val (majorType, info) = reader.readHeader()
    require(majorType == 4) { "expected CBOR array, got major type $majorType" }
    require(reader.readLength(info) == 5L) { "expected 5-element fountain-part array" }
    reader.readUInt() // seqNum (caller already knows it from the UR sequence component)
    val seqLength = reader.readUInt()
    val messageLength = reader.readUInt()
    val checksum = reader.readUInt()
    val fragment = reader.readByteString()
    return DecodedFountainPart(seqLength, messageLength, checksum, fragment)
}
