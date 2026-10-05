@file:OptIn(ExperimentalAtomicApi::class)

package io.bluewallet.blueberry

import fr.acinq.bitcoin.MnemonicCode
import io.bluewallet.blueberry.labels.PaymentLabel
import io.bluewallet.blueberry.labels.UtxoLabel
import io.bluewallet.blueberry.labels.WalletLabels
import io.bluewallet.blueberry.labels.encryptWalletLabels
import io.bluewallet.blueberry.labels.extendedFingerprint
import io.bluewallet.blueberry.labels.labelingAccountKey
import io.bluewallet.blueberry.labels.storedWalletLabels
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.TxPaymentLabelRow
import io.bluewallet.blueberry.wallet.WalletSecretKind
import io.bluewallet.blueberry.wallet.loadWalletSecret
import io.bluewallet.blueberry.wallet.parseWalletSecret
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.encoding.Base64

internal const val LABEL_STORE_ORIGIN = "https://bytes.bluewallet.io:8445"
internal const val LABEL_STORE_KEY = "labels"
internal const val LABELS_REMOTE_SEQ_KEY = "labels_remote_seq"
internal const val LABELS_DIRTY_KEY = "labels_dirty"

private val labelSync = Mutex()

/** Held only around label writes and the dirty-flag update. Network IO stays outside it. */
private val labelWrite = Mutex()
private val labelEpoch = AtomicInt(0)

suspend fun publishWalletLabels(
    db: Database,
    post: suspend (namespace: String, body: String) -> String = ::postLabelBlob,
) {
    withContext(Dispatchers.Default) {
        labelSync.withLock { uploadUntilCaughtUp(db, post) }
    }
}

internal expect suspend fun postLabelBlob(
    namespace: String,
    body: String,
): String

internal expect suspend fun getLabelStore(path: String): String

fun editLabels(
    db: Database,
    edit: () -> Unit,
) {
    runBlocking {
        labelWrite.withLock {
            labelEpoch.fetchAndAdd(1)
            db.keyValue.set(LABELS_DIRTY_KEY, "1")
            edit()
        }
    }
}

suspend fun restoreWalletLabelsOnColdStart(
    db: Database,
    post: suspend (namespace: String, body: String) -> String = ::postLabelBlob,
    get: suspend (path: String) -> String = ::getLabelStore,
): Boolean =
    withContext(Dispatchers.Default) {
        labelSync.withLock {
            val mnemonic = mnemonicOrNull(db)
            if (mnemonic == null) {
                false
            } else if (db.keyValue.get(LABELS_DIRTY_KEY) == "1") {
                uploadUntilCaughtUp(db, post)
                false
            } else {
                pullLabelsIfRemoteAhead(db, mnemonic, get)
            }
        }
    }

private suspend fun uploadUntilCaughtUp(
    db: Database,
    post: suspend (namespace: String, body: String) -> String,
) {
    val mnemonic = mnemonicOrNull(db) ?: return
    val seed = MnemonicCode.toSeed(mnemonic, "")
    val namespace = fingerprintHex(extendedFingerprint(seed))
    val accountKey = labelingAccountKey(seed)
    var caughtUp = false
    while (!caughtUp) {
        val (seen, labels) =
            labelWrite.withLock {
                labelEpoch.load() to
                    WalletLabels(
                        utxoNames = db.utxoNames.list().map { UtxoLabel(it.outpoint, it.name) },
                        paymentLabels = db.txPaymentLabels.list().map { PaymentLabel(it.txid, it.label) },
                    )
            }
        val seq = post(namespace, Base64.encode(encryptWalletLabels(accountKey, labels)))
        require(seq.toLongOrNull() != null) { "label upload bad sequence" }
        labelWrite.withLock {
            caughtUp = labelEpoch.load() == seen
            if (caughtUp) {
                db.keyValue.set(LABELS_REMOTE_SEQ_KEY, seq)
                db.keyValue.set(LABELS_DIRTY_KEY, "0")
            }
        }
    }
}

private suspend fun pullLabelsIfRemoteAhead(
    db: Database,
    mnemonic: String,
    get: suspend (path: String) -> String,
): Boolean {
    val seed = MnemonicCode.toSeed(mnemonic, "")
    val namespace = fingerprintHex(extendedFingerprint(seed))
    val epochAtStart = labelEpoch.load()
    val remoteSeq = get("namespaceseq/$namespace").trim().toLongOrNull()
    val localSeq = db.keyValue.get(LABELS_REMOTE_SEQ_KEY)?.toLongOrNull() ?: 0L
    val labels =
        if (remoteSeq != null && remoteSeq > localSeq) {
            storedWalletLabels(
                labelingAccountKey(seed),
                Base64.decode(get("namespace/$namespace/$LABEL_STORE_KEY")),
            )
        } else {
            null
        }
    var replaced = false
    if (labels != null && remoteSeq != null) {
        labelWrite.withLock {
            val clean = db.keyValue.get(LABELS_DIRTY_KEY) != "1" && labelEpoch.load() == epochAtStart
            if (clean) {
                db.transaction {
                    db.utxoNames.list().forEach { db.utxoNames.delete(it.outpoint) }
                    db.txPaymentLabels.list().forEach { db.txPaymentLabels.delete(it.txid) }
                    labels.utxoNames.forEach { db.utxoNames.upsert(it.outpoint, it.name) }
                    labels.paymentLabels.forEach { row ->
                        db.txPaymentLabels.upsert(TxPaymentLabelRow(row.txid, row.label))
                    }
                    db.keyValue.set(LABELS_REMOTE_SEQ_KEY, remoteSeq.toString())
                }
                replaced = true
            }
        }
    }
    return replaced
}

fun uploadLabels(
    scope: CoroutineScope,
    db: Database,
) {
    scope.launch {
        runCatching { publishWalletLabels(db) }
    }
}

private fun mnemonicOrNull(db: Database): String? {
    val parsed = runCatching { parseWalletSecret(loadWalletSecret(db)) }.getOrNull()
    return if (parsed?.kind == WalletSecretKind.MNEMONIC) parsed.value else null
}

private fun fingerprintHex(bytes: ByteArray): String =
    bytes.joinToString("") { byte ->
        val value = byte.toInt() and 0xff
        val hex = "0123456789abcdef"
        "${hex[value shr 4]}${hex[value and 0x0f]}"
    }
