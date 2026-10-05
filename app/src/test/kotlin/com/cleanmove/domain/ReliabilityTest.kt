package com.cleanmove.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ReliabilityTest {
    private val oneHour = 3_600_000L
    private val oneMinute = 60_000L

    @Test
    fun `clampedHourDiffMs rounds the device-server gap to the nearest hour and negates it`() {
        // Device is 3 hours ahead of server (e.g. wrong device timezone) -> diff corrects by subtracting 3h.
        assertEquals(-3 * oneHour, clampedHourDiffMs(deviceNowMs = 3 * oneHour, serverTimeMs = 0))
        // Fine clock drift under 30 min rounds away: treated as no correction needed.
        assertEquals(0L, clampedHourDiffMs(deviceNowMs = 10 * 60_000, serverTimeMs = 0))
    }

    @Test
    fun `minutesUntil floors to whole minutes and can be negative when overdue`() {
        val now = 1_000_000L
        assertEquals(5, minutesUntil(horarioNaOrigemMs = now + 5 * oneMinute + 30_000, diffHorarioMs = 0, deviceNowMs = now))
        assertEquals(-2, minutesUntil(horarioNaOrigemMs = now - 2 * oneMinute, diffHorarioMs = 0, deviceNowMs = now))
        // A diffHorarioMs correction cancels out an hour-scale offset baked into horarioNaOrigem.
        assertEquals(5, minutesUntil(horarioNaOrigemMs = now + 5 * oneMinute + oneHour, diffHorarioMs = oneHour, deviceNowMs = now))
    }

    @Test
    fun `reliabilityOf is green when the GPS fix is fresh and on schedule`() {
        val now = 10_000_000L
        val result = reliabilityOf(
            horarioNaOrigemMs = now + 5 * oneMinute,
            horarioDaTransmissaoMs = now - oneMinute,
            horarioDePartidaMs = now,
            horarioDoServidorMs = now,
            deviceNowMs = now,
        )
        assertEquals(Reliability.GREEN, result)
    }

    @Test
    fun `reliabilityOf is gray when the predicted departure is more than 5h away from device time`() {
        val now = 10_000_000L
        val result = reliabilityOf(
            horarioNaOrigemMs = now + 5 * oneMinute,
            horarioDaTransmissaoMs = now,
            horarioDePartidaMs = now - 6 * oneHour,
            horarioDoServidorMs = now,
            deviceNowMs = now,
        )
        assertEquals(Reliability.GRAY, result)
    }

    @Test
    fun `reliabilityOf is orange then red as the GPS fix grows stale`() {
        val now = 10_000_000L
        val orange = reliabilityOf(
            horarioNaOrigemMs = now,
            horarioDaTransmissaoMs = now - 25 * oneMinute,
            horarioDePartidaMs = now,
            horarioDoServidorMs = now,
            deviceNowMs = now,
        )
        assertEquals(Reliability.ORANGE, orange)

        val red = reliabilityOf(
            horarioNaOrigemMs = now,
            horarioDaTransmissaoMs = now - 40 * oneMinute,
            horarioDePartidaMs = now,
            horarioDoServidorMs = now,
            deviceNowMs = now,
        )
        assertEquals(Reliability.RED, red)
    }
}
