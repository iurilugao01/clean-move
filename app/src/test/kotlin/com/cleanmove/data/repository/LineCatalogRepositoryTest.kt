package com.cleanmove.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.cleanmove.data.ceturb.CeturbSource
import com.cleanmove.data.ceturb.ItineraryDto
import com.cleanmove.data.ceturb.LineDto
import com.cleanmove.data.ceturb.ScheduleDto
import com.cleanmove.data.local.AppDatabase
import com.cleanmove.domain.DayType
import com.cleanmove.domain.Line
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

private class FakeSource : CeturbSource {
    var fail = false
    var lineCalls = 0
    var detailCalls = 0
    var lines = listOf(LineDto("NOVA PALESTINA / FRADINHOS", "0010"), LineDto("TABUAZEIRO CIRCULAR", "0073"))
    var schedules = listOf(ScheduleDto("TERM", "DIAS ÚTEIS", "06:00", "0010", 1, "A"))
    var itinerary = listOf(ItineraryDto("RUA DO CAJU   ", "0010", "I", 1))

    override suspend fun lines(): List<LineDto> { lineCalls++; if (fail) throw IOException("offline"); return lines }
    override suspend fun schedules(code: String): List<ScheduleDto> { detailCalls++; if (fail) throw IOException("offline"); return schedules }
    override suspend fun itinerary(code: String): List<ItineraryDto> { if (fail) throw IOException("offline"); return itinerary }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LineCatalogRepositoryTest {
    private lateinit var db: AppDatabase
    private val source = FakeSource()
    private var clock = 0L
    private lateinit var repo: LineCatalogRepository
    private val day = 24L * 60 * 60 * 1000

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = LineCatalogRepository(source, db.catalogDao(), now = { clock }, ttlMillis = day)
    }

    @After fun tearDown() = db.close()

    @Test fun `first refresh stores lines and a second one inside the TTL does not hit the network`() = runTest {
        assertTrue(repo.refreshLinesIfStale().isSuccess)
        assertTrue(repo.refreshLinesIfStale().isSuccess)
        assertEquals(1, source.lineCalls)
        repo.observeLines("").test {
            assertEquals(listOf(Line("0010", "NOVA PALESTINA / FRADINHOS"), Line("0073", "TABUAZEIRO CIRCULAR")), awaitItem())
            cancel()
        }
    }

    @Test fun `refresh after the TTL fetches again`() = runTest {
        repo.refreshLinesIfStale()
        clock += day + 1
        repo.refreshLinesIfStale()
        assertEquals(2, source.lineCalls)
    }

    @Test fun `search matches name or code case-insensitively`() = runTest {
        repo.refreshLinesIfStale()
        repo.observeLines("tabuaz").test { assertEquals(listOf("0073"), awaitItem().map { it.code }); cancel() }
        repo.observeLines("0010").test { assertEquals(listOf("0010"), awaitItem().map { it.code }); cancel() }
    }

    @Test fun `failed refresh with an empty cache returns failure`() = runTest {
        source.fail = true
        assertTrue(repo.refreshLinesIfStale().isFailure)
    }

    @Test fun `failed refresh keeps the cached lines`() = runTest {
        repo.refreshLinesIfStale()
        clock += day + 1
        source.fail = true
        assertTrue(repo.refreshLinesIfStale().isFailure)
        repo.observeLines("").test { assertEquals(2, awaitItem().size); cancel() }
    }

    @Test fun `loadDetail fetches maps and caches schedules and itinerary`() = runTest {
        repo.refreshLinesIfStale()
        val detail = repo.loadDetail("0010").getOrThrow()
        assertEquals("0010", detail.line.code)
        assertEquals(DayType.WEEKDAY, detail.schedules.single().dayType)
        assertEquals(setOf("A"), detail.schedules.single().notes)
        assertEquals("RUA DO CAJU", detail.itinerary.single().street)
        repo.loadDetail("0010")
        assertEquals(1, source.detailCalls)
    }

    @Test fun `an empty schedule response is cached and not refetched`() = runTest {
        repo.refreshLinesIfStale()
        source.schedules = emptyList(); source.itinerary = emptyList()
        val detail = repo.loadDetail("0010").getOrThrow()
        assertTrue(detail.schedules.isEmpty())
        repo.loadDetail("0010")
        assertEquals(1, source.detailCalls)
    }

    @Test fun `loadDetail serves stale cache when the refetch fails`() = runTest {
        repo.refreshLinesIfStale()
        repo.loadDetail("0010")
        clock += day + 1
        source.fail = true
        assertEquals(1, repo.loadDetail("0010").getOrThrow().schedules.size)
    }

    @Test fun `loadDetail fails when offline with nothing cached`() = runTest {
        repo.refreshLinesIfStale()
        source.fail = true
        assertTrue(repo.loadDetail("0010").isFailure)
    }

    @Test fun `loadDetail fails for an unknown line`() = runTest {
        assertTrue(repo.loadDetail("9999").isFailure)
    }

    @Test fun `an empty lines response over a populated cache keeps the cache and fails`() = runTest {
        repo.refreshLinesIfStale()
        clock += day + 1
        source.lines = emptyList()
        assertTrue(repo.refreshLinesIfStale().isFailure)
        repo.observeLines("").test { assertEquals(2, awaitItem().size); cancel() }
    }

    @Test fun `an empty lines response on first load fails and is not cached as fresh`() = runTest {
        source.lines = emptyList()
        assertTrue(repo.refreshLinesIfStale().isFailure)
        source.lines = listOf(LineDto("TABUAZEIRO CIRCULAR", "0073"))
        assertTrue(repo.refreshLinesIfStale().isSuccess)
        assertEquals(2, source.lineCalls)
        repo.observeLines("").test { assertEquals(listOf("0073"), awaitItem().map { it.code }); cancel() }
    }

    @Test fun `an empty detail response over a populated cache keeps the stale detail`() = runTest {
        repo.refreshLinesIfStale()
        repo.loadDetail("0010")
        clock += day + 1
        source.schedules = emptyList(); source.itinerary = emptyList()
        val detail = repo.loadDetail("0010").getOrThrow()
        assertEquals(1, detail.schedules.size)
        assertEquals(1, detail.itinerary.size)
        repo.loadDetail("0010")
        assertEquals(3, source.detailCalls)
    }

    @Test fun `a new cache version ignores previously cached data`() = runTest {
        repo.refreshLinesIfStale()
        val upgraded = LineCatalogRepository(source, db.catalogDao(), now = { clock }, ttlMillis = day, cacheVersion = 2)
        upgraded.refreshLinesIfStale()
        assertEquals(2, source.lineCalls)
        upgraded.loadDetail("0010")
        repo.loadDetail("0010")
        assertEquals(2, source.detailCalls)
    }
}
