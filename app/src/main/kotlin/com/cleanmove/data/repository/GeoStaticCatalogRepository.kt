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

private const val GEO_CORE_KEY = "geo_core"
private const val GEO_EXTENDED_KEY = "geo_extended"
private const val SEVEN_DAYS_MILLIS = 7L * 24 * 60 * 60 * 1000

class GeoStaticCatalogRepository(
    private val source: GeocontrolStaticSource,
    private val dao: GeoDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = SEVEN_DAYS_MILLIS,
) : StopCatalog {

    override fun observeStops(query: String): Flow<List<GeoStop>> =
        dao.observeStops(query.trim()).map { rows -> rows.map { it.toDomain() } }

    /**
     * Refreshes the geo-static cache in two independent phases so that a failure fetching the
     * large, failure-prone dumps (associations/trips) never discards the small core data
     * (stops/lines/routes) that Buscar/stop search actually needs. Each phase has its own TTL
     * and its own try/catch; both are attempted regardless of the other's outcome. Overall
     * success requires only the core phase to succeed - the extended phase failing is reported
     * only internally (it's swallowed here, same as the rest of this class's error handling);
     * callers who need visibility into extended-phase failures specifically would need a new
     * signal, which is out of scope for this fix.
     */
    override suspend fun refreshIfStale(): Result<Unit> {
        val core = refreshCoreIfStale()
        refreshExtendedIfStale()
        return core
    }

    private suspend fun refreshCoreIfStale(): Result<Unit> {
        if (isFresh(dao.geoUpdatedAt(GEO_CORE_KEY))) return Result.success(Unit)
        return try {
            val stops = source.stops().map { it.toDomain().toEntity() }
            val lines = source.lines().map { it.toDomain().let { l -> GeoLineEntity(l.id, l.code, l.name) } }
            val routes = source.routes().map { it.toDomain().let { r -> GeoRouteEntity(r.id, r.code, r.destinationLabel, r.lineId) } }
            dao.replaceCore(stops, lines, routes, SyncMeta(GEO_CORE_KEY, now()))
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun refreshExtendedIfStale(): Result<Unit> {
        if (isFresh(dao.geoUpdatedAt(GEO_EXTENDED_KEY))) return Result.success(Unit)
        return try {
            val associations = source.associations().map { it.toDomain().toEntity() }
            val trips = source.tripsNext7Days().mapNotNull { it.toDomain()?.toEntity() }
            dao.replaceExtended(associations, trips, SyncMeta(GEO_EXTENDED_KEY, now()))
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
    override suspend fun routeCode(routeId: Int): String? = dao.routeCode(routeId)

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
