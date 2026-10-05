package com.cleanmove.ui

import com.cleanmove.data.geocontrol.static.GeoAssociation
import com.cleanmove.data.geocontrol.static.GeoTrip
import com.cleanmove.domain.FavoritesStore
import com.cleanmove.domain.GeoStop
import com.cleanmove.domain.Line
import com.cleanmove.domain.LineCatalog
import com.cleanmove.domain.LineDetail
import com.cleanmove.domain.StopCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeCatalog(
    initialLines: List<Line> = emptyList(),
    var refreshResult: Result<Unit> = Result.success(Unit),
    var detailResult: Result<LineDetail> = Result.failure(IllegalStateException("not set")),
) : LineCatalog {
    val lines = MutableStateFlow(initialLines)
    var refreshCalls = 0

    override fun observeLines(query: String): Flow<List<Line>> =
        lines.map { all -> all.filter { it.name.contains(query, true) || it.code.contains(query, true) } }

    override suspend fun refreshLinesIfStale(): Result<Unit> { refreshCalls++; return refreshResult }
    override suspend fun loadDetail(code: String): Result<LineDetail> = detailResult
    override suspend fun lineByCode(code: String): Line? = lines.value.firstOrNull { it.code == code }
}

class FakeStopCatalog(
    initialStops: List<GeoStop> = emptyList(),
    var refreshResult: Result<Unit> = Result.success(Unit),
) : StopCatalog {
    val stops = MutableStateFlow(initialStops)
    var refreshCalls = 0

    override fun observeStops(query: String): Flow<List<GeoStop>> =
        stops.map { all -> all.filter { (it.street ?: "").contains(query, true) || (it.code ?: "").contains(query, true) } }
    override suspend fun refreshIfStale(): Result<Unit> { refreshCalls++; return refreshResult }
    override suspend fun stop(id: Int): GeoStop? = stops.value.firstOrNull { it.id == id }
    override suspend fun lineCode(lineId: Int): String? = null
    override suspend fun lineIdForRoute(routeId: Int): Int? = null
    override suspend fun routesServing(stopId: Int): List<Int> = emptyList()
    override suspend fun routeCode(routeId: Int): String? = null
    override suspend fun stopOrderWithinRoute(routeId: Int): List<GeoAssociation> = emptyList()
    override suspend fun tripsFor(routeId: Int): List<GeoTrip> = emptyList()
}

class FakeFavorites(initial: Set<String> = emptySet()) : FavoritesStore {
    val state = MutableStateFlow(initial)
    override val favoriteLines: Flow<Set<String>> = state
    override suspend fun toggleLine(code: String) { state.value = if (code in state.value) state.value - code else state.value + code }
}
