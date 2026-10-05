package com.cleanmove.data.repository

import com.cleanmove.data.local.GeoDao
import com.cleanmove.data.local.StopOffsetEntity
import com.cleanmove.domain.DayType
import com.cleanmove.domain.EstimatedArrivals
import com.cleanmove.domain.EstimatedStopArrival
import com.cleanmove.domain.Line
import com.cleanmove.domain.LineCatalog
import com.cleanmove.domain.LineDetail
import com.cleanmove.domain.StopCatalog
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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
        // Resolve every route serving this stop to a Line, then group by line CODE: a line with
        // several itineraries (ida/volta, variants) serving the same stop must not have its
        // schedule loaded - or emitted - once per route (see Finding I1).
        val routeIdsByLineCode = linkedMapOf<String, MutableList<Int>>()
        val lineByCode = mutableMapOf<String, Line>()
        for (routeId in stopCatalog.routesServing(stopId)) {
            val geoLineId = stopCatalog.lineIdForRoute(routeId) ?: continue
            val line = resolver.resolve(geoLineId) ?: continue
            lineByCode.putIfAbsent(line.code, line)
            routeIdsByLineCode.getOrPut(line.code) { mutableListOf() }.add(routeId)
        }
        if (routeIdsByLineCode.isEmpty()) return emptyList()

        // One loadDetail per distinct line, run concurrently (Finding I2) instead of once per route.
        val detailsByLineCode: Map<String, LineDetail?> = coroutineScope {
            routeIdsByLineCode.keys
                .associateWith { code -> async { lineCatalog.loadDetail(code).getOrNull() } }
                .mapValues { it.value.await() }
        }

        val results = mutableListOf<EstimatedStopArrival>()
        for ((lineCode, routeIds) in routeIdsByLineCode) {
            val line = lineByCode.getValue(lineCode)
            val detail = detailsByLineCode[lineCode] ?: continue

            // Pick one representative route id per distinct direction (null = direction unknown).
            // Routes sharing a direction are deduplicated to a single pass, using the first
            // route's calibrated offset, per Finding I1 step 5.
            val routeIdByDirection = linkedMapOf<Int?, Int>()
            for (routeId in routeIds) {
                val direction = directionFor(routeId)
                routeIdByDirection.putIfAbsent(direction, routeId)
            }

            for ((direction, routeId) in routeIdByDirection) {
                val offset = offsetFor(routeId, stopId)
                // Known direction -> only that terminal's schedule. Unknown -> fallback to the
                // full (both-direction) schedule rather than guessing wrong (Finding I1 step 3).
                val schedules = if (direction != null) {
                    detail.schedules.filter { it.direction == direction }
                } else {
                    detail.schedules
                }

                schedules
                    .filter { it.dayType == dayType }
                    .forEach { dep ->
                        val estimatedMinute = Math.floorMod(dep.minuteOfDay + (offset ?: 0), DAY_MINUTES)
                        val delta = Math.floorMod(estimatedMinute - nowMinuteOfDay, DAY_MINUTES)
                        if (delta <= windowMinutes) {
                            results += EstimatedStopArrival(line.code, line.name, estimatedMinute, isInterpolated = offset != null)
                        }
                    }
            }
        }
        return results.sortedBy { Math.floorMod(it.minuteOfDay - nowMinuteOfDay, DAY_MINUTES) }
    }

    /**
     * The direction a route serves, derived from the last character of its [GeoRoute.code]
     * suffix (e.g. Ceturb-style "010I" for ida/outbound -> 1, "010V" for volta/return -> 2),
     * following the same "I" -> outbound convention established in CeturbMappers.toSteps()/
     * toDepartures(). Returns null (unknown) when the route has no code - callers must not
     * guess a direction in that case, and should fall back to not filtering by direction.
     */
    private suspend fun directionFor(routeId: Int): Int? {
        val code = stopCatalog.routeCode(routeId)
        if (code.isNullOrEmpty()) return null
        return if (code.last().uppercaseChar() == 'I') 1 else 2
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
