package com.cleanmove.data.repository

import com.cleanmove.data.geocontrol.static.GeoAssociation
import com.cleanmove.data.geocontrol.static.GeoTrip
import com.cleanmove.domain.GeoStop
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object StopOffsetCalibrator {
    fun calibrate(
        associations: List<GeoAssociation>,
        stopsById: Map<Int, GeoStop>,
        trips: List<GeoTrip>,
        minSamples: Int = 3,
    ): Map<Int, Int> {
        val durationsMin = trips
            .mapNotNull { trip -> trip.estimatedArrivalMs?.let { it - trip.scheduledAtMs } }
            .filter { it > 0 }
            .map { it / 60_000.0 }
        if (durationsMin.size < minSamples) return emptyMap()
        val avgDurationMin = durationsMin.average()

        val ordered = associations
            .sortedBy { it.order }
            .distinctBy { it.stopId }
            .mapNotNull { assoc -> stopsById[assoc.stopId]?.let { assoc.stopId to it } }
        if (ordered.isEmpty()) return emptyMap()
        if (ordered.size == 1) return mapOf(ordered.single().first to 0)

        val cumulativeKm = DoubleArray(ordered.size)
        for (i in 1 until ordered.size) {
            val (_, prev) = ordered[i - 1]
            val (_, curr) = ordered[i]
            cumulativeKm[i] = cumulativeKm[i - 1] + haversineKm(prev.latitude, prev.longitude, curr.latitude, curr.longitude)
        }
        val totalKm = cumulativeKm.last()

        return ordered.mapIndexed { i, (stopId, _) ->
            val fraction = if (totalKm > 0) cumulativeKm[i] / totalKm else 0.0
            stopId to (avgDurationMin * fraction).roundToInt()
        }.toMap()
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return earthRadiusKm * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
