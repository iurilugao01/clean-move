package com.cleanmove.data.ceturb

import com.cleanmove.domain.DayType
import com.cleanmove.domain.ItineraryStep
import com.cleanmove.domain.Line
import com.cleanmove.domain.ScheduledDeparture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CeturbMappersTest {
    private fun schedule(time: String, day: String = "DIAS ÚTEIS", seq: Int = 1, obs: String? = null, terminal: String = "NOVA PALESTINA") =
        ScheduleDto(terminalName = terminal, dayDescription = day, departureTime = time, lineCode = "0010", terminalSeq = seq, orientation = obs)

    @Test
    fun `maps a line`() {
        assertEquals(Line("0010", "NOVA PALESTINA / FRADINHOS"), LineDto("NOVA PALESTINA / FRADINHOS", "0010").toDomain())
    }

    @Test
    fun `maps day descriptions and falls back to OTHER`() {
        assertEquals(DayType.WEEKDAY, "DIAS ÚTEIS".toDayType())
        assertEquals(DayType.SATURDAY, "SÁBADO".toDayType())
        assertEquals(DayType.SUNDAY_HOLIDAY, "DOMINGOS E FERIADOS".toDayType())
        assertEquals(DayType.ATYPICAL, "ATÍPICOS ENTRE FERIADOS".toDayType())
        assertEquals(DayType.OTHER, "ALGO NOVO".toDayType())
    }

    @Test
    fun `live Ceturb data uses separate DOMINGOS and FERIADO descriptions`() {
        assertEquals(DayType.SUNDAY_HOLIDAY, "DOMINGOS".toDayType())
        assertEquals(DayType.SUNDAY_HOLIDAY, "FERIADO".toDayType())
    }

    @Test
    fun `identical sunday and holiday departures merge into one`() {
        val departures = listOf(
            schedule("06:15", day = "DOMINGOS"),
            schedule("06:15", day = "FERIADO"),
            schedule("07:00", day = "DOMINGOS"),
        ).toDepartures()
        assertEquals(listOf(375, 420), departures.map { it.minuteOfDay })
        assertEquals(setOf(DayType.SUNDAY_HOLIDAY), departures.map { it.dayType }.toSet())
    }

    @Test
    fun `parses minute of day including after-midnight and bad values`() {
        assertEquals(360, parseMinuteOfDay("06:00"))
        assertEquals(10, parseMinuteOfDay("24:10"))
        assertEquals(1439, parseMinuteOfDay("23:59"))
        assertNull(parseMinuteOfDay(""))
        assertNull(parseMinuteOfDay("abc"))
        assertNull(parseMinuteOfDay("12:xx"))
    }

    @Test
    fun `collapses duplicate rows that differ only by orientation`() {
        val departures = listOf(schedule("06:00", obs = "A"), schedule("06:00", obs = "P")).toDepartures()
        assertEquals(
            listOf(ScheduledDeparture("0010", DayType.WEEKDAY, 1, "NOVA PALESTINA", 360, setOf("A", "P"))),
            departures,
        )
    }

    @Test
    fun `keeps different directions and days separate and drops unparsable times`() {
        val departures = listOf(
            schedule("06:00", seq = 1),
            schedule("06:00", seq = 2, terminal = "FRADINHOS"),
            schedule("06:00", day = "SÁBADO"),
            schedule("??"),
        ).toDepartures()
        assertEquals(3, departures.size)
    }

    @Test
    fun `itinerary trims streets drops blanks and orders by direction then sequence`() {
        val steps = listOf(
            ItineraryDto("RUA SÃO PEDRO                 ", "0010", "I", 2),
            ItineraryDto("RUA DO CAJU                   ", "0010", "I", 1),
            ItineraryDto("                              ", "0010", "I", 3),
            ItineraryDto("AV. BRASIL", "0010", "V", 1),
        ).toSteps()
        assertEquals(
            listOf(
                ItineraryStep("0010", 1, 1, "RUA DO CAJU"),
                ItineraryStep("0010", 1, 2, "RUA SÃO PEDRO"),
                ItineraryStep("0010", 2, 1, "AV. BRASIL"),
            ),
            steps,
        )
    }
}
