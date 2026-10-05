package com.cleanmove.data.repository

import com.cleanmove.data.local.GeoDao
import com.cleanmove.data.local.StopOffsetEntity
import com.cleanmove.domain.DayType
import com.cleanmove.domain.EstimatedArrivals
import com.cleanmove.domain.EstimatedStopArrival
import com.cleanmove.domain.LineCatalog
import com.cleanmove.domain.StopCatalog

private const val DAY_MINUTES = 1440

/**
 * The "Estimativas" data source for a stop: schedule-based departures (Ceturb's [LineCatalog])
 * adjusted by a calibrated per-stop offset (from [StopOffsetCalibrator], cached in [GeoDao]),
 * lazily computing that offset the first time it is needed for a route/stop pair and reusing
 * it afterward.
 */
class EstimatedArrivalsRepository(
    private val stopCatalog: StopCatalog,
    private val geoDao: GeoDao,
    private val resolver: LineCodeResolver,
    private val lineCatalog: LineCatalog,
    private val now: () -> Long = System::currentTimeMillis,
    private val windowMinutes: Int = 60,
) : EstimatedArrivals {

    override suspend fun forStop(stopId: Int, nowMinuteOfDay: Int, dayType: DayType): List<EstimatedStopArrival> {
        val results = mutableListOf<EstimatedStopArrival>()
        for (routeId in stopCatalog.routesServing(stopId)) {
            val geoLineId = stopCatalog.lineIdForRoute(routeId) ?: continue
            val line = resolver.resolve(geoLineId) ?: continue
            val detail = lineCatalog.loadDetail(line.code).getOrNull() ?: continue
            val offset = offsetFor(routeId, stopId)

            detail.schedules
                .filter { it.dayType == dayType }
                .forEach { dep ->
                    val estimatedMinute = Math.floorMod(dep.minuteOfDay + (offset ?: 0), DAY_MINUTES)
                    val delta = Math.floorMod(estimatedMinute - nowMinuteOfDay, DAY_MINUTES)
                    if (delta <= windowMinutes) {
                        results += EstimatedStopArrival(line.code, line.name, estimatedMinute, isInterpolated = offset != null)
                    }
                }
        }
        return results.sortedBy { Math.floorMod(it.minuteOfDay - nowMinuteOfDay, DAY_MINUTES) }
    }

    private suspend fun offsetFor(routeId: Int, stopId: Int): Int? {
        geoDao.stopOffset(routeId, stopId)?.let { return it.offsetMinutes }

        val associations = stopCatalog.stopOrderWithinRoute(routeId)
        if (associations.isEmpty()) return null
        val stopsById = associations.map { it.stopId }.distinct()
            .mapNotNull { id -> stopCatalog.stop(id)?.let { id to it } }.toMap()
        val trips = stopCatalog.tripsFor(routeId)

        val offsets = StopOffsetCalibrator.calibrate(associations, stopsById, trips)
        if (offsets.isEmpty()) return null

        val computedAt = now()
        geoDao.putStopOffsets(offsets.map { (sid, minutes) -> StopOffsetEntity(routeId, sid, minutes, trips.size, computedAt) })
        return offsets[stopId]
    }
}
