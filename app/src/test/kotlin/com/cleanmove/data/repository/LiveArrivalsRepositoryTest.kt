package com.cleanmove.data.repository

import com.cleanmove.data.geocontrol.EstimativaDto
import com.cleanmove.data.geocontrol.EstimativasPorOrigemResponseDto
import com.cleanmove.data.geocontrol.GeocontrolSource
import com.cleanmove.data.geocontrol.ItinerarioDto
import com.cleanmove.data.geocontrol.ListarItinerariosResponseDto
import com.cleanmove.data.geocontrol.ListarPontosDeParadaResponseDto
import com.cleanmove.data.geocontrol.PesquisarPontosDeParadaResponseDto
import com.cleanmove.data.geocontrol.PontoDto
import com.cleanmove.data.geocontrol.UltimaPosicaoResponseDto
import com.cleanmove.data.geocontrol.static.GeoAssociation
import com.cleanmove.data.geocontrol.static.GeoTrip
import com.cleanmove.domain.GeoStop
import com.cleanmove.domain.Line
import com.cleanmove.domain.LineCatalog
import com.cleanmove.domain.LineDetail
import com.cleanmove.domain.StopCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private class FakeGeocontrolSource(var response: EstimativasPorOrigemResponseDto, var fail: Boolean = false) : GeocontrolSource {
    override suspend fun estimatesForStop(stopId: Int): EstimativasPorOrigemResponseDto {
        if (fail) throw IOException("offline")
        return response
    }
    override suspend fun searchStops(text: String?, envelope: List<Double>?) = PesquisarPontosDeParadaResponseDto()
    override suspend fun stops(ids: List<Int>) = ListarPontosDeParadaResponseDto()
    override suspend fun routes(ids: List<Int>) = ListarItinerariosResponseDto()
    override suspend fun vehiclePosition(vehicleIds: List<Int>) = UltimaPosicaoResponseDto()
}

private class ArrivalsFixedStopCatalog(private val codes: Map<Int, String> = emptyMap()) : StopCatalog {
    override fun observeStops(query: String): Flow<List<GeoStop>> = flowOf(emptyList())
    override suspend fun refreshIfStale() = Result.success(Unit)
    override suspend fun stop(id: Int): GeoStop? = null
    override suspend fun lineCode(lineId: Int): String? = codes[lineId]
    override suspend fun lineIdForRoute(routeId: Int): Int? = null
    override suspend fun routesServing(stopId: Int): List<Int> = emptyList()
    override suspend fun routeCode(routeId: Int): String? = null
    override suspend fun stopOrderWithinRoute(routeId: Int): List<GeoAssociation> = emptyList()
    override suspend fun tripsFor(routeId: Int): List<GeoTrip> = emptyList()
}

private class ArrivalsFixedLineCatalog(private val lines: List<Line>) : LineCatalog {
    override fun observeLines(query: String): Flow<List<Line>> = flowOf(lines)
    override suspend fun refreshLinesIfStale() = Result.success(Unit)
    override suspend fun loadDetail(code: String): Result<LineDetail> = Result.failure(IllegalStateException())
    override suspend fun lineByCode(code: String): Line? = lines.firstOrNull { it.code == code }
}

class LiveArrivalsRepositoryTest {
    private fun estimativa(linhaId: Int) = EstimativaDto(
        itinerarioId = 1, linhaId = linhaId, veiculo = "1234",
        horarioDePartida = 1000, horarioNaOrigem = 1000 + 5 * 60_000, horarioDaTransmissao = 1000,
        accessibility = true, arCondicionado = false, wifi = "ABC",
    )

    @Test fun `resolves the line code for a known Geocontrol line`() = runTest {
        val repo = LiveArrivalsRepository(
            FakeGeocontrolSource(EstimativasPorOrigemResponseDto(1, 1000, listOf(estimativa(776)))),
            LineCodeResolver(ArrivalsFixedStopCatalog(mapOf(776 to "010")), ArrivalsFixedLineCatalog(listOf(Line("0010", "NOVA PALESTINA / FRADINHOS")))),
            ArrivalsFixedStopCatalog(),
        )
        val arrival = repo.forStop(404, nowMs = 1000).getOrThrow().single()
        assertEquals("0010", arrival.lineCode)
        assertEquals("NOVA PALESTINA / FRADINHOS", arrival.lineName)
        assertEquals(null, arrival.rawLineLabel)
        assertTrue(arrival.hasAccessibility)
    }

    @Test fun `an unresolved line falls back to its own Geocontrol code, then to a numeric label`() = runTest {
        val withGeoCode = LiveArrivalsRepository(
            FakeGeocontrolSource(EstimativasPorOrigemResponseDto(1, 1000, listOf(estimativa(1)))),
            LineCodeResolver(ArrivalsFixedStopCatalog(mapOf(1 to "79N")), ArrivalsFixedLineCatalog(emptyList())),
            ArrivalsFixedStopCatalog(mapOf(1 to "79N")),
        )
        assertEquals("79N", withGeoCode.forStop(404, 1000).getOrThrow().single().rawLineLabel)

        val withoutGeoCode = LiveArrivalsRepository(
            FakeGeocontrolSource(EstimativasPorOrigemResponseDto(1, 1000, listOf(estimativa(9999)))),
            LineCodeResolver(ArrivalsFixedStopCatalog(), ArrivalsFixedLineCatalog(emptyList())),
            ArrivalsFixedStopCatalog(),
        )
        assertEquals("Linha 9999", withoutGeoCode.forStop(404, 1000).getOrThrow().single().rawLineLabel)
    }

    @Test fun `a source failure becomes Result failure`() = runTest {
        val repo = LiveArrivalsRepository(
            FakeGeocontrolSource(EstimativasPorOrigemResponseDto(), fail = true),
            LineCodeResolver(ArrivalsFixedStopCatalog(), ArrivalsFixedLineCatalog(emptyList())),
            ArrivalsFixedStopCatalog(),
        )
        assertTrue(repo.forStop(404, 1000).isFailure)
    }

    @Test fun `an empty estimate list is a success, not a failure`() = runTest {
        val repo = LiveArrivalsRepository(
            FakeGeocontrolSource(EstimativasPorOrigemResponseDto(1, 1000, emptyList())),
            LineCodeResolver(ArrivalsFixedStopCatalog(), ArrivalsFixedLineCatalog(emptyList())),
            ArrivalsFixedStopCatalog(),
        )
        assertEquals(emptyList<Any>(), repo.forStop(404, 1000).getOrThrow())
    }
}
