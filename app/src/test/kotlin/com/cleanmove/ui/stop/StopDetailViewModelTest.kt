package com.cleanmove.ui.stop

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.cleanmove.domain.DayType
import com.cleanmove.domain.EstimatedArrivals
import com.cleanmove.domain.EstimatedStopArrival
import com.cleanmove.domain.GeoStop
import com.cleanmove.domain.LiveArrivals
import com.cleanmove.domain.LiveStopArrival
import com.cleanmove.domain.Reliability
import com.cleanmove.domain.StopCatalog
import com.cleanmove.data.geocontrol.static.GeoAssociation
import com.cleanmove.data.geocontrol.static.GeoTrip
import com.cleanmove.ui.MainDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

private class FakeLiveArrivals(var result: Result<List<LiveStopArrival>>) : LiveArrivals {
    var calls = 0
    override suspend fun forStop(stopId: Int, nowMs: Long): Result<List<LiveStopArrival>> { calls++; return result }
}

private class FakeEstimatedArrivals(var result: List<EstimatedStopArrival> = emptyList()) : EstimatedArrivals {
    override suspend fun forStop(stopId: Int, nowMinuteOfDay: Int, dayType: DayType): List<EstimatedStopArrival> = result
}

private class FakeStopCatalog(private val label: GeoStop?) : StopCatalog {
    override fun observeStops(query: String): Flow<List<GeoStop>> = flowOf(emptyList())
    override suspend fun refreshIfStale() = Result.success(Unit)
    override suspend fun stop(id: Int): GeoStop? = label
    override suspend fun lineCode(lineId: Int): String? = null
    override suspend fun lineIdForRoute(routeId: Int): Int? = null
    override suspend fun routesServing(stopId: Int): List<Int> = emptyList()
    override suspend fun stopOrderWithinRoute(routeId: Int): List<GeoAssociation> = emptyList()
    override suspend fun tripsFor(routeId: Int): List<GeoTrip> = emptyList()
}

private val fixedClock = { ZonedDateTime.of(2026, 10, 5, 8, 0, 0, 0, ZoneId.of("America/Sao_Paulo")) } // a Monday, 08:00

private fun liveArrival(code: String = "0010") = LiveStopArrival(code, null, "NOVA PALESTINA", "1234", 5, false, false, false, Reliability.GREEN)
private fun stopEntity() = GeoStop(404, "110001", null, "Avenida Paulino Muller", "Vitória", -20.31, -40.31, false)

class StopDetailViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private fun vm(live: FakeLiveArrivals, estimated: FakeEstimatedArrivals = FakeEstimatedArrivals()) =
        StopDetailViewModel(SavedStateHandle(mapOf("stopId" to 404)), live, estimated, FakeStopCatalog(stopEntity()), clock = fixedClock)

    @Test fun `shows the stop's street as the label and loads live arrivals by default`() = runTest {
        val vm = vm(FakeLiveArrivals(Result.success(listOf(liveArrival()))))
        vm.state.test {
            val s = expectMostRecentItem()
            assertEquals("Avenida Paulino Muller", s.stopLabel)
            assertEquals(StopMode.LIVE, s.mode)
            assertFalse(s.loading)
            assertEquals(1, s.liveRows.size)
            cancel()
        }
    }

    @Test fun `a live failure auto-switches to Estimates and flags it as an automatic fallback`() = runTest {
        val vm = vm(FakeLiveArrivals(Result.failure(RuntimeException())), FakeEstimatedArrivals(listOf(EstimatedStopArrival("0010", "X", 480, true))))
        vm.state.test {
            val s = expectMostRecentItem()
            assertEquals(StopMode.ESTIMATES, s.mode)
            assertTrue(s.autoFallback)
            assertEquals(1, s.estimatedRows.size)
            cancel()
        }
    }

    @Test fun `an empty live result also auto-switches to Estimates`() = runTest {
        val vm = vm(FakeLiveArrivals(Result.success(emptyList())))
        vm.state.test {
            assertEquals(StopMode.ESTIMATES, expectMostRecentItem().mode)
            cancel()
        }
    }

    @Test fun `selecting Estimates manually is not flagged as an automatic fallback`() = runTest {
        val vm = vm(FakeLiveArrivals(Result.success(listOf(liveArrival()))))
        vm.state.test {
            expectMostRecentItem() // initial live load
            vm.selectMode(StopMode.ESTIMATES)
            val s = expectMostRecentItem()
            assertEquals(StopMode.ESTIMATES, s.mode)
            assertFalse(s.autoFallback)
            cancel()
        }
    }

    @Test fun `switching back to Ao vivo retries the live call instead of staying on a stale result`() = runTest {
        val live = FakeLiveArrivals(Result.failure(RuntimeException()))
        val vm = vm(live)
        vm.state.test {
            expectMostRecentItem() // auto-fell back to Estimates
            assertEquals(1, live.calls)
            live.result = Result.success(listOf(liveArrival()))
            vm.selectMode(StopMode.LIVE)
            val s = expectMostRecentItem()
            assertEquals(StopMode.LIVE, s.mode)
            assertEquals(1, s.liveRows.size)
            assertEquals(2, live.calls)
            cancel()
        }
    }

    @Test fun `currentDayType maps Monday to WEEKDAY, Saturday to SATURDAY, Sunday to SUNDAY_HOLIDAY`() {
        assertEquals(DayType.WEEKDAY, currentDayType(DayOfWeek.MONDAY))
        assertEquals(DayType.WEEKDAY, currentDayType(DayOfWeek.FRIDAY))
        assertEquals(DayType.SATURDAY, currentDayType(DayOfWeek.SATURDAY))
        assertEquals(DayType.SUNDAY_HOLIDAY, currentDayType(DayOfWeek.SUNDAY))
    }
}
