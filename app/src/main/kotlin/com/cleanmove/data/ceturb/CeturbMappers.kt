package com.cleanmove.data.ceturb

import com.cleanmove.domain.DayType
import com.cleanmove.domain.ItineraryStep
import com.cleanmove.domain.Line
import com.cleanmove.domain.ScheduledDeparture

fun LineDto.toDomain(): Line = Line(code = code, name = description.trim())

fun String.toDayType(): DayType = when (trim().uppercase()) {
    "DIAS ÚTEIS" -> DayType.WEEKDAY
    "SÁBADO" -> DayType.SATURDAY
    "DOMINGOS E FERIADOS" -> DayType.SUNDAY_HOLIDAY
    "ATÍPICOS ENTRE FERIADOS" -> DayType.ATYPICAL
    else -> DayType.OTHER
}

/** "06:00" -> 360. Hours >= 24 (after-midnight service) wrap to the next day: "24:10" -> 10. */
fun parseMinuteOfDay(raw: String): Int? {
    val parts = raw.trim().split(":")
    if (parts.size < 2) return null
    val hours = parts[0].toIntOrNull() ?: return null
    val minutes = parts[1].toIntOrNull() ?: return null
    if (hours < 0 || minutes !in 0..59) return null
    return (hours * 60 + minutes) % 1440
}

fun List<ScheduleDto>.toDepartures(): List<ScheduledDeparture> =
    mapNotNull { dto ->
        val minute = parseMinuteOfDay(dto.departureTime) ?: return@mapNotNull null
        Triple(dto, dto.dayDescription.toDayType(), minute)
    }
        .groupBy { (dto, day, minute) -> listOf(dto.lineCode, day, dto.terminalSeq, minute) }
        .values
        .map { group ->
            val (first, day, minute) = group.first()
            ScheduledDeparture(
                lineCode = first.lineCode,
                dayType = day,
                direction = first.terminalSeq,
                terminalName = first.terminalName.trim(),
                minuteOfDay = minute,
                notes = group.mapNotNull { it.first.orientation?.trim()?.takeIf(String::isNotEmpty) }.toSet(),
            )
        }
        .sortedWith(compareBy({ it.dayType }, { it.direction }, { it.minuteOfDay }))

fun List<ItineraryDto>.toSteps(): List<ItineraryStep> =
    filter { it.street.isNotBlank() }
        .map {
            ItineraryStep(
                lineCode = it.lineCode,
                direction = if (it.direction.trim().uppercase() == "I") 1 else 2,
                sequence = it.sequence,
                street = it.street.trim(),
            )
        }
        .sortedWith(compareBy({ it.direction }, { it.sequence }))
