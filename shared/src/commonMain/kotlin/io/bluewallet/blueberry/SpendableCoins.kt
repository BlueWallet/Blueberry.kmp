package io.bluewallet.blueberry

enum class CoinAvailability {
    NoCoins,
    AllFrozen,
    Spendable,
}

internal const val ALL_FROZEN_NOTICE = "Every coin is frozen. Unfreeze one in Coin control to spend it."

internal fun coinAvailability(
    coinCount: Int,
    frozenCount: Int,
): CoinAvailability =
    when {
        coinCount == 0 -> CoinAvailability.NoCoins
        frozenCount >= coinCount -> CoinAvailability.AllFrozen
        else -> CoinAvailability.Spendable
    }

internal fun coinAvailability(utxos: List<WalletUtxoRow>): CoinAvailability = coinAvailability(coinCount = utxos.size, frozenCount = utxos.count { it.isFrozen })

internal fun sendCoinPrompt(availability: CoinAvailability): String =
    when (availability) {
        CoinAvailability.NoCoins -> "No coins to spend yet."
        CoinAvailability.AllFrozen -> ALL_FROZEN_NOTICE
        CoinAvailability.Spendable -> "Choose which coins this payment spends."
    }
