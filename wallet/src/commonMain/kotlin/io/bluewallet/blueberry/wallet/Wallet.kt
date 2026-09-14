package io.bluewallet.blueberry.wallet

import io.bluewallet.blueberry.storage.Database
import kotlin.math.floor
import kotlin.math.max

data class CreateWalletOptions(
    val secret: String? = null,
    val addressGap: Int? = null,
) {
    override fun toString(): String = "CreateWalletOptions(secret=${if (secret == null) "null" else "[redacted]"}, addressGap=$addressGap)"
}

data class SyncFromDbResult(
    val grew: Boolean,
)

interface Wallet {
    fun snapshot(): WatchWallet

    fun scripts(): List<ByteArray>

    fun gaps(): HdWatchGaps

    fun peekGaps(): HdWatchGaps

    fun refresh(): WatchWallet

    fun syncFromDb(): SyncFromDbResult
}

fun createWallet(
    db: Database,
    options: CreateWalletOptions = CreateWalletOptions(),
): Wallet {
    val raw = options.secret ?: loadWalletSecret(db)
    val secret = parseWalletSecret(raw).value

    if (options.addressGap != null) {
        val n = max(0, floor(options.addressGap.toDouble()).toInt())
        saveHdWatchGaps(db, HdWatchGaps.uniform(WatchGaps(n, n)))
    }

    var currentGaps = loadHdWatchGaps(db)
    var current = deriveWatchWallet(secret, currentGaps)
    log("wallet", "ready kind=${current.kind} gaps=$currentGaps")

    val syncFromDbImpl: () -> SyncFromDbResult = {
        val gaps = loadHdWatchGaps(db)
        val grew = gaps != currentGaps
        if (grew) {
            currentGaps = gaps
            current = deriveWatchWallet(secret, currentGaps)
            log("wallet", "gaps grew gaps=$gaps")
        }
        SyncFromDbResult(grew)
    }

    return object : Wallet {
        override fun snapshot(): WatchWallet = current

        override fun scripts(): List<ByteArray> = current.scripts

        override fun gaps(): HdWatchGaps = currentGaps

        override fun peekGaps(): HdWatchGaps = loadHdWatchGaps(db)

        override fun refresh(): WatchWallet {
            syncFromDbImpl()
            return current
        }

        override fun syncFromDb(): SyncFromDbResult = syncFromDbImpl()
    }
}
