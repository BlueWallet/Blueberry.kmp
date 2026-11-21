package io.bluewallet.blueberry

import io.bluewallet.blueberry.headers.nowMillis
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.wallet.Wallet
import io.bluewallet.blueberry.wallet.canonicalBitcoinAddress

/**
 * Persist a label for one of our addresses. Trim; empty clears. Marks labels dirty for sync and
 * refreshes the tx list. Returns false and does nothing if the stored label already matches
 * and the key is already canonical. Bech32 keys are stored in lowercase so a pasted uppercase
 * address still matches. Unchanged text still collapses aliases onto that key.
 */
fun setAddressLabel(
    db: Database,
    wallet: Wallet?,
    walletTxsStore: WalletTxsStore,
    address: String,
    label: String,
): Boolean {
    val key = canonicalBitcoinAddress(address)
    val trimmed = label.trim()
    val aliases = addressLabelAliases(db, key)
    val current = db.addressLabels.get(key) ?: aliases.firstOrNull()?.label
    val needsCollapse = aliases.any { it.address != key }
    if (current == trimmed.ifEmpty { null } && !needsCollapse) return false
    editLabels(db) {
        db.transaction {
            aliases.forEach { db.addressLabels.delete(it.address) }
            if (trimmed.isEmpty()) {
                db.addressLabels.delete(key)
            } else {
                db.addressLabels.upsert(key, trimmed)
            }
        }
    }
    val at = nowMillis()
    walletTxsStore.applyFromDb(db, snapshotFromDb(db, at, at, wallet, walletTxsStore.get().linkageCache()))
    return true
}

/** Label stored for [address], accepting either the pasted form or the canonical bech32 key. */
fun addressLabel(
    db: Database,
    address: String,
): String? {
    val key = canonicalBitcoinAddress(address)
    return db.addressLabels.get(key) ?: addressLabelAliases(db, key).firstOrNull()?.label
}

private fun addressLabelAliases(
    db: Database,
    key: String,
) = db.addressLabels.list().filter { canonicalBitcoinAddress(it.address) == key }
