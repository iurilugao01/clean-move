package com.cleanmove.data.geocontrol

import com.cleanmove.domain.Reliability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeocontrolMappersTest {
    private val now = 10_000_000L

    private fun estimativa(
        linhaId: Int = 1,
        itinerarioId: Int = 10,
        veiculo: String? = "1234",
        horarioDePartida: Long = now,
        horarioNaOrigem: Long = now + 5 * 60_000,
        horarioDaTransmissao: Long = now,
        accessibility: Boolean = false,
        arCondicionado: Boolean = false,
        wifi: String? = null,
    ) = EstimativaDto(
        veiculoId = 99,
        itinerarioId = itinerarioId,
        linhaId = linhaId,
        veiculo = veiculo,
        horarioDePartida = horarioDePartida,
        horarioNaOrigem = horarioNaOrigem,
        horarioDaTransmissao = horarioDaTransmissao,
        accessibility = accessibility,
        arCondicionado = arCondicionado,
        wifi = wifi,
    )

    @Test
    fun `maps timing, amenities and reliability from a live estimate`() {
        val response = EstimativasPorOrigemResponseDto(
            pontoDeOrigemId = 404,
            horarioDoServidor = now,
            estimativas = listOf(estimativa(accessibility = true, arCondicionado = true, wifi = "ABC123")),
        )

        val arrivals = response.toArrivals(deviceNowMs = now)

        val arrival = arrivals.single()
        assertEquals(1, arrival.linhaId)
        assertEquals(10, arrival.itinerarioId)
        assertEquals("1234", arrival.vehicleLabel)
        assertEquals(5, arrival.minutesAway)
        assertTrue(arrival.hasAccessibility)
        assertTrue(arrival.hasAirConditioning)
        assertTrue(arrival.hasWifi)
        assertEquals(Reliability.GREEN, arrival.reliability)
    }

    @Test
    fun `a blank or missing wifi code means no wifi`() {
        val withoutWifi = EstimativasPorOrigemResponseDto(1, now, listOf(estimativa(wifi = null)))
        val withBlankWifi = EstimativasPorOrigemResponseDto(1, now, listOf(estimativa(wifi = "  ")))

        assertFalse(withoutWifi.toArrivals(now).single().hasWifi)
        assertFalse(withBlankWifi.toArrivals(now).single().hasWifi)
    }

    @Test
    fun `an empty estimates list maps to an empty arrivals list`() {
        assertTrue(EstimativasPorOrigemResponseDto(1, now, emptyList()).toArrivals(now).isEmpty())
    }

    @Test
    fun `maps a search response to plain stop ids`() {
        val dto = PesquisarPontosDeParadaResponseDto(pontosDeParada = listOf(404, 405))
        assertEquals(listOf(404, 405), dto.toStopIds())
    }

    @Test
    fun `maps full stop details, trimming blank optional fields to null`() {
        val dto = PontoDto(
            id = 404,
            identificador = "110001",
            descricao = "  ",
            logradouro = "Avenida Paulino Muller",
            municipio = "Vitória",
            longitude = -40.31,
            latitude = -20.31,
            terminal = false,
        )
        val stop = dto.toStop()
        assertEquals(404, stop.id)
        assertEquals("110001", stop.code)
        assertNull(stop.description)
        assertEquals("Avenida Paulino Muller", stop.street)
        assertFalse(stop.isTerminal)
    }

    @Test
    fun `parses a WKT point into latitude and longitude`() {
        val point = parseWktPoint("POINT (-40.319 -20.317)")
        assertEquals(-40.319, point!!.longitude, 0.0001)
        assertEquals(-20.317, point.latitude, 0.0001)
    }

    @Test
    fun `tolerates extra whitespace and a Z coordinate in WKT`() {
        val point = parseWktPoint("  POINT Z (-40.319   -20.317  10.5) ")
        assertEquals(-40.319, point!!.longitude, 0.0001)
        assertEquals(-20.317, point.latitude, 0.0001)
    }

    @Test
    fun `an unparsable WKT value returns null instead of throwing`() {
        assertNull(parseWktPoint("LINESTRING (0 0, 1 1)"))
        assertNull(parseWktPoint(""))
        assertNull(parseWktPoint("POINT (abc def)"))
    }

    @Test
    fun `maps a vehicle position response to a LatLng`() {
        val dto = UltimaPosicaoResponseDto(pacotes = listOf(PacoteDto(localizacao = "POINT (-40.3 -20.3)")))
        val position = dto.toVehiclePosition()
        assertEquals(-40.3, position!!.longitude, 0.0001)
    }

    @Test
    fun `an empty pacotes list maps to no vehicle position`() {
        assertNull(UltimaPosicaoResponseDto(pacotes = emptyList()).toVehiclePosition())
    }
}
