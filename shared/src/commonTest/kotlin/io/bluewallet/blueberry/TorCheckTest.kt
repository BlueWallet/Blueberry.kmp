package io.bluewallet.blueberry

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TorCheckTest {
    @Test
    fun checkTorExit_ok_shows_elapsed_not_ip() = runBlocking {
        var now = 10_000L
        val lines = checkTorExit(
            overallMs = 5_000,
            nowMs = { now },
            fetchOnce = {
                now += 21_400
                TorCheckIp(isTor = true, ip = "1.2.3.4")
            },
        )
        assertEquals(listOf("ok", "21.4s"), lines)
    }

    @Test
    fun checkTorExit_retries_IsTor_false_then_succeeds() = runBlocking {
        var now = 0L
        var calls = 0
        val lines = checkTorExit(
            overallMs = 5_000,
            backoffMs = 0,
            nowMs = { now },
            fetchOnce = {
                calls++
                now += 1_000
                if (calls == 1) TorCheckIp(isTor = false, ip = "9.9.9.9")
                else TorCheckIp(isTor = true, ip = "8.8.8.8")
            },
        )
        assertEquals(2, calls)
        assertEquals(listOf("ok", "2s"), lines)
    }

    @Test
    fun formatTorCheckElapsed_rounds_to_tenths() {
        assertEquals("0s", formatTorCheckElapsed(0))
        assertEquals("0s", formatTorCheckElapsed(49))
        assertEquals("0.1s", formatTorCheckElapsed(50))
        assertEquals("21.4s", formatTorCheckElapsed(21_400))
        assertEquals("21s", formatTorCheckElapsed(21_049))
    }

    @Test
    fun checkTorExit_reports_error_when_deadline_elapses() = runBlocking {
        val lines = checkTorExit(
            overallMs = 0,
            backoffMs = 0,
            fetchOnce = { error("no network") },
        )
        assertTrue(lines.first().contains("no network") || lines.first().contains("failed"))
    }
}
