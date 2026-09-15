package io.bluewallet.blueberry.boot

import io.bluewallet.blueberry.storage.Database

const val HOME_DETAILED_SYNC_KEY = "home_detailed_sync"

fun loadHomeDetailedSync(db: Database): Boolean = db.keyValue.get(HOME_DETAILED_SYNC_KEY) == "1"

fun saveHomeDetailedSync(
    db: Database,
    detailed: Boolean,
) {
    db.keyValue.set(HOME_DETAILED_SYNC_KEY, if (detailed) "1" else "0")
}
