package com.cleanmove.domain

import com.cleanmove.data.geocontrol.static.GeoAssociation
import com.cleanmove.data.geocontrol.static.GeoTrip
import kotlinx.coroutines.flow.Flow

interface LineCatalog {
    fun observeLines(query: String): Flow<List<Line>>
    suspend fun refreshLinesIfStale(): Result<Unit>
    suspend fun loadDetail(code: String): Result<LineDetail>
}

interface StopCatalog {
    fun observeStops(query: String): Flow<List<GeoStop>>
    suspend fun refreshIfStale(): Result<Unit>
    suspend fun stop(id: Int): GeoStop?
    suspend fun lineCode(lineId: Int): String?
    suspend fun lineIdForRoute(routeId: Int): Int?
    suspend fun routesServing(stopId: Int): List<Int>
    suspend fun stopOrderWithinRoute(routeId: Int): List<GeoAssociation>
    suspend fun tripsFor(routeId: Int): List<GeoTrip>
}

interface FavoritesStore {
    val favoriteLines: Flow<Set<String>>
    suspend fun toggleLine(code: String)
}
