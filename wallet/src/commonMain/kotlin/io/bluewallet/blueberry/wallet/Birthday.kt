package io.bluewallet.blueberry.wallet

import io.bluewallet.blueberry.storage.Database
import kotlin.math.max

sealed class WalletBirthdayInspection {
    data object None : WalletBirthdayInspection()

    data object Pending : WalletBirthdayInspection()

    data class Ok(
        val height: Int,
    ) : WalletBirthdayInspection()
}

fun markWalletBirthdayPending(db: Database) {
    db.keyValue.set(WALLET_BIRTHDAY_HEIGHT_KEY, WALLET_BIRTHDAY_PENDING)
    log("wallet", "birthday pending")
}

fun inspectWalletBirthday(db: Database): WalletBirthdayInspection {
    val raw = db.keyValue.get(WALLET_BIRTHDAY_HEIGHT_KEY) ?: return WalletBirthdayInspection.None
    if (raw.trim().isEmpty()) return WalletBirthdayInspection.None
    val trimmed = raw.trim()
    if (trimmed == WALLET_BIRTHDAY_PENDING) return WalletBirthdayInspection.Pending
    val height = trimmed.toIntOrNull() ?: return WalletBirthdayInspection.None
    if (height < 0 || height.toString() != trimmed) return WalletBirthdayInspection.None
    return WalletBirthdayInspection.Ok(height)
}

/** Compact-filter scan floor: birthday if set, otherwise the stored header min. */
fun compactFilterFrom(db: Database): Int? {
    val minH = db.headers.minHeight() ?: return null
    val birthday = inspectWalletBirthday(db)
    return when (birthday) {
        is WalletBirthdayInspection.Ok -> max(birthday.height, minH)
        else -> minH
    }
}

/** Freeze pending birthday to [height]. No-op if not pending. Returns whether written. */
fun maybeFreezeWalletBirthday(
    db: Database,
    height: Int,
): Boolean {
    if (height < 0) return false
    if (inspectWalletBirthday(db) != WalletBirthdayInspection.Pending) return false
    db.keyValue.set(WALLET_BIRTHDAY_HEIGHT_KEY, height.toString())
    log("wallet", "birthday height=$height")
    return true
}
