package com.cleanmove.data.repository

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
import org.junit.Assert.assertNull
import org.junit.Test

private class FixedStopCatalog(private val codes: Map<Int, String>) : StopCatalog {
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

private class FixedLineCatalog(private val lines: List<Line>) : LineCatalog {
    override fun observeLines(query: String): Flow<List<Line>> = flowOf(lines)
    override suspend fun refreshLinesIfStale() = Result.success(Unit)
    override suspend fun loadDetail(code: String): Result<LineDetail> = Result.failure(IllegalStateException())
    override suspend fun lineByCode(code: String): Line? = lines.firstOrNull { it.code == code }
}

class LineCodeResolverTest {
    @Test
    fun `resolves a Geocontrol line id by prefixing its code with the Ceturb zero`() = runTest {
        val resolver = LineCodeResolver(
            FixedStopCatalog(mapOf(776 to "010")),
            FixedLineCatalog(listOf(Line("0010", "NOVA PALESTINA / FRADINHOS"))),
        )
        assertEquals(Line("0010", "NOVA PALESTINA / FRADINHOS"), resolver.resolve(776))
    }

    @Test
    fun `returns null when the Geocontrol line id itself is unknown`() = runTest {
        val resolver = LineCodeResolver(FixedStopCatalog(emptyMap()), FixedLineCatalog(emptyList()))
        assertNull(resolver.resolve(9999))
    }

    @Test
    fun `returns null when the prefixed code has no match in the Ceturb catalog (a known exception line)`() = runTest {
        val resolver = LineCodeResolver(
            FixedStopCatalog(mapOf(1 to "79N")), // e.g. Geocontrol "79N" has no "079N" counterpart in Ceturb
            FixedLineCatalog(listOf(Line("0010", "X"))),
        )
        assertNull(resolver.resolve(1))
    }
}
