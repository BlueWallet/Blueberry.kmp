package io.bluewallet.blueberry

import fr.acinq.bitcoin.MnemonicCode
import io.bluewallet.blueberry.labels.PaymentLabel
import io.bluewallet.blueberry.labels.UtxoLabel
import io.bluewallet.blueberry.labels.WalletLabels
import io.bluewallet.blueberry.labels.decryptWalletLabels
import io.bluewallet.blueberry.labels.extendedFingerprint
import io.bluewallet.blueberry.labels.labelingAccountKey
import io.bluewallet.blueberry.storage.TxPaymentLabelRow
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.saveWalletSecret
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PublishWalletLabelsTest {
    @Test
    fun mnemonic_posts_every_label_under_the_extended_fingerprint() =
        runPublish { db, posts ->
            val mnemonic =
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
            saveWalletSecret(db, mnemonic)
            val txid = "ab".repeat(32)
            db.txPaymentLabels
                .upsert(TxPaymentLabelRow(txid, "rent"))
            db.utxoNames.upsert("$txid:1", "change")

            publishWalletLabels(db) { namespace, body ->
                posts += namespace to body
                "666"
            }

            val seed = MnemonicCode.toSeed(mnemonic, "")
            val (namespace, body) = posts.single()
            assertEquals(fingerprintHex(extendedFingerprint(seed)), namespace)
            assertEquals("666", db.keyValue.get(LABELS_REMOTE_SEQ_KEY))
            assertEquals(
                WalletLabels(
                    utxoNames = listOf(UtxoLabel("$txid:1", "change")),
                    paymentLabels = listOf(PaymentLabel(txid, "rent")),
                ),
                decryptWalletLabels(labelingAccountKey(seed), Base64.decode(body)),
            )
        }

    @Test
    fun edit_during_post_is_uploaded_before_dirty_is_cleared() =
        runPublish { db, posts ->
            val mnemonic =
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
            saveWalletSecret(db, mnemonic)
            val txid = "ab".repeat(32)
            db.txPaymentLabels.upsert(TxPaymentLabelRow(txid, "rent"))
            var postsSeen = 0

            publishWalletLabels(db) { _, body ->
                postsSeen += 1
                if (postsSeen == 1) {
                    editLabels(db) {
                        db.txPaymentLabels.upsert(TxPaymentLabelRow(txid, "rent-again"))
                    }
                }
                posts += "" to body
                "4"
            }

            val seed = MnemonicCode.toSeed(mnemonic, "")
            assertEquals(2, posts.size)
            assertEquals(
                WalletLabels(
                    utxoNames = emptyList(),
                    paymentLabels = listOf(PaymentLabel(txid, "rent-again")),
                ),
                decryptWalletLabels(labelingAccountKey(seed), Base64.decode(posts.last().second)),
            )
            assertEquals("rent-again", db.txPaymentLabels.get(txid)?.label)
            assertEquals("0", db.keyValue.get(LABELS_DIRTY_KEY))
            assertEquals("4", db.keyValue.get(LABELS_REMOTE_SEQ_KEY))
        }

    @Test
    fun watch_only_secret_does_not_post() =
        runPublish { db, posts ->
            saveWalletSecret(
                db,
                "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs",
            )
            db.utxoNames.upsert("aa".repeat(32) + ":0", "cold")

            publishWalletLabels(db) { namespace, body ->
                posts += namespace to body
                "1"
            }

            assertTrue(posts.isEmpty())
        }

    @Test
    fun missing_secret_does_not_post() =
        runPublish { db, posts ->
            publishWalletLabels(db) { namespace, body ->
                posts += namespace to body
                "1"
            }
            assertTrue(posts.isEmpty())
        }
}

private fun runPublish(block: suspend (io.bluewallet.blueberry.storage.Database, MutableList<Pair<String, String>>) -> Unit) {
    kotlinx.coroutines.runBlocking {
        val db = createSqliteDatabase(":memory:")
        val posts = mutableListOf<Pair<String, String>>()
        try {
            block(db, posts)
        } finally {
            db.close()
        }
    }
}

private fun fingerprintHex(bytes: ByteArray): String =
    bytes.joinToString("") { byte ->
        val value = byte.toInt() and 0xff
        val hex = "0123456789abcdef"
        "${hex[value shr 4]}${hex[value and 0x0f]}"
    }
