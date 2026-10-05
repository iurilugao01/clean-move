package com.cleanmove.data.repository

import com.cleanmove.data.geocontrol.static.GeoAssociation
import com.cleanmove.data.geocontrol.static.GeoTrip
import com.cleanmove.domain.GeoStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StopOffsetCalibratorTest {
    private fun stop(id: Int, lat: Double, lng: Double) =
        GeoStop(id, null, null, null, null, latitude = lat, longitude = lng, isTerminal = false)

    // Three stops roughly 1km apart along a line, so distance (and thus offset) grows evenly.
    private val stops = mapOf(
        1 to stop(1, -20.30, -40.30),
        2 to stop(2, -20.31, -40.30),
        3 to stop(3, -20.32, -40.30),
    )
    private val associations = listOf(
        GeoAssociation(routeId = 10, order = 1, stopId = 1),
        GeoAssociation(routeId = 10, order = 2, stopId = 2),
        GeoAssociation(routeId = 10, order = 3, stopId = 3),
    )

    private fun trip(startMs: Long, durationMin: Long) =
        GeoTrip(routeId = 10, scheduledAtMs = startMs, estimatedArrivalMs = startMs + durationMin * 60_000)

    @Test
    fun `distributes the average trip duration proportionally to distance, increasing along the route`() {
        val trips = listOf(trip(0, 30), trip(100_000, 32), trip(200_000, 28))
        val offsets = StopOffsetCalibrator.calibrate(associations, stops, trips)

        assertEquals(0, offsets.getValue(1))
        assertTrue(offsets.getValue(2) in 1..29)
        assertTrue(offsets.getValue(2) < offsets.getValue(3))
        assertEquals(30, offsets.getValue(3)) // last stop gets ~the full average duration
    }

    @Test
    fun `fewer than minSamples valid trips yields no offsets`() {
        val offsets = StopOffsetCalibrator.calibrate(associations, stops, listOf(trip(0, 30), trip(1, 32)))
        assertTrue(offsets.isEmpty())
    }

    @Test
    fun `a trip with a null or non-positive duration does not count as a sample`() {
        val noEstimate = GeoTrip(routeId = 10, scheduledAtMs = 0, estimatedArrivalMs = null)
        val zeroOrNegative = GeoTrip(routeId = 10, scheduledAtMs = 1000, estimatedArrivalMs = 500)
        val offsets = StopOffsetCalibrator.calibrate(associations, stops, listOf(trip(0, 30), noEstimate, zeroOrNegative))
        assertTrue(offsets.isEmpty()) // only 1 valid sample, below the default minimum of 3
    }

    @Test
    fun `duplicate stopId entries and out-of-order ordem values never crash and stay non-decreasing`() {
        val messyAssociations = listOf(
            GeoAssociation(routeId = 10, order = 3, stopId = 3),
            GeoAssociation(routeId = 10, order = 1, stopId = 1),
            GeoAssociation(routeId = 10, order = 1, stopId = 1), // duplicate
            GeoAssociation(routeId = 10, order = 2, stopId = 2),
        )
        val offsets = StopOffsetCalibrator.calibrate(messyAssociations, stops, listOf(trip(0, 30), trip(1, 32), trip(2, 28)))
        assertEquals(3, offsets.size)
        assertTrue(offsets.getValue(1) <= offsets.getValue(2))
        assertTrue(offsets.getValue(2) <= offsets.getValue(3))
    }

    @Test
    fun `a stop missing from stopsById is skipped, not crashed on`() {
        val withUnknownStop = associations + GeoAssociation(routeId = 10, order = 4, stopId = 999)
        val offsets = StopOffsetCalibrator.calibrate(withUnknownStop, stops, listOf(trip(0, 30), trip(1, 32), trip(2, 28)))
        assertEquals(setOf(1, 2, 3), offsets.keys)
    }

    @Test
    fun `a single-stop route offsets to zero instead of dividing by zero distance`() {
        val one = listOf(GeoAssociation(routeId = 10, order = 1, stopId = 1))
        val offsets = StopOffsetCalibrator.calibrate(one, stops, listOf(trip(0, 30), trip(1, 32), trip(2, 28)))
        assertEquals(mapOf(1 to 0), offsets)
    }
}
