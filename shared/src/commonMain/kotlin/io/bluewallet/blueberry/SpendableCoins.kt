package io.bluewallet.blueberry

enum class CoinAvailability {
    NoCoins,
    AllFrozen,

    /** Nothing is spendable and every unfrozen coin is held by a payment that has not confirmed; none is frozen. */
    AllSending,

    /** Nothing is spendable: some coins are frozen and the rest are held by a payment that has not confirmed. */
    FrozenOrSending,
    Spendable,
}

internal const val ALL_FROZEN_NOTICE = "Every coin is frozen. Unfreeze one in Coin control to spend it."
internal const val ALL_SENDING_NOTICE =
    "Every coin is in a payment that hasn't confirmed. They come back if it fails or is forgotten, and its change can be spent once it confirms."
internal const val FROZEN_OR_SENDING_NOTICE =
    "Every coin is frozen or in a payment that hasn't confirmed. Unfreeze one in Coin control, or wait for that payment."

/** [sendingCount] counts unfrozen coins held by a payment that is still sending. */
internal fun coinAvailability(
    coinCount: Int,
    frozenCount: Int,
    sendingCount: Int = 0,
): CoinAvailability =
    when {
        coinCount == 0 -> CoinAvailability.NoCoins
        frozenCount >= coinCount -> CoinAvailability.AllFrozen
        frozenCount + sendingCount < coinCount -> CoinAvailability.Spendable
        frozenCount == 0 -> CoinAvailability.AllSending
        else -> CoinAvailability.FrozenOrSending
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

/** Why nothing can be spent, or null when something can. */
internal fun unavailableNotice(availability: CoinAvailability): String? =
    when (availability) {
        CoinAvailability.AllFrozen -> ALL_FROZEN_NOTICE
        CoinAvailability.AllSending -> ALL_SENDING_NOTICE
        CoinAvailability.FrozenOrSending -> FROZEN_OR_SENDING_NOTICE
        CoinAvailability.NoCoins, CoinAvailability.Spendable -> null
    }

/** [storedFrozen] is the persisted freeze list; it wins even if the snapshot row is stale. */
internal fun spendableSelectedKeys(
    utxos: List<WalletUtxoRow>,
    keys: Set<String>,
    storedFrozen: Set<String> = emptySet(),
): List<String> = utxos.filter { it.key in keys && !it.isFrozen && it.key !in storedFrozen }.map { it.key }

internal fun sendCoinPrompt(availability: CoinAvailability): String =
    when (availability) {
        CoinAvailability.NoCoins -> "No coins to spend yet."
        CoinAvailability.Spendable -> "Choose which coins this payment spends."
        else -> unavailableNotice(availability).orEmpty()
    }
