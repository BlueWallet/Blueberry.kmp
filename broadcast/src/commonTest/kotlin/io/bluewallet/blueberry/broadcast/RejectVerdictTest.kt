package io.bluewallet.blueberry.broadcast

import io.bluewallet.bip324.transactionId
import kotlin.test.Test
import kotlin.test.assertEquals

private const val REJECT_INVALID = 0x10
private const val REJECT_DUPLICATE = 0x12

/** BIP61 `reject` for [txHex]: message, code, reason, txid. */
internal fun txRejectPayload(
    txHex: String,
    code: Int = REJECT_INVALID,
    message: String = "tx",
    txid: ByteArray = transactionId(decodeBroadcastTx(txHex)),
): ByteArray {
    val msg = message.encodeToByteArray()
    val reason = "bad-txns".encodeToByteArray()
    return byteArrayOf(msg.size.toByte()) + msg + byteArrayOf(code.toByte(), reason.size.toByte()) + reason + txid
}

class RejectVerdictTest {
    private val txid = transactionId(decodeBroadcastTx(sampleTxHex()))

    @Test
    fun an_invalid_tx_reject_refuses() {
        assertEquals(RejectVerdict.Refused, rejectVerdict(txRejectPayload(sampleTxHex()), txid))
    }

    @Test
    fun a_duplicate_reject_means_the_peer_has_it() {
        assertEquals(RejectVerdict.AlreadyHave, rejectVerdict(txRejectPayload(sampleTxHex(), REJECT_DUPLICATE), txid))
    }

    @Test
    fun a_reject_for_another_tx_is_unrelated() {
        val other = ByteArray(32) { 7 }
        assertEquals(RejectVerdict.Unrelated, rejectVerdict(txRejectPayload(sampleTxHex(), txid = other), txid))
    }

    @Test
    fun a_reject_for_another_message_is_unrelated() {
        assertEquals(RejectVerdict.Unrelated, rejectVerdict(txRejectPayload(sampleTxHex(), message = "version"), txid))
    }

    @Test
    fun a_reject_that_does_not_parse_is_unrelated() {
        assertEquals(RejectVerdict.Unrelated, rejectVerdict(byteArrayOf(0), txid))
    }
}
