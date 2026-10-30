package io.bluewallet.blueberry

import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SendFeeMemoryTest {
    @Test
    fun first_run_has_no_rate_then_remembers_the_last_one() {
        val db = createSqliteDatabase(":memory:")
        try {
            assertNull(loadSendFeeRate(db))
            saveSendFeeRate(db, 3.0)
            assertEquals(3.0, loadSendFeeRate(db))
            assertEquals("3", db.keyValue.get(SEND_FEE_RATE_KEY))
            saveSendFeeRate(db, 1.5)
            assertEquals(1.5, loadSendFeeRate(db))
        } finally {
            db.close()
        }
    }

    @Test
    fun coins_button_says_what_is_missing() {
        assertEquals("Pick coins to pay with", sendCoinsButtonLabel(0, SendCoverage(0, 1_000, false)))
        assertEquals("Need 0.00000400 BTC more", sendCoinsButtonLabel(1, SendCoverage(600, 1_000, false)))
        assertEquals("Continue with 1 coin", sendCoinsButtonLabel(1, SendCoverage(2_000, 1_000, true)))
        assertEquals("Continue with 3 coins", sendCoinsButtonLabel(3, SendCoverage(2_000, 1_000, true)))
    }

    @Test
    fun junk_in_storage_reads_as_no_rate() {
        val db = createSqliteDatabase(":memory:")
        try {
            db.keyValue.set(SEND_FEE_RATE_KEY, "fast")
            assertNull(loadSendFeeRate(db))
        } finally {
            db.close()
        }
    }
}
