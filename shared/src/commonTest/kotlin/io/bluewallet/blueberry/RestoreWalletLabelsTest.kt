package io.bluewallet.blueberry

import fr.acinq.bitcoin.MnemonicCode
import io.bluewallet.blueberry.labels.UtxoLabel
import io.bluewallet.blueberry.labels.WalletLabels
import io.bluewallet.blueberry.labels.encryptMetadataFile
import io.bluewallet.blueberry.labels.encryptWalletLabels
import io.bluewallet.blueberry.labels.labelingAccountKey
import io.bluewallet.blueberry.storage.TxPaymentLabelRow
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.saveWalletSecret
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestoreWalletLabelsTest {
    @Test
    fun remote_ahead_replaces_local_labels_and_stores_seq() =
        runRestore { db, posts, gets ->
            val mnemonic = ABANDON
            saveWalletSecret(db, mnemonic)
            val txid = "ab".repeat(32)
            val out = "$txid:0"
            db.utxoNames.upsert(out, "local-only")
            db.txPaymentLabels.upsert(TxPaymentLabelRow(txid, "old"))
            db.keyValue.set(LABELS_REMOTE_SEQ_KEY, "1")
            val remote =
                WalletLabels(
                    utxoNames = listOf(UtxoLabel(out, "from-remote")),
                    paymentLabels = emptyList(),
                )
            val blob = remoteBlob(mnemonic, remote)

            val restored =
                restoreWalletLabelsOnColdStart(db, post = { _, _ -> error("no post") }) { path ->
                    gets += path
                    if (path.startsWith("namespaceseq/")) "5" else blob
                }

            assertEquals(true, restored)
            assertEquals("from-remote", db.utxoNames.get(out))
            assertNull(db.txPaymentLabels.get(txid))
            assertEquals("5", db.keyValue.get(LABELS_REMOTE_SEQ_KEY))
            assertTrue(posts.isEmpty())
            assertEquals(2, gets.size)
        }

    @Test
    fun dirty_local_edit_uploads_and_does_not_pull() =
        runRestore { db, posts, gets ->
            saveWalletSecret(db, ABANDON)
            val out = "ab".repeat(32) + ":0"
            db.utxoNames.upsert(out, "keep-local")
            db.keyValue.set(LABELS_DIRTY_KEY, "1")
            db.keyValue.set(LABELS_REMOTE_SEQ_KEY, "1")

            val restored =
                restoreWalletLabelsOnColdStart(db, post = { _, _ ->
                    posts += "posted"
                    "9"
                }) { error("no get") }

            assertEquals(false, restored)
            assertEquals("keep-local", db.utxoNames.get(out))
            assertEquals("9", db.keyValue.get(LABELS_REMOTE_SEQ_KEY))
            assertEquals("0", db.keyValue.get(LABELS_DIRTY_KEY))
            assertEquals(listOf("posted"), posts)
            assertTrue(gets.isEmpty())
        }

    @Test
    fun non_bip329_blob_does_not_wipe_local_labels() =
        runRestore { db, _, gets ->
            saveWalletSecret(db, ABANDON)
            val out = "ab".repeat(32) + ":0"
            db.utxoNames.upsert(out, "keep")
            db.keyValue.set(LABELS_REMOTE_SEQ_KEY, "1")
            val blob =
                Base64.encode(
                    encryptMetadataFile(
                        labelingAccountKey(MnemonicCode.toSeed(ABANDON, "")),
                        """{"accountLabel":"Saving account"}""".encodeToByteArray(),
                    ),
                )

            val restored =
                restoreWalletLabelsOnColdStart(db, post = { _, _ -> error("no post") }) { path ->
                    gets += path
                    if (path.startsWith("namespaceseq/")) "5" else blob
                }

            assertEquals(false, restored)
            assertEquals("keep", db.utxoNames.get(out))
            assertEquals("1", db.keyValue.get(LABELS_REMOTE_SEQ_KEY))
            assertEquals(2, gets.size)
        }

    @Test
    fun edit_during_blob_download_is_not_replaced() =
        runRestore { db, _, _ ->
            saveWalletSecret(db, ABANDON)
            db.keyValue.set(LABELS_REMOTE_SEQ_KEY, "1")
            val remoteOut = "aa".repeat(32) + ":0"
            val blob =
                remoteBlob(
                    ABANDON,
                    WalletLabels(utxoNames = listOf(UtxoLabel(remoteOut, "remote")), paymentLabels = emptyList()),
                )
            val kept = "bb".repeat(32) + ":1"

            val restored =
                restoreWalletLabelsOnColdStart(db, post = { _, _ -> error("no post") }) { path ->
                    if (!path.startsWith("namespaceseq/")) {
                        editLabels(db) { db.utxoNames.upsert(kept, "typed-during-download") }
                    }
                    if (path.startsWith("namespaceseq/")) "5" else blob
                }

            assertEquals(false, restored)
            assertEquals("typed-during-download", db.utxoNames.get(kept))
            assertNull(db.utxoNames.get(remoteOut))
            assertEquals("1", db.keyValue.get(LABELS_REMOTE_SEQ_KEY))
            assertEquals("1", db.keyValue.get(LABELS_DIRTY_KEY))
        }

    @Test
    fun same_seq_does_not_download_the_blob() =
        runRestore { db, _, gets ->
            saveWalletSecret(db, ABANDON)
            db.keyValue.set(LABELS_REMOTE_SEQ_KEY, "4")

            val restored =
                restoreWalletLabelsOnColdStart(db, post = { _, _ -> error("no post") }) { path ->
                    gets += path
                    "4"
                }

            assertEquals(false, restored)
            assertEquals(1, gets.size)
            assertTrue(gets.single().startsWith("namespaceseq/"))
        }
}

private const val ABANDON =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

private fun remoteBlob(
    mnemonic: String,
    labels: WalletLabels,
): String {
    val seed = MnemonicCode.toSeed(mnemonic, "")
    return Base64.encode(encryptWalletLabels(labelingAccountKey(seed), labels))
}

private fun runRestore(
    block: suspend (
        io.bluewallet.blueberry.storage.Database,
        MutableList<String>,
        MutableList<String>,
    ) -> Unit,
) {
    kotlinx.coroutines.runBlocking {
        val db = createSqliteDatabase(":memory:")
        val posts = mutableListOf<String>()
        val gets = mutableListOf<String>()
        try {
            block(db, posts, gets)
        } finally {
            db.close()
        }
    }
}
