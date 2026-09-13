package io.bluewallet.blueberry.onboarding

import io.bluewallet.blueberry.boot.latestCheckpointYear
import io.bluewallet.blueberry.boot.saveSyncFromYear
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.wallet.markWalletBirthdayPending
import io.bluewallet.blueberry.wallet.saveWalletSecret

fun persistImportedSecret(
    db: Database,
    raw: String,
) {
    saveWalletSecret(db, raw)
}

fun persistCreatedWallet(
    db: Database,
    mnemonic: String,
) {
    saveWalletSecret(db, mnemonic)
    saveSyncFromYear(db, latestCheckpointYear())
    markWalletBirthdayPending(db)
}

fun persistSyncYear(
    db: Database,
    year: Int,
) {
    saveSyncFromYear(db, year)
}
