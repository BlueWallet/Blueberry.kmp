package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals

class SpendableCoinsTest {
    @Test
    fun availability_names_an_all_frozen_wallet() {
        assertEquals(CoinAvailability.NoCoins, coinAvailability(coinCount = 0, frozenCount = 0))
        assertEquals(CoinAvailability.AllFrozen, coinAvailability(coinCount = 3, frozenCount = 3))
        assertEquals(CoinAvailability.Spendable, coinAvailability(coinCount = 3, frozenCount = 2))
    }

    @Test
    fun send_prompt_says_the_wallet_is_locked_when_everything_is_frozen() {
        assertEquals(ALL_FROZEN_NOTICE, sendCoinPrompt(CoinAvailability.AllFrozen))
        assertEquals("Choose which coins this payment spends.", sendCoinPrompt(CoinAvailability.Spendable))
        assertEquals("No coins to spend yet.", sendCoinPrompt(CoinAvailability.NoCoins))
    }
}
