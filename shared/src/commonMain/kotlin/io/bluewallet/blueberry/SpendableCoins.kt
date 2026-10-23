package io.bluewallet.blueberry

enum class CoinAvailability {
    NoCoins,
    AllFrozen,

    /** Nothing is spendable and at least one coin is held by a payment that is still sending. */
    AllSending,
    Spendable,
}

internal const val ALL_FROZEN_NOTICE = "Every coin is frozen. Unfreeze one in Coin control to spend it."
internal const val ALL_SENDING_NOTICE =
    "Every coin is in a payment that is still sending. They come back if it fails, and its change can be spent once it confirms."

/** [sendingCount] counts unfrozen coins held by a payment that is still sending. */
internal fun coinAvailability(
    coinCount: Int,
    frozenCount: Int,
    sendingCount: Int = 0,
): CoinAvailability =
    when {
        coinCount == 0 -> CoinAvailability.NoCoins
        frozenCount >= coinCount -> CoinAvailability.AllFrozen
        frozenCount + sendingCount >= coinCount -> CoinAvailability.AllSending
        else -> CoinAvailability.Spendable
    }

internal fun coinAvailability(
    utxos: List<WalletUtxoRow>,
    held: Set<String> = emptySet(),
): CoinAvailability =
    coinAvailability(
        coinCount = utxos.size,
        frozenCount = utxos.count { it.isFrozen },
        sendingCount = utxos.count { !it.isFrozen && it.key in held },
    )

/** [storedFrozen] is the persisted freeze list; it wins even if the snapshot row is stale. */
internal fun spendableSelectedKeys(
    utxos: List<WalletUtxoRow>,
    keys: Set<String>,
    storedFrozen: Set<String> = emptySet(),
): List<String> = utxos.filter { it.key in keys && !it.isFrozen && it.key !in storedFrozen }.map { it.key }

internal fun sendCoinPrompt(availability: CoinAvailability): String =
    when (availability) {
        CoinAvailability.NoCoins -> "No coins to spend yet."
        CoinAvailability.AllFrozen -> ALL_FROZEN_NOTICE
        CoinAvailability.AllSending -> ALL_SENDING_NOTICE
        CoinAvailability.Spendable -> "Choose which coins this payment spends."
    }
