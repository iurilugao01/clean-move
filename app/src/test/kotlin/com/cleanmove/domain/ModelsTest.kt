package com.cleanmove.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsTest {
    @Test
    fun `GeoStop holds optional display fields and required coordinates`() {
        val stop = GeoStop(
            id = 404, code = "110001", description = null, street = "Avenida Paulino Muller",
            municipality = "Vitória", latitude = -20.317, longitude = -40.3196, isTerminal = false,
        )
        assertEquals(404, stop.id)
        assertEquals("110001", stop.code)
    }

    @Test
    fun `LiveStopArrival can represent an unresolved line with only the raw Geocontrol label`() {
        val arrival = LiveStopArrival(
            lineCode = null, rawLineLabel = "325", lineName = null, vehicleLabel = "1234",
            minutesAway = 5, hasAccessibility = false, hasAirConditioning = true, hasWifi = false,
            reliability = Reliability.GREEN,
        )
        assertEquals(null, arrival.lineCode)
        assertEquals("325", arrival.rawLineLabel)
    }

    @Test
    fun `EstimatedStopArrival marks whether the time was interpolated`() {
        val plain = EstimatedStopArrival("0010", "NOVA PALESTINA / FRADINHOS", 480, isInterpolated = false)
        val calibrated = plain.copy(isInterpolated = true)
        assertEquals(false, plain.isInterpolated)
        assertEquals(true, calibrated.isInterpolated)
    }
}
