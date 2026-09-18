package io.bluewallet.blueberry.boot

import io.bluewallet.blueberry.storage.Database

const val ALWAYS_SHOW_SYNC_PROGRESS_KEY = "always_show_sync_progress"

fun loadAlwaysShowSyncProgress(db: Database): Boolean = db.keyValue.get(ALWAYS_SHOW_SYNC_PROGRESS_KEY) == "1"

fun saveAlwaysShowSyncProgress(
    db: Database,
    alwaysShow: Boolean,
) {
    db.keyValue.set(ALWAYS_SHOW_SYNC_PROGRESS_KEY, if (alwaysShow) "1" else "0")
}
