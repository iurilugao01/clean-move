package com.cleanmove.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cleanmove.data.geocontrol.static.GeoAssociation
import com.cleanmove.data.geocontrol.static.GeoTrip
import com.cleanmove.data.local.AppDatabase
import com.cleanmove.data.local.StopOffsetEntity
import com.cleanmove.domain.DayType
import com.cleanmove.domain.GeoStop
import com.cleanmove.domain.Line
import com.cleanmove.domain.LineCatalog
import com.cleanmove.domain.LineDetail
import com.cleanmove.domain.ScheduledDeparture
import com.cleanmove.domain.StopCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun stop(id: Int, lat: Double = -20.30, lng: Double = -40.30) =
    GeoStop(id, null, null, null, null, lat, lng, false)

private class EstimatedFixedStopCatalog(
    val routesByStop: Map<Int, List<Int>> = emptyMap(),
    val lineIdByRoute: Map<Int, Int> = emptyMap(),
    val associationsByRoute: Map<Int, List<GeoAssociation>> = emptyMap(),
    val tripsByRoute: Map<Int, List<GeoTrip>> = emptyMap(),
    val stopsById: Map<Int, GeoStop> = emptyMap(),
    val routeCodeByRoute: Map<Int, String?> = emptyMap(),
) : StopCatalog {
    var tripsCalls = 0
    override fun observeStops(query: String): Flow<List<GeoStop>> = flowOf(emptyList())
    override suspend fun refreshIfStale() = Result.success(Unit)
    override suspend fun stop(id: Int): GeoStop? = stopsById[id]
    override suspend fun lineCode(lineId: Int): String? = null
    override suspend fun lineIdForRoute(routeId: Int): Int? = lineIdByRoute[routeId]
    override suspend fun routesServing(stopId: Int): List<Int> = routesByStop[stopId].orEmpty()
    override suspend fun stopOrderWithinRoute(routeId: Int): List<GeoAssociation> = associationsByRoute[routeId].orEmpty()
    override suspend fun tripsFor(routeId: Int): List<GeoTrip> { tripsCalls++; return tripsByRoute[routeId].orEmpty() }
    override suspend fun routeCode(routeId: Int): String? = routeCodeByRoute[routeId]
}

private class EstimatedFixedLineCatalog(private val details: Map<String, LineDetail>) : LineCatalog {
    override fun observeLines(query: String): Flow<List<Line>> = flowOf(emptyList())
    override suspend fun refreshLinesIfStale() = Result.success(Unit)
    override suspend fun loadDetail(code: String): Result<LineDetail> =
        details[code]?.let { Result.success(it) } ?: Result.failure(NoSuchElementException())
    override suspend fun lineByCode(code: String): Line? = details[code]?.line
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EstimatedArrivalsRepositoryTest {
    private lateinit var db: AppDatabase
    private val line0010 = Line("0010", "NOVA PALESTINA / FRADINHOS")
    private val weekdaySchedule = ScheduledDeparture("0010", DayType.WEEKDAY, 1, "NOVA PALESTINA", minuteOfDay = 480, notes = emptySet())

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun `uses an already-cached offset, marking the result as interpolated`() = runTest {
        db.geoDao().putStopOffsets(listOf(StopOffsetEntity(routeId = 1, stopId = 404, offsetMinutes = 7, sampleCount = 5, computedAtMs = 0)))
        val stopCatalog = EstimatedFixedStopCatalog(routesByStop = mapOf(404 to listOf(1)), lineIdByRoute = mapOf(1 to 776))
        val lineCatalog = EstimatedFixedLineCatalog(mapOf("0010" to LineDetail(line0010, listOf(weekdaySchedule), emptyList())))
        // geo code "010" + Ceturb "0" prefix resolves to line0010
        val repo = EstimatedArrivalsRepository(stopCatalog, db.geoDao(), LineCodeResolver(FakeGeoCodeCatalog(776, "010"), lineCatalog), lineCatalog, now = { 0 })

        val arrival = repo.forStop(404, nowMinuteOfDay = 480, dayType = DayType.WEEKDAY).single()
        assertEquals(487, arrival.minuteOfDay) // 480 + 7
        assertTrue(arrival.isInterpolated)
    }

    @Test fun `calibrates lazily when no offset is cached, then reuses it without recomputing`() = runTest {
        val stops = mapOf(404 to stop(404, -20.30, -40.30), 405 to stop(405, -20.31, -40.30))
        val associations = listOf(GeoAssociation(1, 1, 404), GeoAssociation(1, 2, 405))
        val trips = listOf(
            GeoTrip(1, 0, 20 * 60_000L), GeoTrip(1, 1, 22 * 60_000L), GeoTrip(1, 2, 18 * 60_000L),
        )
        val stopCatalog = EstimatedFixedStopCatalog(
            routesByStop = mapOf(405 to listOf(1)), lineIdByRoute = mapOf(1 to 776),
            associationsByRoute = mapOf(1 to associations), tripsByRoute = mapOf(1 to trips), stopsById = stops,
        )
        val geoCatalog = FakeGeoCodeCatalog(776, "010")
        val lineCatalog = EstimatedFixedLineCatalog(mapOf("0010" to LineDetail(line0010, listOf(weekdaySchedule), emptyList())))
        val repo = EstimatedArrivalsRepository(stopCatalog, db.geoDao(), LineCodeResolver(geoCatalog, lineCatalog), lineCatalog, now = { 0 })

        val first = repo.forStop(405, nowMinuteOfDay = 480, dayType = DayType.WEEKDAY)
        assertTrue(first.single().isInterpolated)
        assertEquals(1, stopCatalog.tripsCalls)

        repo.forStop(405, nowMinuteOfDay = 480, dayType = DayType.WEEKDAY)
        assertEquals(1, stopCatalog.tripsCalls) // cached now, no second calibration pass
    }

    @Test fun `too few trip samples falls back to the plain terminal time, not interpolated`() = runTest {
        val stopCatalog = EstimatedFixedStopCatalog(
            routesByStop = mapOf(404 to listOf(1)), lineIdByRoute = mapOf(1 to 776),
            associationsByRoute = mapOf(1 to listOf(GeoAssociation(1, 1, 404))),
            tripsByRoute = mapOf(1 to listOf(GeoTrip(1, 0, 60_000L))), // only 1 sample
            stopsById = mapOf(404 to stop(404)),
        )
        val geoCatalog = FakeGeoCodeCatalog(776, "010")
        val lineCatalog = EstimatedFixedLineCatalog(mapOf("0010" to LineDetail(line0010, listOf(weekdaySchedule), emptyList())))
        val repo = EstimatedArrivalsRepository(stopCatalog, db.geoDao(), LineCodeResolver(geoCatalog, lineCatalog), lineCatalog, now = { 0 })

        val arrival = repo.forStop(404, nowMinuteOfDay = 480, dayType = DayType.WEEKDAY).single()
        assertEquals(480, arrival.minuteOfDay)
        assertTrue(!arrival.isInterpolated)
    }

    @Test fun `an unresolved line is omitted, other lines still show`() = runTest {
        val resolved = LineDetail(line0010, listOf(weekdaySchedule), emptyList())
        val lineCatalog = EstimatedFixedLineCatalog(mapOf("0010" to resolved))
        val stopCatalog = EstimatedFixedStopCatalog(
            routesByStop = mapOf(404 to listOf(1, 2)),
            lineIdByRoute = mapOf(1 to 1, 2 to 776), // route 1's geo line id (1) has no Ceturb code -> unresolved
        )
        val geoCatalog = FakeGeoCodeCatalog(776, "010") // only line 776 has a known Geocontrol code
        val repo = EstimatedArrivalsRepository(stopCatalog, db.geoDao(), LineCodeResolver(geoCatalog, lineCatalog), lineCatalog, now = { 0 })

        assertEquals(1, repo.forStop(404, nowMinuteOfDay = 480, dayType = DayType.WEEKDAY).size)
    }

    @Test fun `filters by day type and keeps only departures inside the 60-minute window`() = runTest {
        val detail = LineDetail(line0010, listOf(
            weekdaySchedule.copy(minuteOfDay = 480),      // in window, right now
            weekdaySchedule.copy(minuteOfDay = 560),       // 80 min away, outside the window
            weekdaySchedule.copy(dayType = DayType.SATURDAY, minuteOfDay = 490), // wrong day type
        ), emptyList())
        val lineCatalog = EstimatedFixedLineCatalog(mapOf("0010" to detail))
        val stopCatalog = EstimatedFixedStopCatalog(routesByStop = mapOf(404 to listOf(1)), lineIdByRoute = mapOf(1 to 776))
        val geoCatalog = FakeGeoCodeCatalog(776, "010")
        val repo = EstimatedArrivalsRepository(stopCatalog, db.geoDao(), LineCodeResolver(geoCatalog, lineCatalog), lineCatalog, now = { 0 })

        val result = repo.forStop(404, nowMinuteOfDay = 480, dayType = DayType.WEEKDAY)
        assertEquals(listOf(480), result.map { it.minuteOfDay })
    }

    @Test fun `two routes resolving to the same line but different directions each surface only their own direction, deduplicated`() = runTest {
        // route 1 -> ida ("...I"), route 2 -> volta ("...V"), both resolve to the same line 0010.
        val outbound = weekdaySchedule.copy(direction = 1, minuteOfDay = 480, terminalName = "NOVA PALESTINA")
        val inbound = weekdaySchedule.copy(direction = 2, minuteOfDay = 485, terminalName = "FRADINHOS")
        val detail = LineDetail(line0010, listOf(outbound, inbound), emptyList())
        val lineCatalog = EstimatedFixedLineCatalog(mapOf("0010" to detail))
        val stopCatalog = EstimatedFixedStopCatalog(
            routesByStop = mapOf(404 to listOf(1, 2)),
            lineIdByRoute = mapOf(1 to 776, 2 to 776),
            routeCodeByRoute = mapOf(1 to "010I", 2 to "010V"),
        )
        val geoCatalog = FakeGeoCodeCatalog(776, "010")
        val repo = EstimatedArrivalsRepository(stopCatalog, db.geoDao(), LineCodeResolver(geoCatalog, lineCatalog), lineCatalog, now = { 0 })

        val result = repo.forStop(404, nowMinuteOfDay = 480, dayType = DayType.WEEKDAY)

        // exactly one departure per direction, no duplicates, and loadDetail was effectively used once per distinct line.
        assertEquals(listOf(480, 485), result.map { it.minuteOfDay }.sorted())
        assertEquals(2, result.size)
    }

    @Test fun `two routes resolving to the same line and same direction are deduplicated to a single departure`() = runTest {
        val detail = LineDetail(line0010, listOf(weekdaySchedule.copy(direction = 1, minuteOfDay = 480)), emptyList())
        val lineCatalog = EstimatedFixedLineCatalog(mapOf("0010" to detail))
        val stopCatalog = EstimatedFixedStopCatalog(
            routesByStop = mapOf(404 to listOf(1, 2)),
            lineIdByRoute = mapOf(1 to 776, 2 to 776),
            routeCodeByRoute = mapOf(1 to "010I", 2 to "010I"),
        )
        val geoCatalog = FakeGeoCodeCatalog(776, "010")
        val repo = EstimatedArrivalsRepository(stopCatalog, db.geoDao(), LineCodeResolver(geoCatalog, lineCatalog), lineCatalog, now = { 0 })

        val result = repo.forStop(404, nowMinuteOfDay = 480, dayType = DayType.WEEKDAY)

        assertEquals(1, result.size) // not duplicated once per route
    }

    @Test fun `when a route's direction cannot be determined, that line's schedule is not filtered by direction and does not crash`() = runTest {
        val detail = LineDetail(line0010, listOf(
            weekdaySchedule.copy(direction = 1, minuteOfDay = 480),
            weekdaySchedule.copy(direction = 2, minuteOfDay = 481),
        ), emptyList())
        val lineCatalog = EstimatedFixedLineCatalog(mapOf("0010" to detail))
        val stopCatalog = EstimatedFixedStopCatalog(
            routesByStop = mapOf(404 to listOf(1)),
            lineIdByRoute = mapOf(1 to 776),
            routeCodeByRoute = mapOf(1 to null), // unknown direction
        )
        val geoCatalog = FakeGeoCodeCatalog(776, "010")
        val repo = EstimatedArrivalsRepository(stopCatalog, db.geoDao(), LineCodeResolver(geoCatalog, lineCatalog), lineCatalog, now = { 0 })

        val result = repo.forStop(404, nowMinuteOfDay = 480, dayType = DayType.WEEKDAY)

        // unknown direction -> fallback keeps both directions, undeduplicated-by-direction but still only one loadDetail/line.
        assertEquals(listOf(480, 481), result.map { it.minuteOfDay }.sorted())
    }

    @Test fun `multiple departures of the same line inside the window all appear`() = runTest {
        val detail = LineDetail(line0010, listOf(weekdaySchedule.copy(minuteOfDay = 480), weekdaySchedule.copy(minuteOfDay = 520)), emptyList())
        val lineCatalog = EstimatedFixedLineCatalog(mapOf("0010" to detail))
        val stopCatalog = EstimatedFixedStopCatalog(routesByStop = mapOf(404 to listOf(1)), lineIdByRoute = mapOf(1 to 776))
        val geoCatalog = FakeGeoCodeCatalog(776, "010")
        val repo = EstimatedArrivalsRepository(stopCatalog, db.geoDao(), LineCodeResolver(geoCatalog, lineCatalog), lineCatalog, now = { 0 })

        assertEquals(listOf(480, 520), repo.forStop(404, nowMinuteOfDay = 480, dayType = DayType.WEEKDAY).map { it.minuteOfDay })
    }
}

/** A StopCatalog that only answers lineCode(lineId), for LineCodeResolver's needs in these tests. */
private class FakeGeoCodeCatalog(private val lineId: Int, private val code: String) : StopCatalog {
    override fun observeStops(query: String): Flow<List<GeoStop>> = flowOf(emptyList())
    override suspend fun refreshIfStale() = Result.success(Unit)
    override suspend fun stop(id: Int): GeoStop? = null
    override suspend fun lineCode(lineId: Int): String? = if (lineId == this.lineId) code else null
    override suspend fun lineIdForRoute(routeId: Int): Int? = null
    override suspend fun routesServing(stopId: Int): List<Int> = emptyList()
    override suspend fun stopOrderWithinRoute(routeId: Int): List<GeoAssociation> = emptyList()
    override suspend fun tripsFor(routeId: Int): List<GeoTrip> = emptyList()
    override suspend fun routeCode(routeId: Int): String? = null
}
