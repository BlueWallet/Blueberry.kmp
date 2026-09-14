package io.bluewallet.blueberry.wallet

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MnemonicChangeTest {
    @Test
    fun first_unused_index_fills_holes_then_increments() {
        assertEquals(0, firstUnusedIndex(emptyList()))
        assertEquals(2, firstUnusedIndex(listOf(0, 1)))
        assertEquals(1, firstUnusedIndex(listOf(0, 2)))
        assertEquals(MAX_WATCH_COUNT - 1, firstUnusedIndex((0 until MAX_WATCH_COUNT).toList()))
    }

    @Test
    fun empty_mnemonic_wallet_still_samples_all_hd_script_types() {
        val wallet = deriveWatchWallet(ABANDON, WatchGaps(1, 1)).copy(addresses = emptyList())
        val used = mapOf(AddressScriptType.P2TR to listOf(0, 1))
        val random =
            object : Random() {
                override fun nextBits(bitCount: Int): Int = 0

                override fun nextInt(until: Int): Int {
                    assertEquals(HD_SCRIPT_TYPES.size, until)
                    return HD_SCRIPT_TYPES.indexOf(AddressScriptType.P2TR)
                }
            }

        val pick = pickMnemonicChange(wallet, used, random)

        assertEquals(AddressScriptType.P2TR, pick.scriptType)
        assertEquals(firstUnusedIndex(used.getValue(AddressScriptType.P2TR)), pick.index)
        assertNull(pick.address)
    }

    @Test
    fun zpub_wallet_always_uses_native_segwit_change() {
        val zpubWallet = deriveWatchWallet(BLUE_ZPUB, WatchGaps(1, 1))
        val otherType =
            deriveWatchWallet(ABANDON, WatchGaps(1, 1))
                .addresses
                .first { it.resolvedScriptType() == AddressScriptType.P2TR }
        val wallet = zpubWallet.copy(addresses = zpubWallet.addresses + otherType)

        repeat(10) { seed ->
            val pick = pickMnemonicChange(wallet, emptyMap(), Random(seed))
            assertEquals(AddressScriptType.P2WPKH, pick.scriptType)
        }
    }

    @Test
    fun pick_uses_injected_random_and_that_type_internal_index() {
        val wallet = deriveWatchWallet(ABANDON, WatchGaps(2, 2))
        val used =
            mapOf(
                AddressScriptType.P2PKH to listOf(0),
            )
        val pick0 = pickMnemonicChange(wallet, used, Random(0))
        val expectedType = HD_SCRIPT_TYPES[Random(0).nextInt(HD_SCRIPT_TYPES.size)]
        assertEquals(expectedType, pick0.scriptType)
        val expectedIndex = firstUnusedIndex(used[expectedType] ?: emptyList())
        assertEquals(expectedIndex, pick0.index)
        assertEquals(expectedType, pick0.address!!.resolvedScriptType())
        assertEquals(true, pick0.address!!.change)
        assertEquals(expectedIndex, pick0.address!!.index)
    }

    @Test
    fun pick_past_window_returns_null_address_and_next_index() {
        val wallet = deriveWatchWallet(ABANDON, WatchGaps(1, 1))
        val used = mapOf(AddressScriptType.P2TR to listOf(0))
        val pick =
            pickMnemonicChange(
                wallet,
                used,
                object : Random() {
                    override fun nextBits(bitCount: Int): Int = 0

                    override fun nextInt(until: Int): Int = HD_SCRIPT_TYPES.indexOf(AddressScriptType.P2TR)
                },
            )
        assertEquals(AddressScriptType.P2TR, pick.scriptType)
        assertEquals(1, pick.index)
        assertNull(pick.address)
    }
}
