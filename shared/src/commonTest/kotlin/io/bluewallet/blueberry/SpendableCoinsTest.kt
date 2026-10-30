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
    fun availability_names_a_wallet_whose_coins_are_all_sending() {
        assertEquals(CoinAvailability.AllSending, coinAvailability(coinCount = 2, frozenCount = 0, sendingCount = 2))
        assertEquals(CoinAvailability.Spendable, coinAvailability(coinCount = 3, frozenCount = 0, sendingCount = 2))
        assertEquals(ALL_SENDING_NOTICE, sendCoinPrompt(CoinAvailability.AllSending))
    }

    @Test
    fun frozen_coins_are_not_called_sending() {
        assertEquals(CoinAvailability.FrozenOrSending, coinAvailability(coinCount = 3, frozenCount = 1, sendingCount = 2))
        assertEquals(FROZEN_OR_SENDING_NOTICE, sendCoinPrompt(CoinAvailability.FrozenOrSending))
        assertEquals(FROZEN_OR_SENDING_NOTICE, noSpendableNotice(0, CoinAvailability.FrozenOrSending))
        assertEquals("Every coin is frozen or sending", amountContinueLabel("0.5", 0, CoinAvailability.FrozenOrSending))
    }

    @Test
    fun send_prompt_says_the_wallet_is_locked_when_everything_is_frozen() {
        assertEquals(ALL_FROZEN_NOTICE, sendCoinPrompt(CoinAvailability.AllFrozen))
        assertEquals("Choose which coins this payment spends.", sendCoinPrompt(CoinAvailability.Spendable))
        assertEquals("No coins to spend yet.", sendCoinPrompt(CoinAvailability.NoCoins))
    }
}
