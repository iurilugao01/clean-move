package com.cleanmove.domain

enum class DayType { WEEKDAY, SATURDAY, SUNDAY_HOLIDAY, ATYPICAL, OTHER }

data class Line(val code: String, val name: String)

data class ScheduledDeparture(
    val lineCode: String,
    val dayType: DayType,
    val direction: Int,
    val terminalName: String,
    val minuteOfDay: Int,
    val notes: Set<String>,
)

data class ItineraryStep(val lineCode: String, val direction: Int, val sequence: Int, val street: String)

data class LineDetail(
    val line: Line,
    val schedules: List<ScheduledDeparture>,
    val itinerary: List<ItineraryStep>,
)

data class LiveArrival(val lineCode: String, val minutesAway: Int)

data class ScheduledArrival(val lineCode: String, val minuteOfDay: Int)

data class Arrival(val lineCode: String, val liveMinutes: Int?, val scheduledMinuteOfDay: Int?)
