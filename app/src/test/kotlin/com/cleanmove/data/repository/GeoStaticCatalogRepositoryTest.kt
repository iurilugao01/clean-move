package com.cleanmove.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.cleanmove.data.geocontrol.static.AssociacaoDto
import com.cleanmove.data.geocontrol.static.GeocontrolStaticSource
import com.cleanmove.data.geocontrol.static.StaticItinerarioDto
import com.cleanmove.data.geocontrol.static.StaticLinhaDto
import com.cleanmove.data.geocontrol.static.StaticPontoDto
import com.cleanmove.data.geocontrol.static.ViagemDto
import com.cleanmove.data.local.AppDatabase
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

private class FakeStaticSource : GeocontrolStaticSource {
    var fail = false
    var calls = 0
    var stops = listOf(StaticPontoDto(id = 404, codigo = "110001", municipio = "Vitória", logradouro = "Avenida Paulino Muller", longitude = -40.31, latitude = -20.31, terminal = false))
    var lines = listOf(StaticLinhaDto(id = 776, codigo = "010", descricao = "NOVA PALESTINA / FRADINHOS"))
    var routes = listOf(StaticItinerarioDto(id = 4804, codigo = "010I", bandeira = "NOVA PALESTINA / FRADINHOS", linhaId = 776))
    var associations = listOf(
        AssociacaoDto(itinerarioId = 4804, ordem = 1, pontoDeParadaId = 404),
        AssociacaoDto(itinerarioId = 4804, ordem = 2, pontoDeParadaId = 405),
    )
    var trips = listOf(ViagemDto(dataAgendada = 1000, linhaId = 776, itinerarioId = 4804, dataChegadaEstimada = 5000))

    override suspend fun stops() = check().let { stops }
    override suspend fun lines() = check().let { lines }
    override suspend fun routes() = check().let { routes }
    override suspend fun associations() = check().let { associations }
    override suspend fun tripsNext7Days() = check().let { trips }
    private fun check() { calls++; if (fail) throw IOException("offline") }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeoStaticCatalogRepositoryTest {
    private lateinit var db: AppDatabase
    private val source = FakeStaticSource()
    private var clock = 0L
    private lateinit var repo: GeoStaticCatalogRepository
    private val sevenDays = 7 * 24 * 60 * 60 * 1000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = GeoStaticCatalogRepository(source, db.geoDao(), now = { clock }, ttlMillis = sevenDays)
    }

    @After fun tearDown() = db.close()

    @Test fun `first refresh fetches all five endpoints, a second one inside the TTL does not`() = runTest {
        assertTrue(repo.refreshIfStale().isSuccess)
        assertTrue(repo.refreshIfStale().isSuccess)
        assertEquals(5, source.calls) // one call each for stops/lines/routes/associations/trips, exactly once
    }

    @Test fun `observeStops exposes cached stops and filters by query`() = runTest {
        repo.refreshIfStale()
        repo.observeStops("").test { assertEquals(1, awaitItem().size); cancel() }
        repo.observeStops("palestina").test { assertEquals(0, awaitItem().size); cancel() } // query matches code/street/municipality, not line names
        repo.observeStops("paulino").test { assertEquals(1, awaitItem().size); cancel() }
    }

    @Test fun `stop(id) returns a cached stop or null`() = runTest {
        repo.refreshIfStale()
        assertEquals(404, repo.stop(404)?.id)
        assertEquals(null, repo.stop(9999))
    }

    @Test fun `lineCode resolves a Geocontrol line id to its own code`() = runTest {
        repo.refreshIfStale()
        assertEquals("010", repo.lineCode(776))
        assertEquals(null, repo.lineCode(9999))
    }

    @Test fun `lineIdForRoute and routesServing navigate route-stop-line relationships`() = runTest {
        repo.refreshIfStale()
        assertEquals(776, repo.lineIdForRoute(4804))
        assertEquals(listOf(4804), repo.routesServing(404))
        assertTrue(repo.routesServing(9999).isEmpty())
    }

    @Test fun `stopOrderWithinRoute returns associations ordered by sequence`() = runTest {
        repo.refreshIfStale()
        val order = repo.stopOrderWithinRoute(4804)
        assertEquals(listOf(404, 405), order.map { it.stopId })
        assertEquals(listOf(1, 2), order.map { it.order })
    }

    @Test fun `tripsFor filters the cached trip dump by route`() = runTest {
        repo.refreshIfStale()
        assertEquals(1, repo.tripsFor(4804).size)
        assertTrue(repo.tripsFor(9999).isEmpty())
    }

    @Test fun `a failed refresh with an empty cache fails, with cache it keeps serving the old data`() = runTest {
        source.fail = true
        assertTrue(repo.refreshIfStale().isFailure)

        source.fail = false
        repo.refreshIfStale()
        clock += sevenDays + 1
        source.fail = true
        assertTrue(repo.refreshIfStale().isFailure)
        assertEquals(404, repo.stop(404)?.id)
    }
}
