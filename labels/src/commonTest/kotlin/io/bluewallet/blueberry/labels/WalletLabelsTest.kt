package io.bluewallet.blueberry.labels

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WalletLabelsTest {
    @Test
    fun encrypt_wallet_labels_seals_bip329_lines() {
        val labels =
            WalletLabels(
                utxoNames = listOf(UtxoLabel(OUTPOINT, "say \"hi\"")),
                paymentLabels = listOf(PaymentLabel(TXID, "rent")),
            )
        val payload = encryptWalletLabels(ACCOUNT_KEY, labels)
        val plaintext = decryptMetadataFile(ACCOUNT_KEY, payload).decodeToString()

        assertEquals(
            """
            {"type":"output","ref":"$OUTPOINT","label":"say \"hi\""}
            {"type":"tx","ref":"$TXID","label":"rent"}
            """.trimIndent(),
            plaintext,
        )
    }

    @Test
    fun decrypt_wallet_labels_returns_rows_for_upsert() {
        val plaintext =
            """
            {"type":"output","ref":"$OUTPOINT","label":"cold storage"}
            {"type":"tx","ref":"$TXID","label":"rent"}
            """.trimIndent()
        val payload = encryptMetadataFile(ACCOUNT_KEY, plaintext.encodeToByteArray())

        assertEquals(
            WalletLabels(
                utxoNames = listOf(UtxoLabel(OUTPOINT, "cold storage")),
                paymentLabels = listOf(PaymentLabel(TXID, "rent")),
            ),
            decryptWalletLabels(ACCOUNT_KEY, payload),
        )
    }

    @Test
    fun decrypt_wallet_labels_skips_bad_lines_and_keeps_the_rest() {
        val plaintext =
            """
            {"type":"output","ref":"$OUTPOINT","label":"keep"}
            {not json
            {"type":"addr","ref":"1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa","label":"skip"}
            {"type":"tx","ref":"$TXID"}
            {"type":"tx","ref":"$TXID","label":"rent"}
            """.trimIndent()
        val payload = encryptMetadataFile(ACCOUNT_KEY, plaintext.encodeToByteArray())

        assertEquals(
            WalletLabels(
                utxoNames = listOf(UtxoLabel(OUTPOINT, "keep")),
                paymentLabels = listOf(PaymentLabel(TXID, "rent")),
            ),
            decryptWalletLabels(ACCOUNT_KEY, payload),
        )
    }

    @Test
    fun stored_wallet_labels_keeps_a_blank_document_as_an_empty_set() {
        val payload = encryptWalletLabels(ACCOUNT_KEY, WalletLabels(emptyList(), emptyList()))

        assertEquals(WalletLabels(emptyList(), emptyList()), storedWalletLabels(ACCOUNT_KEY, payload))
    }

    @Test
    fun stored_wallet_labels_rejects_a_non_blank_file_with_no_rows() {
        val payload =
            encryptMetadataFile(
                ACCOUNT_KEY,
                """{"accountLabel":"Saving account"}""".encodeToByteArray(),
            )

        assertEquals(null, storedWalletLabels(ACCOUNT_KEY, payload))
    }

    @Test
    fun decrypt_wallet_labels_rejects_a_tampered_payload() {
        val payload = encryptWalletLabels(ACCOUNT_KEY, WalletLabels(emptyList(), emptyList())).copyOf()
        payload[payload.lastIndex] = (payload[payload.lastIndex].toInt() xor 0x01).toByte()

        assertFailsWith<IllegalArgumentException> {
            decryptWalletLabels(ACCOUNT_KEY, payload)
        }
    }
}

private const val ACCOUNT_KEY = "v5kCxSKLTsnwmgPBeaRyFDWeG9zXouF34L72763zjLrS4LWy8"
private const val TXID = "350eebc1012ce2339b71b5fca317a0d174abc3a633684bc65a71845deb596539"
private const val OUTPOINT = "$TXID:0"
