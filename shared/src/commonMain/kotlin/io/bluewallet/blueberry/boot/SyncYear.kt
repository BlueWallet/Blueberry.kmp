package io.bluewallet.blueberry.boot

import io.bluewallet.blueberry.storage.Database

const val SYNC_FROM_YEAR_KEY = "sync_from_year"
const val DEFAULT_CHECKPOINT_YEAR = 2019

sealed class SyncFromYearInspection {
    data object Missing : SyncFromYearInspection()

    data class Ok(
        val year: Int,
    ) : SyncFromYearInspection()
}

fun listCheckpointYears(): List<Int> = (2009..2026).toList()

fun latestCheckpointYear(): Int = listCheckpointYears().last()

fun parseSyncFromYear(raw: String?): Int? {
    if (raw == null) return null
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val year = trimmed.toIntOrNull() ?: return null
    if (year.toString() != trimmed) return null
    if (year !in listCheckpointYears()) return null
    return year
}

fun inspectSyncFromYear(db: Database): SyncFromYearInspection {
    val year = parseSyncFromYear(db.keyValue.get(SYNC_FROM_YEAR_KEY))
    return if (year == null) SyncFromYearInspection.Missing else SyncFromYearInspection.Ok(year)
}

fun loadSyncFromYear(db: Database): Int {
    val inspected = inspectSyncFromYear(db)
    if (inspected !is SyncFromYearInspection.Ok) {
        throw IllegalArgumentException("sync_from_year missing or invalid")
    }
    return inspected.year
}

fun saveSyncFromYear(
    db: Database,
    year: Int,
) {
    if (year !in listCheckpointYears()) {
        throw IllegalArgumentException("unknown sync_from_year: $year")
    }
    db.keyValue.set(SYNC_FROM_YEAR_KEY, year.toString())
}
