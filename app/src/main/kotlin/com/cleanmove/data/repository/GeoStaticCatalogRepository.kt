package com.cleanmove.data.repository

import com.cleanmove.data.geocontrol.static.GeoAssociation
import com.cleanmove.data.geocontrol.static.GeoTrip
import com.cleanmove.data.geocontrol.static.GeocontrolStaticSource
import com.cleanmove.data.geocontrol.static.toDomain
import com.cleanmove.data.local.GeoAssociationEntity
import com.cleanmove.data.local.GeoDao
import com.cleanmove.data.local.GeoLineEntity
import com.cleanmove.data.local.GeoRouteEntity
import com.cleanmove.data.local.GeoStopEntity
import com.cleanmove.data.local.GeoTripEntity
import com.cleanmove.data.local.SyncMeta
import com.cleanmove.domain.GeoStop
import com.cleanmove.domain.StopCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val GEO_STATIC_KEY = "geo_static"
private const val SEVEN_DAYS_MILLIS = 7L * 24 * 60 * 60 * 1000

class GeoStaticCatalogRepository(
    private val source: GeocontrolStaticSource,
    private val dao: GeoDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = SEVEN_DAYS_MILLIS,
) : StopCatalog {

    override fun observeStops(query: String): Flow<List<GeoStop>> =
        dao.observeStops(query.trim()).map { rows -> rows.map { it.toDomain() } }

    override suspend fun refreshIfStale(): Result<Unit> {
        if (isFresh(dao.geoUpdatedAt(GEO_STATIC_KEY))) return Result.success(Unit)
        return try {
            val stops = source.stops().map { it.toDomain().toEntity() }
            val lines = source.lines().map { it.toDomain().let { l -> GeoLineEntity(l.id, l.code, l.name) } }
            val routes = source.routes().map { it.toDomain().let { r -> GeoRouteEntity(r.id, r.code, r.destinationLabel, r.lineId) } }
            val associations = source.associations().map { it.toDomain().toEntity() }
            val trips = source.tripsNext7Days().mapNotNull { it.toDomain()?.toEntity() }
            dao.replaceAll(stops, lines, routes, associations, trips, SyncMeta(GEO_STATIC_KEY, now()))
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun stop(id: Int): GeoStop? = dao.stop(id)?.toDomain()
    override suspend fun lineCode(lineId: Int): String? = dao.lineCode(lineId)
    override suspend fun lineIdForRoute(routeId: Int): Int? = dao.lineIdForRoute(routeId)
    override suspend fun routesServing(stopId: Int): List<Int> = dao.routesServing(stopId)

    override suspend fun stopOrderWithinRoute(routeId: Int): List<GeoAssociation> =
        dao.associationsForRoute(routeId).map { GeoAssociation(it.routeId, it.order, it.stopId) }

    override suspend fun tripsFor(routeId: Int): List<GeoTrip> =
        dao.tripsForRoute(routeId).map { GeoTrip(it.routeId, it.scheduledAtMs, it.estimatedArrivalMs) }

    private fun isFresh(updatedAt: Long?): Boolean = updatedAt != null && now() - updatedAt < ttlMillis

    private fun GeoStopEntity.toDomain() = GeoStop(id, code, description, street, municipality, latitude, longitude, isTerminal)
    private fun GeoStop.toEntity() = GeoStopEntity(id, code, description, street, municipality, latitude, longitude, isTerminal)
    private fun GeoAssociation.toEntity() = GeoAssociationEntity(routeId = routeId, order = order, stopId = stopId)
    private fun GeoTrip.toEntity() = GeoTripEntity(routeId = routeId, scheduledAtMs = scheduledAtMs, estimatedArrivalMs = estimatedArrivalMs)
}
