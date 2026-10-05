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

data class GeoStop(
    val id: Int,
    val code: String?,
    val description: String?,
    val street: String?,
    val municipality: String?,
    val latitude: Double,
    val longitude: Double,
    val isTerminal: Boolean,
)

/** One live bus, as shown in "Ao vivo" mode. [lineCode] is null when the Geocontrol
 * linhaId could not be resolved to a Ceturb line — [rawLineLabel] is shown instead. */
data class LiveStopArrival(
    val lineCode: String?,
    val rawLineLabel: String?,
    val lineName: String?,
    val vehicleLabel: String?,
    val minutesAway: Int,
    val hasAccessibility: Boolean,
    val hasAirConditioning: Boolean,
    val hasWifi: Boolean,
    val reliability: Reliability,
)

/** One scheduled departure, as shown in "Estimativas" mode. [isInterpolated] is true
 * when [minuteOfDay] includes a calibrated per-stop offset; false when it is the plain
 * terminal departure time (no calibration available yet for this line). */
data class EstimatedStopArrival(
    val lineCode: String,
    val lineName: String,
    val minuteOfDay: Int,
    val isInterpolated: Boolean,
)
