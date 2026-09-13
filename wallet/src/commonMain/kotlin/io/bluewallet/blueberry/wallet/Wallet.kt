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

    fun gaps(): WatchGaps

    fun peekGaps(): WatchGaps

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
        saveWatchGaps(db, WatchGaps(n, n))
    }

    var currentGaps = loadWatchGaps(db)
    var current = deriveWatchWallet(secret, currentGaps)
    log(
        "wallet",
        "ready kind=${current.kind} external=${currentGaps.external} internal=${currentGaps.internal}",
    )

    val syncFromDbImpl: () -> SyncFromDbResult = {
        val gaps = loadWatchGaps(db)
        val grew =
            gaps.external != currentGaps.external ||
                gaps.internal != currentGaps.internal
        if (grew) {
            currentGaps = gaps
            current = deriveWatchWallet(secret, currentGaps)
            log(
                "wallet",
                "gaps grew external=${gaps.external} internal=${gaps.internal}",
            )
        }
        SyncFromDbResult(grew)
    }

    return object : Wallet {
        override fun snapshot(): WatchWallet = current

        override fun scripts(): List<ByteArray> = current.scripts

        override fun gaps(): WatchGaps = currentGaps

        override fun peekGaps(): WatchGaps = loadWatchGaps(db)

        override fun refresh(): WatchWallet {
            syncFromDbImpl()
            return current
        }

        override fun syncFromDb(): SyncFromDbResult = syncFromDbImpl()
    }
}
