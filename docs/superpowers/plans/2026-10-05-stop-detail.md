# Stop Search & Stop Detail Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user search for a stop ("ponto") by text and open a screen that lists approaching buses, toggling between **Ao vivo** (Geocontrol live estimates) and **Estimativas** (Ceturb schedule + a locally-calibrated per-stop offset), with automatic fallback to Estimativas when the live call fails.

**Architecture:** A new Geocontrol **static** client (GET `pontual-api-web`, already live-confirmed this session) supplies the stop/line/route/association/trip catalog, cached in Room with a 7-day TTL. A pure local join (`LineCodeResolver`) maps a Geocontrol `linhaId` to the existing Ceturb `Line.code`. A pure local calibrator (`StopOffsetCalibrator`) derives, once per route and from cached data only, how many minutes past the terminal departure each stop is typically reached, and persists it — so "Estimativas" never needs a network call once the underlying static data is cached. The existing (branch `feat/geocontrol-client`) POST previsão client supplies live arrivals; this is the one call in the whole feature still unvalidated against a real server. `ArrivalMerger`/`Arrival`/`ScheduledArrival`/`LiveArrival` from Plan 1 are deleted — the per-item side-by-side design they implemented is replaced by this whole-screen mode toggle.

**Tech Stack:** Same as Plan 1 (Kotlin, Compose, Hilt, Room/KSP, Ktor+kotlinx.serialization, JUnit4, Turbine, Robolectric) plus nothing new.

**Spec:** `docs/superpowers/specs/2026-10-05-stop-detail-design.md` (supersedes design-spec v1 §2.1's endpoint table and §3.1's merge-based flow — read both; this plan argues from the newer one).

**Branch base:** `feat/geocontrol-client` (not `master`/`feat/foundation`) — this plan consumes `GeocontrolSource`/`GeocontrolArrival`/`Reliability` already built there (71 tests passing at that branch's tip).

## Global Constraints

- Geocontrol **static** host (GET, live-confirmed 2026-10-05): `https://gvbus.geocontrol.com.br/pontual-api-web/`. Methods used: `listarPontosDeParada`, `listarLinhas`, `listarItinerarios`, `listarAssociacaoPontoItinerario`, `listarViagensProximosSeteDias`. No auth, no request params.
- Geocontrol **previsão** host (POST, unvalidated): `https://gvbus-previsao.geocontrol.com.br/pontual-previsao` — already implemented as `GeocontrolApi`/`GeocontrolSource` on the base branch. Do not re-implement; consume it.
- Static cache TTL: **7 days** (structural data, low churn — spec §3.2). Live arrivals: no cache, fetched per screen view.
- Line-code join rule (confirmed, spike §"Line-code mapping"): Ceturb `Linha` minus its first character equals Geocontrol `codigo` for 393/396 lines. No match → that line is "unresolved": shown live with its raw Geocontrol label, no Estimativas for it.
- Calibration minimum sample size: **3 trips** with a non-null estimated arrival, per itinerary. Below that, no `StopOffsetEntity` is written for that itinerary's stops.
- Estimativas window: **60 minutes** from now; multiple upcoming departures per line, not just the next one (spec §4).
- `coerceInputValues = true`, `ignoreUnknownKeys = true` on every new JSON client, matching the established pattern — a single bad field must never fail a whole response.
- Database: entities are additive to the existing `AppDatabase` (version bump; `fallbackToDestructiveMigration()` already configured in `AppModule`, so no manual migration is written).
- Commit trailer: `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`.
- No outbound network call is made by any step in this plan — all tests use `MockEngine`/fakes. If a task's manual-verification step would require a real network call to a Geocontrol host, skip it and say so; do not attempt it.

## Review Focus

1. A stop with **no itinerary resolves to a Ceturb line code** (e.g. one of the 3 known mismatches): the live list must still show that bus (with the raw Geocontrol label), and Estimativas must simply omit that line rather than erroring the whole screen.
2. An itinerary with **fewer than 3 trip samples** (or none) in `listarViagensProximosSeteDias`: calibration must not write a `StopOffsetEntity`, and Estimativas for that line must fall back to the plain terminal departure time, unlabeled with `≈`.
3. The **live call fails or returns an empty estimate list**: the screen auto-switches to Estimativas and the toggle reflects that, without the user having to do anything — but switching back to "Ao vivo" manually must retry, not stay stuck on a cached empty result.
4. `listarAssociacaoPontoItinerario`/`listarViagensProximosSeteDias` are **large, unfilterable dumps** (55k/101k rows): a stop-detail screen open must never trigger a full re-download — only a stale/missing TTL does, and the Estimativas path must work from whatever is already cached even if the live refresh of these tables fails.
5. **Duplicate or out-of-order `ordem` values** in the association data for one itinerary (plausible in a 55k-row real dump, not seen in the sample but not ruled out): the calibrator must not crash or produce a negative/decreasing offset sequence.

---

## File Structure

```
app/src/main/kotlin/com/cleanmove/
├─ domain/
│  ├─ Models.kt                      (modify: remove LiveArrival/ScheduledArrival/Arrival; add GeoStop, LiveStopArrival, EstimatedStopArrival)
│  ├─ ArrivalMerger.kt                (delete)
│  └─ Repositories.kt                 (modify: add StopCatalog, LiveArrivals, EstimatedArrivals interfaces)
├─ data/geocontrol/static/
│  ├─ GeocontrolStaticDtos.kt         (new)
│  ├─ GeocontrolStaticMappers.kt      (new)
│  └─ GeocontrolStaticApi.kt          (new)
├─ data/local/
│  ├─ GeoEntities.kt                  (new: GeoStopEntity, GeoLineEntity, GeoRouteEntity, GeoAssociationEntity, StopOffsetEntity)
│  ├─ GeoDao.kt                       (new)
│  └─ AppDatabase.kt                  (modify: register new entities, bump version)
├─ data/repository/
│  ├─ GeoStaticCatalogRepository.kt   (new)
│  ├─ LineCodeResolver.kt             (new)
│  ├─ StopOffsetCalibrator.kt         (new)
│  ├─ LiveArrivalsRepository.kt       (new)
│  └─ EstimatedArrivalsRepository.kt  (new)
├─ ui/stop/
│  ├─ StopDetailViewModel.kt          (new)
│  └─ StopDetailScreen.kt             (new)
├─ ui/search/
│  ├─ SearchViewModel.kt              (modify: add stop results)
│  └─ SearchScreen.kt                 (modify: add "Pontos" section)
├─ ui/nav/CleanMoveApp.kt             (modify: add "stop/{id}" route)
└─ di/AppModule.kt                    (modify: new providers)
```

---

### Task 1: Remove the per-item merge model; add the per-mode domain types

**Files:**
- Delete: `app/src/main/kotlin/com/cleanmove/domain/ArrivalMerger.kt`, `app/src/test/kotlin/com/cleanmove/domain/ArrivalMergerTest.kt`
- Modify: `app/src/main/kotlin/com/cleanmove/domain/Models.kt`
- Test: `app/src/test/kotlin/com/cleanmove/domain/ModelsTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `data class GeoStop(val id: Int, val code: String?, val description: String?, val street: String?, val municipality: String?, val latitude: Double, val longitude: Double, val isTerminal: Boolean)`
  - `data class LiveStopArrival(val lineCode: String?, val rawLineLabel: String?, val lineName: String?, val vehicleLabel: String?, val minutesAway: Int, val hasAccessibility: Boolean, val hasAirConditioning: Boolean, val hasWifi: Boolean, val reliability: Reliability)`
  - `data class EstimatedStopArrival(val lineCode: String, val lineName: String, val minuteOfDay: Int, val isInterpolated: Boolean)`
  - `LiveArrival`, `ScheduledArrival`, `Arrival` no longer exist.

- [ ] **Step 1: Write a test pinning the new/changed model shapes**

```kotlin
// app/src/test/kotlin/com/cleanmove/domain/ModelsTest.kt
package com.cleanmove.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsTest {
    @Test
    fun `GeoStop holds optional display fields and required coordinates`() {
        val stop = GeoStop(
            id = 404, code = "110001", description = null, street = "Avenida Paulino Muller",
            municipality = "Vitória", latitude = -20.317, longitude = -40.3196, isTerminal = false,
        )
        assertEquals(404, stop.id)
        assertEquals("110001", stop.code)
    }

    @Test
    fun `LiveStopArrival can represent an unresolved line with only the raw Geocontrol label`() {
        val arrival = LiveStopArrival(
            lineCode = null, rawLineLabel = "325", lineName = null, vehicleLabel = "1234",
            minutesAway = 5, hasAccessibility = false, hasAirConditioning = true, hasWifi = false,
            reliability = Reliability.GREEN,
        )
        assertEquals(null, arrival.lineCode)
        assertEquals("325", arrival.rawLineLabel)
    }

    @Test
    fun `EstimatedStopArrival marks whether the time was interpolated`() {
        val plain = EstimatedStopArrival("0010", "NOVA PALESTINA / FRADINHOS", 480, isInterpolated = false)
        val calibrated = plain.copy(isInterpolated = true)
        assertEquals(false, plain.isInterpolated)
        assertEquals(true, calibrated.isInterpolated)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd app && ../gradlew :app:testDebugUnitTest --tests "com.cleanmove.domain.ModelsTest"` (or `.\gradlew.bat` on Windows PowerShell)
Expected: FAIL — compilation error, `GeoStop`/`LiveStopArrival`/`EstimatedStopArrival` unresolved.

- [ ] **Step 3: Delete the old merge model and add the new types**

Delete `app/src/main/kotlin/com/cleanmove/domain/ArrivalMerger.kt` and `app/src/test/kotlin/com/cleanmove/domain/ArrivalMergerTest.kt`.

Edit `app/src/main/kotlin/com/cleanmove/domain/Models.kt`: remove `LiveArrival`, `ScheduledArrival`, `Arrival`; append:

```kotlin
data class GeoStop(
    val id: Int,
    val code: String?,
    val description: String?,
    val street: String?,
    val municipality: String?,
    val latitude: Double,
    val longitude: Double,
    val isTerminal: Boolean,
)

/** One live bus, as shown in "Ao vivo" mode. [lineCode] is null when the Geocontrol
 * linhaId could not be resolved to a Ceturb line — [rawLineLabel] is shown instead. */
data class LiveStopArrival(
    val lineCode: String?,
    val rawLineLabel: String?,
    val lineName: String?,
    val vehicleLabel: String?,
    val minutesAway: Int,
    val hasAccessibility: Boolean,
    val hasAirConditioning: Boolean,
    val hasWifi: Boolean,
    val reliability: Reliability,
)

/** One scheduled departure, as shown in "Estimativas" mode. [isInterpolated] is true
 * when [minuteOfDay] includes a calibrated per-stop offset; false when it is the plain
 * terminal departure time (no calibration available yet for this line). */
data class EstimatedStopArrival(
    val lineCode: String,
    val lineName: String,
    val minuteOfDay: Int,
    val isInterpolated: Boolean,
)
```

- [ ] **Step 4: Run to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.domain.*"`
Expected: `ModelsTest` 3/3 PASS; no `ArrivalMergerTest` listed (deleted); no other test in `domain` references the removed types (if one does, the brief is wrong — fix the brief's assumption, not the deleted types: nothing in Plan 1's UI consumed `ArrivalMerger`, so this should be a clean removal).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(domain): replace per-item arrival merge with per-mode stop arrival types" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 2: Geocontrol static client — DTOs and mappers

**Files:**
- Create: `app/src/main/kotlin/com/cleanmove/data/geocontrol/static/GeocontrolStaticDtos.kt`, `GeocontrolStaticMappers.kt`
- Test: `app/src/test/kotlin/com/cleanmove/data/geocontrol/static/GeocontrolStaticMappersTest.kt`

**Interfaces:**
- Consumes: `GeoStop` (Task 1).
- Produces:
  - DTOs: `StaticPontoDto`, `StaticLinhaDto`, `StaticItinerarioDto`, `AssociacaoDto`, `ViagemDto` (field names/types below, confirmed live on 2026-10-05).
  - `fun StaticPontoDto.toDomain(): GeoStop`
  - `data class GeoLine(val id: Int, val code: String, val name: String)`; `fun StaticLinhaDto.toDomain(): GeoLine`
  - `data class GeoRoute(val id: Int, val code: String?, val destinationLabel: String?, val lineId: Int)`; `fun StaticItinerarioDto.toDomain(): GeoRoute`
  - `data class GeoAssociation(val routeId: Int, val order: Int, val stopId: Int)`; `fun AssociacaoDto.toDomain(): GeoAssociation`
  - `data class GeoTrip(val routeId: Int, val scheduledAtMs: Long, val estimatedArrivalMs: Long?)`; `fun ViagemDto.toDomain(): GeoTrip?` (null when `dataAgendada` is missing/zero — a trip with no scheduled time is useless for calibration)

- [ ] **Step 1: Write the failing mapper tests**

```kotlin
// app/src/test/kotlin/com/cleanmove/data/geocontrol/static/GeocontrolStaticMappersTest.kt
package com.cleanmove.data.geocontrol.static

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeocontrolStaticMappersTest {
    @Test
    fun `maps a stop, trimming blank optional fields to null`() {
        val dto = StaticPontoDto(
            id = 404, codigo = "110001", municipio = "Vitória",
            logradouro = "Avenida Paulino Muller", referencia = "  ",
            longitude = -40.3196039018834, latitude = -20.3170426845619,
            azimute = -14, terminal = false,
        )
        val stop = dto.toDomain()
        assertEquals(404, stop.id)
        assertEquals("110001", stop.code)
        assertEquals("Avenida Paulino Muller", stop.street)
        assertNull(stop.description)
    }

    @Test
    fun `maps a line`() {
        val line = StaticLinhaDto(id = 744, codigo = "0015", descricao = "Jockey de Itaparica/São Torquato").toDomain()
        assertEquals(GeoLine(744, "0015", "Jockey de Itaparica/São Torquato"), line)
    }

    @Test
    fun `maps a route with its destination label and owning line`() {
        val route = StaticItinerarioDto(id = 4804, codigo = "010I", bandeira = "NOVA PALESTINA / FRADINHOS", linhaId = 776).toDomain()
        assertEquals(GeoRoute(4804, "010I", "NOVA PALESTINA / FRADINHOS", 776), route)
    }

    @Test
    fun `maps an association`() {
        val assoc = AssociacaoDto(itinerarioId = 510, ordem = 1, pontoDeParadaId = 5244, embarque = true, desembarque = false).toDomain()
        assertEquals(GeoAssociation(510, 1, 5244), assoc)
    }

    @Test
    fun `maps a trip with both timestamps`() {
        val trip = ViagemDto(
            dataAgendada = 1_791_192_420_000, linhaId = 380, itinerarioId = 5721,
            dataChegadaEstimada = 1_791_193_762_000, veiculo = 21254, acessibilidade = true,
        ).toDomain()
        assertEquals(GeoTrip(5721, 1_791_192_420_000, 1_791_193_762_000), trip)
    }

    @Test
    fun `a trip missing dataChegadaEstimada keeps a null estimate instead of dropping the trip`() {
        val trip = ViagemDto(dataAgendada = 1_791_192_420_000, linhaId = 380, itinerarioId = 5721, dataChegadaEstimada = null).toDomain()
        assertEquals(null, trip!!.estimatedArrivalMs)
    }

    @Test
    fun `a trip with no scheduled time maps to null`() {
        assertNull(ViagemDto(dataAgendada = 0, linhaId = 380, itinerarioId = 5721).toDomain())
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.data.geocontrol.static.GeocontrolStaticMappersTest"`
Expected: FAIL — unresolved references (package/classes don't exist yet).

- [ ] **Step 3: Implement the DTOs**

```kotlin
// app/src/main/kotlin/com/cleanmove/data/geocontrol/static/GeocontrolStaticDtos.kt
package com.cleanmove.data.geocontrol.static

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StaticPontoDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("codigo") val codigo: String? = null,
    @SerialName("municipio") val municipio: String? = null,
    @SerialName("logradouro") val logradouro: String? = null,
    @SerialName("referencia") val referencia: String? = null,
    @SerialName("longitude") val longitude: Double = 0.0,
    @SerialName("latitude") val latitude: Double = 0.0,
    @SerialName("azimute") val azimute: Int = 0,
    @SerialName("terminal") val terminal: Boolean = false,
)

@Serializable
data class StaticLinhaDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("codigo") val codigo: String = "",
    @SerialName("descricao") val descricao: String = "",
)

@Serializable
data class StaticItinerarioDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("codigo") val codigo: String? = null,
    @SerialName("bandeira") val bandeira: String? = null,
    @SerialName("linhaId") val linhaId: Int = 0,
)

@Serializable
data class AssociacaoDto(
    @SerialName("itinerarioId") val itinerarioId: Int = 0,
    @SerialName("ordem") val ordem: Int = 0,
    @SerialName("pontoDeParadaId") val pontoDeParadaId: Int = 0,
    @SerialName("embarque") val embarque: Boolean = false,
    @SerialName("desembarque") val desembarque: Boolean = false,
)

@Serializable
data class ViagemDto(
    @SerialName("dataAgendada") val dataAgendada: Long = 0,
    @SerialName("linhaId") val linhaId: Int = 0,
    @SerialName("itinerarioId") val itinerarioId: Int = 0,
    @SerialName("dataChegadaEstimada") val dataChegadaEstimada: Long? = null,
    @SerialName("veiculo") val veiculo: Int? = null,
    @SerialName("acessibilidade") val acessibilidade: Boolean = false,
)
```

- [ ] **Step 4: Implement the mappers**

```kotlin
// app/src/main/kotlin/com/cleanmove/data/geocontrol/static/GeocontrolStaticMappers.kt
package com.cleanmove.data.geocontrol.static

import com.cleanmove.domain.GeoStop

data class GeoLine(val id: Int, val code: String, val name: String)
data class GeoRoute(val id: Int, val code: String?, val destinationLabel: String?, val lineId: Int)
data class GeoAssociation(val routeId: Int, val order: Int, val stopId: Int)
data class GeoTrip(val routeId: Int, val scheduledAtMs: Long, val estimatedArrivalMs: Long?)

private fun String?.blankToNull(): String? = this?.trim()?.takeIf(String::isNotEmpty)

fun StaticPontoDto.toDomain(): GeoStop = GeoStop(
    id = id,
    code = codigo.blankToNull(),
    description = referencia.blankToNull(),
    street = logradouro.blankToNull(),
    municipality = municipio.blankToNull(),
    latitude = latitude,
    longitude = longitude,
    isTerminal = terminal,
)

fun StaticLinhaDto.toDomain(): GeoLine = GeoLine(id = id, code = codigo, name = descricao)

fun StaticItinerarioDto.toDomain(): GeoRoute = GeoRoute(
    id = id, code = codigo.blankToNull(), destinationLabel = bandeira.blankToNull(), lineId = linhaId,
)

fun AssociacaoDto.toDomain(): GeoAssociation = GeoAssociation(routeId = itinerarioId, order = ordem, stopId = pontoDeParadaId)

/** Null when there's no scheduled departure to anchor the trip to. */
fun ViagemDto.toDomain(): GeoTrip? {
    if (dataAgendada <= 0) return null
    return GeoTrip(routeId = itinerarioId, scheduledAtMs = dataAgendada, estimatedArrivalMs = dataChegadaEstimada)
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.data.geocontrol.static.*"`
Expected: 7 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(data): Geocontrol static DTOs and mappers (stops, lines, routes, associations, trips)" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 3: Geocontrol static API client (GET, live-confirmed host)

**Files:**
- Create: `app/src/main/kotlin/com/cleanmove/data/geocontrol/static/GeocontrolStaticApi.kt`
- Test: `app/src/test/kotlin/com/cleanmove/data/geocontrol/static/GeocontrolStaticApiTest.kt`

**Interfaces:**
- Consumes: DTOs from Task 2.
- Produces: `interface GeocontrolStaticSource { suspend fun stops(): List<StaticPontoDto>; suspend fun lines(): List<StaticLinhaDto>; suspend fun routes(): List<StaticItinerarioDto>; suspend fun associations(): List<AssociacaoDto>; suspend fun tripsNext7Days(): List<ViagemDto> }`; `class GeocontrolStaticApi(client: HttpClient, baseUrl: String = GeocontrolStaticApi.BASE_URL) : GeocontrolStaticSource`; `fun createGeocontrolStaticHttpClient(): HttpClient`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/kotlin/com/cleanmove/data/geocontrol/static/GeocontrolStaticApiTest.kt
package com.cleanmove.data.geocontrol.static

import com.cleanmove.data.geocontrol.geocontrolJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.expectSuccess
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeocontrolStaticApiTest {
    private fun api(body: String, onRequest: (String) -> Unit = {}): GeocontrolStaticApi {
        val engine = MockEngine { request ->
            onRequest(request.url.toString())
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(geocontrolJson, ContentType.Any) }
        }
        return GeocontrolStaticApi(client, baseUrl = "https://example.test/pontual-api-web")
    }

    @Test
    fun `stops hits listarPontosDeParada and parses a real-shaped row`() = runTest {
        var url = ""
        val api = api(
            """[{"id":404,"codigo":"110001","municipio":"Vitória","logradouro":"Avenida Paulino Muller","referencia":"x","longitude":-40.31,"latitude":-20.31,"azimute":-14,"terminal":false}]""",
        ) { url = it }
        val result = api.stops()
        assertEquals("https://example.test/pontual-api-web/listarPontosDeParada", url)
        assertEquals(404, result.single().id)
    }

    @Test
    fun `lines hits listarLinhas`() = runTest {
        var url = ""
        val api = api("""[{"id":744,"codigo":"0015","descricao":"Jockey de Itaparica/São Torquato"}]""") { url = it }
        assertEquals("https://example.test/pontual-api-web/listarLinhas", url)
        assertEquals("0015", api.lines().single().codigo)
    }

    @Test
    fun `routes hits listarItinerarios`() = runTest {
        var url = ""
        val api = api("""[{"id":4804,"codigo":"010I","bandeira":"NOVA PALESTINA / FRADINHOS","linhaId":776}]""") { url = it }
        assertEquals("https://example.test/pontual-api-web/listarItinerarios", url)
        assertEquals(776, api.routes().single().linhaId)
    }

    @Test
    fun `associations hits listarAssociacaoPontoItinerario and accepts an empty array`() = runTest {
        var url = ""
        val api = api("[]") { url = it }
        assertTrue(api.associations().isEmpty())
        assertEquals("https://example.test/pontual-api-web/listarAssociacaoPontoItinerario", url)
    }

    @Test
    fun `tripsNext7Days hits listarViagensProximosSeteDias`() = runTest {
        var url = ""
        val api = api(
            """[{"dataAgendada":1791192420000,"linhaId":380,"itinerarioId":5721,"dataChegadaEstimada":1791193762000,"veiculo":21254,"acessibilidade":true}]""",
        ) { url = it }
        assertEquals("https://example.test/pontual-api-web/listarViagensProximosSeteDias", url)
        assertEquals(5721, api.tripsNext7Days().single().itinerarioId)
    }

    @Test(expected = Exception::class)
    fun `http errors surface as exceptions`() = runTest {
        api("oops").let { }
        val engine = MockEngine { respond("oops", HttpStatusCode.InternalServerError) }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(geocontrolJson, ContentType.Any) }
        }
        GeocontrolStaticApi(client, baseUrl = "https://example.test/pontual-api-web").stops()
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.data.geocontrol.static.GeocontrolStaticApiTest"`
Expected: FAIL — `GeocontrolStaticApi` unresolved.

- [ ] **Step 3: Implement**

```kotlin
// app/src/main/kotlin/com/cleanmove/data/geocontrol/static/GeocontrolStaticApi.kt
package com.cleanmove.data.geocontrol.static

import com.cleanmove.data.geocontrol.geocontrolJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json

interface GeocontrolStaticSource {
    suspend fun stops(): List<StaticPontoDto>
    suspend fun lines(): List<StaticLinhaDto>
    suspend fun routes(): List<StaticItinerarioDto>
    suspend fun associations(): List<AssociacaoDto>
    suspend fun tripsNext7Days(): List<ViagemDto>
}

class GeocontrolStaticApi(
    private val client: HttpClient,
    private val baseUrl: String = BASE_URL,
) : GeocontrolStaticSource {
    override suspend fun stops(): List<StaticPontoDto> = client.get("$baseUrl/listarPontosDeParada").body()
    override suspend fun lines(): List<StaticLinhaDto> = client.get("$baseUrl/listarLinhas").body()
    override suspend fun routes(): List<StaticItinerarioDto> = client.get("$baseUrl/listarItinerarios").body()
    override suspend fun associations(): List<AssociacaoDto> = client.get("$baseUrl/listarAssociacaoPontoItinerario").body()
    override suspend fun tripsNext7Days(): List<ViagemDto> = client.get("$baseUrl/listarViagensProximosSeteDias").body()

    companion object {
        const val BASE_URL = "https://gvbus.geocontrol.com.br/pontual-api-web"
    }
}

fun createGeocontrolStaticHttpClient(): HttpClient = HttpClient(OkHttp) {
    expectSuccess = true
    install(HttpTimeout) {
        requestTimeoutMillis = 60_000 // the association/trip dumps are tens of thousands of rows
        connectTimeoutMillis = 10_000
    }
    install(ContentNegotiation) {
        json(geocontrolJson, ContentType.Any)
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.data.geocontrol.static.*"`
Expected: all static-package tests PASS (7 mapper + 6 API = 13).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(data): Geocontrol static HTTP client (stops, lines, routes, associations, trips)" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 4: Room cache and `StopCatalog` repository for the static Geocontrol data

**Files:**
- Create: `app/src/main/kotlin/com/cleanmove/data/local/GeoEntities.kt`, `GeoDao.kt`, `app/src/main/kotlin/com/cleanmove/data/repository/GeoStaticCatalogRepository.kt`
- Modify: `app/src/main/kotlin/com/cleanmove/data/local/AppDatabase.kt`
- Test: `app/src/test/kotlin/com/cleanmove/data/repository/GeoStaticCatalogRepositoryTest.kt`

**Interfaces:**
- Consumes: `GeocontrolStaticSource` and mappers (Task 2/3); `GeoStop` (Task 1).
- Produces:
  - `interface StopCatalog { fun observeStops(query: String): Flow<List<GeoStop>>; suspend fun refreshIfStale(): Result<Unit>; suspend fun stop(id: Int): GeoStop?; suspend fun lineCode(lineId: Int): String?; suspend fun lineIdForRoute(routeId: Int): Int?; suspend fun routesServing(stopId: Int): List<Int>; suspend fun stopOrderWithinRoute(routeId: Int): List<GeoAssociation>; suspend fun tripsFor(routeId: Int): List<GeoTrip> }` (add to `domain/Repositories.kt`; `GeoAssociation`/`GeoTrip` from Task 2 — note `domain/Repositories.kt` may import from `data/geocontrol/static` since Plan 1's existing interfaces already live alongside data-layer-free types; keep it to these two small data classes, nothing DTO-shaped)
  - `class GeoStaticCatalogRepository(source: GeocontrolStaticSource, dao: GeoDao, now: () -> Long = System::currentTimeMillis, ttlMillis: Long = 7 days) : StopCatalog`

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/kotlin/com/cleanmove/data/repository/GeoStaticCatalogRepositoryTest.kt
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
```

- [ ] **Step 2: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.data.repository.GeoStaticCatalogRepositoryTest"`
Expected: FAIL — `GeoDao`/`GeoStaticCatalogRepository` unresolved.

- [ ] **Step 3: Implement entities and DAO**

```kotlin
// app/src/main/kotlin/com/cleanmove/data/local/GeoEntities.kt
package com.cleanmove.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "geo_stops")
data class GeoStopEntity(
    @PrimaryKey val id: Int,
    val code: String?,
    val description: String?,
    val street: String?,
    val municipality: String?,
    val latitude: Double,
    val longitude: Double,
    val isTerminal: Boolean,
)

@Entity(tableName = "geo_lines")
data class GeoLineEntity(@PrimaryKey val id: Int, val code: String, val name: String)

@Entity(tableName = "geo_routes", indices = [Index("lineId")])
data class GeoRouteEntity(@PrimaryKey val id: Int, val code: String?, val destinationLabel: String?, val lineId: Int)

@Entity(tableName = "geo_associations", indices = [Index("routeId"), Index("stopId")])
data class GeoAssociationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeId: Int,
    val order: Int,
    val stopId: Int,
)

@Entity(tableName = "geo_trips", indices = [Index("routeId")])
data class GeoTripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeId: Int,
    val scheduledAtMs: Long,
    val estimatedArrivalMs: Long?,
)

@Entity(tableName = "stop_offsets", primaryKeys = ["routeId", "stopId"])
data class StopOffsetEntity(
    val routeId: Int,
    val stopId: Int,
    val offsetMinutes: Int,
    val sampleCount: Int,
    val computedAtMs: Long,
)
```

```kotlin
// app/src/main/kotlin/com/cleanmove/data/local/GeoDao.kt
package com.cleanmove.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface GeoDao {
    @Query("SELECT * FROM geo_stops WHERE code LIKE '%'||:query||'%' OR street LIKE '%'||:query||'%' OR municipality LIKE '%'||:query||'%' ORDER BY street")
    fun observeStops(query: String): Flow<List<GeoStopEntity>>

    @Query("SELECT * FROM geo_stops WHERE id = :id")
    suspend fun stop(id: Int): GeoStopEntity?

    @Query("SELECT code FROM geo_lines WHERE id = :lineId")
    suspend fun lineCode(lineId: Int): String?

    @Query("SELECT lineId FROM geo_routes WHERE id = :routeId")
    suspend fun lineIdForRoute(routeId: Int): Int?

    @Query("SELECT DISTINCT routeId FROM geo_associations WHERE stopId = :stopId")
    suspend fun routesServing(stopId: Int): List<Int>

    @Query("SELECT * FROM geo_associations WHERE routeId = :routeId ORDER BY `order`")
    suspend fun associationsForRoute(routeId: Int): List<GeoAssociationEntity>

    @Query("SELECT * FROM geo_trips WHERE routeId = :routeId")
    suspend fun tripsForRoute(routeId: Int): List<GeoTripEntity>

    @Query("DELETE FROM geo_stops") suspend fun deleteStops()
    @Query("DELETE FROM geo_lines") suspend fun deleteLines()
    @Query("DELETE FROM geo_routes") suspend fun deleteRoutes()
    @Query("DELETE FROM geo_associations") suspend fun deleteAssociations()
    @Query("DELETE FROM geo_trips") suspend fun deleteTrips()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertStops(items: List<GeoStopEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertLines(items: List<GeoLineEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertRoutes(items: List<GeoRouteEntity>)
    @Insert suspend fun insertAssociations(items: List<GeoAssociationEntity>)
    @Insert suspend fun insertTrips(items: List<GeoTripEntity>)

    @Transaction
    suspend fun replaceAll(
        stops: List<GeoStopEntity>,
        lines: List<GeoLineEntity>,
        routes: List<GeoRouteEntity>,
        associations: List<GeoAssociationEntity>,
        trips: List<GeoTripEntity>,
        meta: SyncMeta,
    ) {
        deleteStops(); deleteLines(); deleteRoutes(); deleteAssociations(); deleteTrips()
        insertStops(stops); insertLines(lines); insertRoutes(routes); insertAssociations(associations); insertTrips(trips)
        putGeoMeta(meta)
    }

    @Query("SELECT updatedAt FROM sync_meta WHERE `key` = :key") suspend fun geoUpdatedAt(key: String): Long?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putGeoMeta(meta: SyncMeta)

    @Query("SELECT * FROM stop_offsets WHERE routeId = :routeId AND stopId = :stopId")
    suspend fun stopOffset(routeId: Int, stopId: Int): StopOffsetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putStopOffsets(items: List<StopOffsetEntity>)
}
```

Modify `AppDatabase.kt`: add the five new entities to the `entities` array and the two offset/trip ones, bump `version` to 2, add `abstract fun geoDao(): GeoDao`:

```kotlin
@Database(
    entities = [
        LineEntity::class, ScheduleEntity::class, ItineraryEntity::class, SyncMeta::class,
        GeoStopEntity::class, GeoLineEntity::class, GeoRouteEntity::class, GeoAssociationEntity::class,
        GeoTripEntity::class, StopOffsetEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
    abstract fun geoDao(): GeoDao
}
```

- [ ] **Step 4: Implement the repository**

```kotlin
// app/src/main/kotlin/com/cleanmove/data/repository/GeoStaticCatalogRepository.kt
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

    private fun GeoStop.toEntity() = GeoStopEntity(id, code, description, street, municipality, latitude, longitude, isTerminal)
    private fun GeoAssociation.toEntity() = GeoAssociationEntity(routeId = routeId, order = order, stopId = stopId)
    private fun GeoTrip.toEntity() = GeoTripEntity(routeId = routeId, scheduledAtMs = scheduledAtMs, estimatedArrivalMs = estimatedArrivalMs)
}
```

```kotlin
// add to app/src/main/kotlin/com/cleanmove/domain/Repositories.kt
import com.cleanmove.data.geocontrol.static.GeoAssociation
import com.cleanmove.data.geocontrol.static.GeoTrip

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
```

- [ ] **Step 5: Run to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.data.repository.GeoStaticCatalogRepositoryTest"`
Expected: 8 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(data): Room cache and StopCatalog repository for Geocontrol static data" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 5: `LineCodeResolver` — join Geocontrol `linhaId` to the existing Ceturb `Line.code`

**Files:**
- Modify: `app/src/main/kotlin/com/cleanmove/domain/Repositories.kt` (add `lineByCode` to `LineCatalog`), `app/src/main/kotlin/com/cleanmove/data/repository/LineCatalogRepository.kt` (implement it), `app/src/test/kotlin/com/cleanmove/ui/FakeCatalog.kt` (implement the new interface member so existing ViewModel tests keep compiling)
- Create: `app/src/main/kotlin/com/cleanmove/data/repository/LineCodeResolver.kt`
- Test: `app/src/test/kotlin/com/cleanmove/data/repository/LineCodeResolverTest.kt`

**Interfaces:**
- Consumes: `StopCatalog.lineCode(lineId)` (Task 4), `LineCatalog` (Plan 1).
- Produces: `LineCatalog.lineByCode(code: String): Line?` (new interface member); `class LineCodeResolver(stopCatalog: StopCatalog, lineCatalog: LineCatalog) { suspend fun resolve(geoLineId: Int): Line? }`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/kotlin/com/cleanmove/data/repository/LineCodeResolverTest.kt
package com.cleanmove.data.repository

import com.cleanmove.data.geocontrol.static.GeoAssociation
import com.cleanmove.data.geocontrol.static.GeoTrip
import com.cleanmove.domain.GeoStop
import com.cleanmove.domain.Line
import com.cleanmove.domain.LineCatalog
import com.cleanmove.domain.LineDetail
import com.cleanmove.domain.StopCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FixedStopCatalog(private val codes: Map<Int, String>) : StopCatalog {
    override fun observeStops(query: String): Flow<List<GeoStop>> = flowOf(emptyList())
    override suspend fun refreshIfStale() = Result.success(Unit)
    override suspend fun stop(id: Int): GeoStop? = null
    override suspend fun lineCode(lineId: Int): String? = codes[lineId]
    override suspend fun lineIdForRoute(routeId: Int): Int? = null
    override suspend fun routesServing(stopId: Int): List<Int> = emptyList()
    override suspend fun stopOrderWithinRoute(routeId: Int): List<GeoAssociation> = emptyList()
    override suspend fun tripsFor(routeId: Int): List<GeoTrip> = emptyList()
}

private class FixedLineCatalog(private val lines: List<Line>) : LineCatalog {
    override fun observeLines(query: String): Flow<List<Line>> = flowOf(lines)
    override suspend fun refreshLinesIfStale() = Result.success(Unit)
    override suspend fun loadDetail(code: String): Result<LineDetail> = Result.failure(IllegalStateException())
    override suspend fun lineByCode(code: String): Line? = lines.firstOrNull { it.code == code }
}

class LineCodeResolverTest {
    @Test
    fun `resolves a Geocontrol line id by prefixing its code with the Ceturb zero`() = runTest {
        val resolver = LineCodeResolver(
            FixedStopCatalog(mapOf(776 to "010")),
            FixedLineCatalog(listOf(Line("0010", "NOVA PALESTINA / FRADINHOS"))),
        )
        assertEquals(Line("0010", "NOVA PALESTINA / FRADINHOS"), resolver.resolve(776))
    }

    @Test
    fun `returns null when the Geocontrol line id itself is unknown`() = runTest {
        val resolver = LineCodeResolver(FixedStopCatalog(emptyMap()), FixedLineCatalog(emptyList()))
        assertNull(resolver.resolve(9999))
    }

    @Test
    fun `returns null when the prefixed code has no match in the Ceturb catalog (a known exception line)`() = runTest {
        val resolver = LineCodeResolver(
            FixedStopCatalog(mapOf(1 to "79N")), // e.g. Geocontrol "79N" has no "079N" counterpart in Ceturb
            FixedLineCatalog(listOf(Line("0010", "X"))),
        )
        assertNull(resolver.resolve(1))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.data.repository.LineCodeResolverTest"`
Expected: FAIL — `LineCodeResolver` unresolved, `LineCatalog.lineByCode` unresolved.

- [ ] **Step 3: Add `lineByCode` to `LineCatalog` and implement it**

Edit `domain/Repositories.kt`, inside `interface LineCatalog`, add: `suspend fun lineByCode(code: String): Line?`

Edit `data/repository/LineCatalogRepository.kt`, add:

```kotlin
    override suspend fun lineByCode(code: String): Line? = dao.line(code)?.let { Line(it.code, it.name) }
```

Edit `app/src/test/kotlin/com/cleanmove/ui/FakeCatalog.kt`'s `FakeCatalog`, add:

```kotlin
    override suspend fun lineByCode(code: String): Line? = lines.value.firstOrNull { it.code == code }
```

- [ ] **Step 4: Implement `LineCodeResolver`**

```kotlin
// app/src/main/kotlin/com/cleanmove/data/repository/LineCodeResolver.kt
package com.cleanmove.data.repository

import com.cleanmove.domain.Line
import com.cleanmove.domain.LineCatalog
import com.cleanmove.domain.StopCatalog

/**
 * Joins a Geocontrol `linhaId` to this app's Ceturb-based [Line], using the rule confirmed
 * in the spike: a Ceturb code minus its leading "0" equals Geocontrol's own code for 393 of
 * 396 lines. The few that don't match (and any `linhaId` not in the static cache at all)
 * simply resolve to null — callers show the raw Geocontrol label instead, never an error.
 */
class LineCodeResolver(
    private val stopCatalog: StopCatalog,
    private val lineCatalog: LineCatalog,
) {
    suspend fun resolve(geoLineId: Int): Line? {
        val geoCode = stopCatalog.lineCode(geoLineId) ?: return null
        return lineCatalog.lineByCode("0$geoCode")
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.data.repository.*" --tests "com.cleanmove.ui.*"`
Expected: `LineCodeResolverTest` 3/3 PASS; all previously-passing `ui.*` ViewModel tests still PASS (confirms the `FakeCatalog` fix didn't regress anything).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(data): LineCodeResolver joins Geocontrol linhaId to the Ceturb line code" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 6: `StopOffsetCalibrator` — pure per-stop offset calculation

**Files:**
- Create: `app/src/main/kotlin/com/cleanmove/data/repository/StopOffsetCalibrator.kt`
- Test: `app/src/test/kotlin/com/cleanmove/data/repository/StopOffsetCalibratorTest.kt`

**Interfaces:**
- Consumes: `GeoAssociation`, `GeoTrip` (Task 2); `GeoStop` (Task 1).
- Produces: `object StopOffsetCalibrator { fun calibrate(associations: List<GeoAssociation>, stopsById: Map<Int, GeoStop>, trips: List<GeoTrip>, minSamples: Int = 3): Map<Int, Int> }` — stopId → offset in minutes past the route's terminal departure.

A Geocontrol trip only gives the duration of the **whole** route (`dataChegadaEstimada − dataAgendada`, confirmed in the spike as end-of-route, not per-stop). This distributes that average duration across stops **proportionally to cumulative straight-line (haversine) distance** between consecutive stops in route order — an approximation, which is why callers label it with `isInterpolated = true` rather than treating it as exact.

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/kotlin/com/cleanmove/data/repository/StopOffsetCalibratorTest.kt
package com.cleanmove.data.repository

import com.cleanmove.data.geocontrol.static.GeoAssociation
import com.cleanmove.data.geocontrol.static.GeoTrip
import com.cleanmove.domain.GeoStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StopOffsetCalibratorTest {
    private fun stop(id: Int, lat: Double, lng: Double) =
        GeoStop(id, null, null, null, null, latitude = lat, longitude = lng, isTerminal = false)

    // Three stops roughly 1km apart along a line, so distance (and thus offset) grows evenly.
    private val stops = mapOf(
        1 to stop(1, -20.30, -40.30),
        2 to stop(2, -20.31, -40.30),
        3 to stop(3, -20.32, -40.30),
    )
    private val associations = listOf(
        GeoAssociation(routeId = 10, order = 1, stopId = 1),
        GeoAssociation(routeId = 10, order = 2, stopId = 2),
        GeoAssociation(routeId = 10, order = 3, stopId = 3),
    )

    private fun trip(startMs: Long, durationMin: Long) =
        GeoTrip(routeId = 10, scheduledAtMs = startMs, estimatedArrivalMs = startMs + durationMin * 60_000)

    @Test
    fun `distributes the average trip duration proportionally to distance, increasing along the route`() {
        val trips = listOf(trip(0, 30), trip(100_000, 32), trip(200_000, 28))
        val offsets = StopOffsetCalibrator.calibrate(associations, stops, trips)

        assertEquals(0, offsets.getValue(1))
        assertTrue(offsets.getValue(2) in 1..29)
        assertTrue(offsets.getValue(2) < offsets.getValue(3))
        assertEquals(30, offsets.getValue(3)) // last stop gets ~the full average duration
    }

    @Test
    fun `fewer than minSamples valid trips yields no offsets`() {
        val offsets = StopOffsetCalibrator.calibrate(associations, stops, listOf(trip(0, 30), trip(1, 32)))
        assertTrue(offsets.isEmpty())
    }

    @Test
    fun `a trip with a null or non-positive duration does not count as a sample`() {
        val noEstimate = GeoTrip(routeId = 10, scheduledAtMs = 0, estimatedArrivalMs = null)
        val zeroOrNegative = GeoTrip(routeId = 10, scheduledAtMs = 1000, estimatedArrivalMs = 500)
        val offsets = StopOffsetCalibrator.calibrate(associations, stops, listOf(trip(0, 30), noEstimate, zeroOrNegative))
        assertTrue(offsets.isEmpty()) // only 1 valid sample, below the default minimum of 3
    }

    @Test
    fun `duplicate stopId entries and out-of-order ordem values never crash and stay non-decreasing`() {
        val messyAssociations = listOf(
            GeoAssociation(routeId = 10, order = 3, stopId = 3),
            GeoAssociation(routeId = 10, order = 1, stopId = 1),
            GeoAssociation(routeId = 10, order = 1, stopId = 1), // duplicate
            GeoAssociation(routeId = 10, order = 2, stopId = 2),
        )
        val offsets = StopOffsetCalibrator.calibrate(messyAssociations, stops, listOf(trip(0, 30), trip(1, 32), trip(2, 28)))
        assertEquals(3, offsets.size)
        assertTrue(offsets.getValue(1) <= offsets.getValue(2))
        assertTrue(offsets.getValue(2) <= offsets.getValue(3))
    }

    @Test
    fun `a stop missing from stopsById is skipped, not crashed on`() {
        val withUnknownStop = associations + GeoAssociation(routeId = 10, order = 4, stopId = 999)
        val offsets = StopOffsetCalibrator.calibrate(withUnknownStop, stops, listOf(trip(0, 30), trip(1, 32), trip(2, 28)))
        assertEquals(setOf(1, 2, 3), offsets.keys)
    }

    @Test
    fun `a single-stop route offsets to zero instead of dividing by zero distance`() {
        val one = listOf(GeoAssociation(routeId = 10, order = 1, stopId = 1))
        val offsets = StopOffsetCalibrator.calibrate(one, stops, listOf(trip(0, 30), trip(1, 32), trip(2, 28)))
        assertEquals(mapOf(1 to 0), offsets)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.data.repository.StopOffsetCalibratorTest"`
Expected: FAIL — `StopOffsetCalibrator` unresolved.

- [ ] **Step 3: Implement**

```kotlin
// app/src/main/kotlin/com/cleanmove/data/repository/StopOffsetCalibrator.kt
package com.cleanmove.data.repository

import com.cleanmove.data.geocontrol.static.GeoAssociation
import com.cleanmove.data.geocontrol.static.GeoTrip
import com.cleanmove.domain.GeoStop
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object StopOffsetCalibrator {
    fun calibrate(
        associations: List<GeoAssociation>,
        stopsById: Map<Int, GeoStop>,
        trips: List<GeoTrip>,
        minSamples: Int = 3,
    ): Map<Int, Int> {
        val durationsMin = trips
            .mapNotNull { trip -> trip.estimatedArrivalMs?.let { it - trip.scheduledAtMs } }
            .filter { it > 0 }
            .map { it / 60_000.0 }
        if (durationsMin.size < minSamples) return emptyMap()
        val avgDurationMin = durationsMin.average()

        val ordered = associations
            .sortedBy { it.order }
            .distinctBy { it.stopId }
            .mapNotNull { assoc -> stopsById[assoc.stopId]?.let { assoc.stopId to it } }
        if (ordered.isEmpty()) return emptyMap()
        if (ordered.size == 1) return mapOf(ordered.single().first to 0)

        val cumulativeKm = DoubleArray(ordered.size)
        for (i in 1 until ordered.size) {
            val (_, prev) = ordered[i - 1]
            val (_, curr) = ordered[i]
            cumulativeKm[i] = cumulativeKm[i - 1] + haversineKm(prev.latitude, prev.longitude, curr.latitude, curr.longitude)
        }
        val totalKm = cumulativeKm.last()

        return ordered.mapIndexed { i, (stopId, _) ->
            val fraction = if (totalKm > 0) cumulativeKm[i] / totalKm else 0.0
            stopId to (avgDurationMin * fraction).roundToInt()
        }.toMap()
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return earthRadiusKm * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.data.repository.StopOffsetCalibratorTest"`
Expected: 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(data): StopOffsetCalibrator (distance-weighted per-stop offset from trip samples)" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 7: `LiveArrivalsRepository` — the "Ao vivo" data source for a stop

**Files:**
- Create: `app/src/main/kotlin/com/cleanmove/data/repository/LiveArrivalsRepository.kt`
- Modify: `app/src/main/kotlin/com/cleanmove/domain/Repositories.kt` (add `LiveArrivals` interface)
- Test: `app/src/test/kotlin/com/cleanmove/data/repository/LiveArrivalsRepositoryTest.kt`

**Interfaces:**
- Consumes: `GeocontrolSource`, `GeocontrolArrival`, `toArrivals` (branch `feat/geocontrol-client`); `LineCodeResolver` (Task 5); `StopCatalog.lineCode` (Task 4); `LiveStopArrival` (Task 1).
- Produces: `interface LiveArrivals { suspend fun forStop(stopId: Int, nowMs: Long): Result<List<LiveStopArrival>> }`; `class LiveArrivalsRepository(source: GeocontrolSource, resolver: LineCodeResolver, stopCatalog: StopCatalog) : LiveArrivals`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/kotlin/com/cleanmove/data/repository/LiveArrivalsRepositoryTest.kt
package com.cleanmove.data.repository

import com.cleanmove.data.geocontrol.EstimativaDto
import com.cleanmove.data.geocontrol.EstimativasPorOrigemResponseDto
import com.cleanmove.data.geocontrol.GeocontrolSource
import com.cleanmove.data.geocontrol.ItinerarioDto
import com.cleanmove.data.geocontrol.ListarItinerariosResponseDto
import com.cleanmove.data.geocontrol.ListarPontosDeParadaResponseDto
import com.cleanmove.data.geocontrol.PesquisarPontosDeParadaResponseDto
import com.cleanmove.data.geocontrol.PontoDto
import com.cleanmove.data.geocontrol.UltimaPosicaoResponseDto
import com.cleanmove.data.geocontrol.static.GeoAssociation
import com.cleanmove.data.geocontrol.static.GeoTrip
import com.cleanmove.domain.GeoStop
import com.cleanmove.domain.Line
import com.cleanmove.domain.LineCatalog
import com.cleanmove.domain.LineDetail
import com.cleanmove.domain.StopCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private class FakeGeocontrolSource(var response: EstimativasPorOrigemResponseDto, var fail: Boolean = false) : GeocontrolSource {
    override suspend fun estimatesForStop(stopId: Int): EstimativasPorOrigemResponseDto {
        if (fail) throw IOException("offline")
        return response
    }
    override suspend fun searchStops(text: String?, envelope: List<Double>?) = PesquisarPontosDeParadaResponseDto()
    override suspend fun stops(ids: List<Int>) = ListarPontosDeParadaResponseDto()
    override suspend fun routes(ids: List<Int>) = ListarItinerariosResponseDto()
    override suspend fun vehiclePosition(vehicleIds: List<Int>) = UltimaPosicaoResponseDto()
}

private class FixedStopCatalog(private val codes: Map<Int, String> = emptyMap()) : StopCatalog {
    override fun observeStops(query: String): Flow<List<GeoStop>> = flowOf(emptyList())
    override suspend fun refreshIfStale() = Result.success(Unit)
    override suspend fun stop(id: Int): GeoStop? = null
    override suspend fun lineCode(lineId: Int): String? = codes[lineId]
    override suspend fun lineIdForRoute(routeId: Int): Int? = null
    override suspend fun routesServing(stopId: Int): List<Int> = emptyList()
    override suspend fun stopOrderWithinRoute(routeId: Int): List<GeoAssociation> = emptyList()
    override suspend fun tripsFor(routeId: Int): List<GeoTrip> = emptyList()
}

private class FixedLineCatalog(private val lines: List<Line>) : LineCatalog {
    override fun observeLines(query: String): Flow<List<Line>> = flowOf(lines)
    override suspend fun refreshLinesIfStale() = Result.success(Unit)
    override suspend fun loadDetail(code: String): Result<LineDetail> = Result.failure(IllegalStateException())
    override suspend fun lineByCode(code: String): Line? = lines.firstOrNull { it.code == code }
}

class LiveArrivalsRepositoryTest {
    private fun estimativa(linhaId: Int) = EstimativaDto(
        itinerarioId = 1, linhaId = linhaId, veiculo = "1234",
        horarioDePartida = 1000, horarioNaOrigem = 1000 + 5 * 60_000, horarioDaTransmissao = 1000,
        accessibility = true, arCondicionado = false, wifi = "ABC",
    )

    @Test fun `resolves the line code for a known Geocontrol line`() = runTest {
        val repo = LiveArrivalsRepository(
            FakeGeocontrolSource(EstimativasPorOrigemResponseDto(1, 1000, listOf(estimativa(776)))),
            LineCodeResolver(FixedStopCatalog(mapOf(776 to "010")), FixedLineCatalog(listOf(Line("0010", "NOVA PALESTINA / FRADINHOS")))),
            FixedStopCatalog(),
        )
        val arrival = repo.forStop(404, nowMs = 1000).getOrThrow().single()
        assertEquals("0010", arrival.lineCode)
        assertEquals("NOVA PALESTINA / FRADINHOS", arrival.lineName)
        assertEquals(null, arrival.rawLineLabel)
        assertTrue(arrival.hasAccessibility)
    }

    @Test fun `an unresolved line falls back to its own Geocontrol code, then to a numeric label`() = runTest {
        val withGeoCode = LiveArrivalsRepository(
            FakeGeocontrolSource(EstimativasPorOrigemResponseDto(1, 1000, listOf(estimativa(1)))),
            LineCodeResolver(FixedStopCatalog(mapOf(1 to "79N")), FixedLineCatalog(emptyList())),
            FixedStopCatalog(mapOf(1 to "79N")),
        )
        assertEquals("79N", withGeoCode.forStop(404, 1000).getOrThrow().single().rawLineLabel)

        val withoutGeoCode = LiveArrivalsRepository(
            FakeGeocontrolSource(EstimativasPorOrigemResponseDto(1, 1000, listOf(estimativa(9999)))),
            LineCodeResolver(FixedStopCatalog(), FixedLineCatalog(emptyList())),
            FixedStopCatalog(),
        )
        assertEquals("Linha 9999", withoutGeoCode.forStop(404, 1000).getOrThrow().single().rawLineLabel)
    }

    @Test fun `a source failure becomes Result failure`() = runTest {
        val repo = LiveArrivalsRepository(
            FakeGeocontrolSource(EstimativasPorOrigemResponseDto(), fail = true),
            LineCodeResolver(FixedStopCatalog(), FixedLineCatalog(emptyList())),
            FixedStopCatalog(),
        )
        assertTrue(repo.forStop(404, 1000).isFailure)
    }

    @Test fun `an empty estimate list is a success, not a failure`() = runTest {
        val repo = LiveArrivalsRepository(
            FakeGeocontrolSource(EstimativasPorOrigemResponseDto(1, 1000, emptyList())),
            LineCodeResolver(FixedStopCatalog(), FixedLineCatalog(emptyList())),
            FixedStopCatalog(),
        )
        assertEquals(emptyList<Any>(), repo.forStop(404, 1000).getOrThrow())
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.data.repository.LiveArrivalsRepositoryTest"`
Expected: FAIL — `LiveArrivals`/`LiveArrivalsRepository` unresolved.

- [ ] **Step 3: Implement**

Add to `domain/Repositories.kt`:

```kotlin
interface LiveArrivals {
    suspend fun forStop(stopId: Int, nowMs: Long): Result<List<LiveStopArrival>>
}
```

```kotlin
// app/src/main/kotlin/com/cleanmove/data/repository/LiveArrivalsRepository.kt
package com.cleanmove.data.repository

import com.cleanmove.data.geocontrol.GeocontrolSource
import com.cleanmove.data.geocontrol.toArrivals
import com.cleanmove.domain.LiveArrivals
import com.cleanmove.domain.LiveStopArrival
import com.cleanmove.domain.StopCatalog
import kotlinx.coroutines.CancellationException

class LiveArrivalsRepository(
    private val source: GeocontrolSource,
    private val resolver: LineCodeResolver,
    private val stopCatalog: StopCatalog,
) : LiveArrivals {

    override suspend fun forStop(stopId: Int, nowMs: Long): Result<List<LiveStopArrival>> = try {
        val arrivals = source.estimatesForStop(stopId).toArrivals(nowMs)
        Result.success(
            arrivals.map { a ->
                val line = resolver.resolve(a.linhaId)
                LiveStopArrival(
                    lineCode = line?.code,
                    rawLineLabel = if (line != null) null else stopCatalog.lineCode(a.linhaId) ?: "Linha ${a.linhaId}",
                    lineName = line?.name,
                    vehicleLabel = a.vehicleLabel,
                    minutesAway = a.minutesAway,
                    hasAccessibility = a.hasAccessibility,
                    hasAirConditioning = a.hasAirConditioning,
                    hasWifi = a.hasWifi,
                    reliability = a.reliability,
                )
            },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.data.repository.LiveArrivalsRepositoryTest"`
Expected: 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(data): LiveArrivalsRepository (resolved line codes, graceful unresolved fallback)" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 8: `EstimatedArrivalsRepository` — the "Estimativas" data source for a stop

**Files:**
- Create: `app/src/main/kotlin/com/cleanmove/data/repository/EstimatedArrivalsRepository.kt`
- Modify: `app/src/main/kotlin/com/cleanmove/domain/Repositories.kt` (add `EstimatedArrivals` interface)
- Test: `app/src/test/kotlin/com/cleanmove/data/repository/EstimatedArrivalsRepositoryTest.kt`

**Interfaces:**
- Consumes: `StopCatalog` (Task 4, including `routesServing`/`lineIdForRoute`/`stopOrderWithinRoute`/`tripsFor`/`stop`), `GeoDao.stopOffset`/`putStopOffsets` (Task 4), `LineCodeResolver` (Task 5), `StopOffsetCalibrator` (Task 6), `LineCatalog.loadDetail` (Plan 1), `EstimatedStopArrival` (Task 1).
- Produces: `interface EstimatedArrivals { suspend fun forStop(stopId: Int, nowMinuteOfDay: Int, dayType: DayType): List<EstimatedStopArrival> }`; `class EstimatedArrivalsRepository(stopCatalog: StopCatalog, geoDao: GeoDao, resolver: LineCodeResolver, lineCatalog: LineCatalog, now: () -> Long = System::currentTimeMillis, windowMinutes: Int = 60) : EstimatedArrivals`.

This never throws for a single bad route/line — each is skipped on its own (no `Result` wrapper needed here, unlike `LiveArrivals`: everything this reads is already-cached local data, so the only realistic failure is "nothing to show," which an empty list already represents).

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/kotlin/com/cleanmove/data/repository/EstimatedArrivalsRepositoryTest.kt
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

private class FixedStopCatalog(
    val routesByStop: Map<Int, List<Int>> = emptyMap(),
    val lineIdByRoute: Map<Int, Int> = emptyMap(),
    val associationsByRoute: Map<Int, List<GeoAssociation>> = emptyMap(),
    val tripsByRoute: Map<Int, List<GeoTrip>> = emptyMap(),
    val stopsById: Map<Int, GeoStop> = emptyMap(),
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
}

private class FixedLineCatalog(private val details: Map<String, LineDetail>) : LineCatalog {
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

    private fun repo(stopCatalog: FixedStopCatalog, details: Map<String, LineDetail> = mapOf("0010" to LineDetail(line0010, listOf(weekdaySchedule), emptyList()))) =
        EstimatedArrivalsRepository(stopCatalog, db.geoDao(), LineCodeResolver(stopCatalog, FixedLineCatalog(details)), FixedLineCatalog(details), now = { 0 })

    @Test fun `uses an already-cached offset, marking the result as interpolated`() = runTest {
        db.geoDao().putStopOffsets(listOf(StopOffsetEntity(routeId = 1, stopId = 404, offsetMinutes = 7, sampleCount = 5, computedAtMs = 0)))
        val stopCatalog = FixedStopCatalog(routesByStop = mapOf(404 to listOf(1)), lineIdByRoute = mapOf(1 to 776))
        val lineCatalog = FixedLineCatalog(mapOf("0010" to LineDetail(line0010, listOf(weekdaySchedule), emptyList())))
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
        val stopCatalog = FixedStopCatalog(
            routesByStop = mapOf(405 to listOf(1)), lineIdByRoute = mapOf(1 to 776),
            associationsByRoute = mapOf(1 to associations), tripsByRoute = mapOf(1 to trips), stopsById = stops,
        )
        val geoCatalog = FakeGeoCodeCatalog(776, "010")
        val lineCatalog = FixedLineCatalog(mapOf("0010" to LineDetail(line0010, listOf(weekdaySchedule), emptyList())))
        val repo = EstimatedArrivalsRepository(stopCatalog, db.geoDao(), LineCodeResolver(geoCatalog, lineCatalog), lineCatalog, now = { 0 })

        val first = repo.forStop(405, nowMinuteOfDay = 480, dayType = DayType.WEEKDAY)
        assertTrue(first.single().isInterpolated)
        assertEquals(1, stopCatalog.tripsCalls)

        repo.forStop(405, nowMinuteOfDay = 480, dayType = DayType.WEEKDAY)
        assertEquals(1, stopCatalog.tripsCalls) // cached now, no second calibration pass
    }

    @Test fun `too few trip samples falls back to the plain terminal time, not interpolated`() = runTest {
        val stopCatalog = FixedStopCatalog(
            routesByStop = mapOf(404 to listOf(1)), lineIdByRoute = mapOf(1 to 776),
            associationsByRoute = mapOf(1 to listOf(GeoAssociation(1, 1, 404))),
            tripsByRoute = mapOf(1 to listOf(GeoTrip(1, 0, 60_000L))), // only 1 sample
            stopsById = mapOf(404 to stop(404)),
        )
        val geoCatalog = FakeGeoCodeCatalog(776, "010")
        val lineCatalog = FixedLineCatalog(mapOf("0010" to LineDetail(line0010, listOf(weekdaySchedule), emptyList())))
        val repo = EstimatedArrivalsRepository(stopCatalog, db.geoDao(), LineCodeResolver(geoCatalog, lineCatalog), lineCatalog, now = { 0 })

        val arrival = repo.forStop(404, nowMinuteOfDay = 480, dayType = DayType.WEEKDAY).single()
        assertEquals(480, arrival.minuteOfDay)
        assertTrue(!arrival.isInterpolated)
    }

    @Test fun `an unresolved line is omitted, other lines still show`() = runTest {
        val resolved = LineDetail(line0010, listOf(weekdaySchedule), emptyList())
        val lineCatalog = FixedLineCatalog(mapOf("0010" to resolved))
        val stopCatalog = FixedStopCatalog(
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
        val lineCatalog = FixedLineCatalog(mapOf("0010" to detail))
        val stopCatalog = FixedStopCatalog(routesByStop = mapOf(404 to listOf(1)), lineIdByRoute = mapOf(1 to 776))
        val geoCatalog = FakeGeoCodeCatalog(776, "010")
        val repo = EstimatedArrivalsRepository(stopCatalog, db.geoDao(), LineCodeResolver(geoCatalog, lineCatalog), lineCatalog, now = { 0 })

        val result = repo.forStop(404, nowMinuteOfDay = 480, dayType = DayType.WEEKDAY)
        assertEquals(listOf(480), result.map { it.minuteOfDay })
    }

    @Test fun `multiple departures of the same line inside the window all appear`() = runTest {
        val detail = LineDetail(line0010, listOf(weekdaySchedule.copy(minuteOfDay = 480), weekdaySchedule.copy(minuteOfDay = 520)), emptyList())
        val lineCatalog = FixedLineCatalog(mapOf("0010" to detail))
        val stopCatalog = FixedStopCatalog(routesByStop = mapOf(404 to listOf(1)), lineIdByRoute = mapOf(1 to 776))
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
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.data.repository.EstimatedArrivalsRepositoryTest"`
Expected: FAIL — `EstimatedArrivals`/`EstimatedArrivalsRepository` unresolved.

- [ ] **Step 3: Implement**

Add to `domain/Repositories.kt`:

```kotlin
interface EstimatedArrivals {
    suspend fun forStop(stopId: Int, nowMinuteOfDay: Int, dayType: DayType): List<EstimatedStopArrival>
}
```

```kotlin
// app/src/main/kotlin/com/cleanmove/data/repository/EstimatedArrivalsRepository.kt
package com.cleanmove.data.repository

import com.cleanmove.data.local.GeoDao
import com.cleanmove.data.local.StopOffsetEntity
import com.cleanmove.domain.DayType
import com.cleanmove.domain.EstimatedArrivals
import com.cleanmove.domain.EstimatedStopArrival
import com.cleanmove.domain.LineCatalog
import com.cleanmove.domain.StopCatalog

private const val DAY_MINUTES = 1440

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
```

- [ ] **Step 4: Run to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.data.repository.EstimatedArrivalsRepositoryTest"`
Expected: 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(data): EstimatedArrivalsRepository (schedule + lazily-calibrated per-stop offset)" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 9: `StopDetailViewModel` and `StopDetailScreen`

**Files:**
- Create: `app/src/main/kotlin/com/cleanmove/ui/stop/StopDetailViewModel.kt`, `ui/stop/StopDetailScreen.kt`
- Test: `app/src/test/kotlin/com/cleanmove/ui/stop/StopDetailViewModelTest.kt`

**Interfaces:**
- Consumes: `LiveArrivals`, `EstimatedArrivals` (Tasks 7–8), `StopCatalog.stop` (Task 4), `DayType`, `LiveStopArrival`, `EstimatedStopArrival`, `Reliability` (Task 1 / existing).
- Produces:
  - `enum class StopMode { LIVE, ESTIMATES }`
  - `fun currentDayType(dayOfWeek: java.time.DayOfWeek): DayType`
  - `data class StopDetailUiState(val stopLabel: String = "", val mode: StopMode = StopMode.LIVE, val autoFallback: Boolean = false, val loading: Boolean = true, val liveRows: List<LiveStopArrival> = emptyList(), val estimatedRows: List<EstimatedStopArrival> = emptyList())`
  - `StopDetailViewModel(savedStateHandle, liveArrivals, estimatedArrivals, stopCatalog, clock: () -> java.time.ZonedDateTime = { java.time.ZonedDateTime.now(java.time.ZoneId.of("America/Sao_Paulo")) })` reading arg `"stopId"`, with `state: StateFlow<StopDetailUiState>`, `selectMode(mode: StopMode)`. No separate `retry()` — re-selecting "Ao vivo" (tested below) already re-queries, and there is no error state distinct from the empty/fallback ones this screen already handles.
  - Nav route `"stop/{stopId}"`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/kotlin/com/cleanmove/ui/stop/StopDetailViewModelTest.kt
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
```

- [ ] **Step 2: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.ui.stop.StopDetailViewModelTest"`
Expected: FAIL — `StopDetailViewModel` and friends unresolved.

- [ ] **Step 3: Implement the ViewModel**

```kotlin
// app/src/main/kotlin/com/cleanmove/ui/stop/StopDetailViewModel.kt
package com.cleanmove.ui.stop

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleanmove.domain.DayType
import com.cleanmove.domain.EstimatedArrivals
import com.cleanmove.domain.EstimatedStopArrival
import com.cleanmove.domain.LiveArrivals
import com.cleanmove.domain.LiveStopArrival
import com.cleanmove.domain.StopCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

enum class StopMode { LIVE, ESTIMATES }

data class StopDetailUiState(
    val stopLabel: String = "",
    val mode: StopMode = StopMode.LIVE,
    val autoFallback: Boolean = false,
    val loading: Boolean = true,
    val liveRows: List<LiveStopArrival> = emptyList(),
    val estimatedRows: List<EstimatedStopArrival> = emptyList(),
)

fun currentDayType(dayOfWeek: DayOfWeek): DayType = when (dayOfWeek) {
    DayOfWeek.SATURDAY -> DayType.SATURDAY
    DayOfWeek.SUNDAY -> DayType.SUNDAY_HOLIDAY
    else -> DayType.WEEKDAY
}

@HiltViewModel
class StopDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val liveArrivals: LiveArrivals,
    private val estimatedArrivals: EstimatedArrivals,
    private val stopCatalog: StopCatalog,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(ZoneId.of("America/Sao_Paulo")) },
) : ViewModel() {
    private val stopId: Int = checkNotNull(savedStateHandle["stopId"])
    private val _state = MutableStateFlow(StopDetailUiState())
    val state: StateFlow<StopDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val stop = stopCatalog.stop(stopId)
            _state.update { it.copy(stopLabel = stop?.street ?: stop?.code ?: "Ponto $stopId") }
            load()
        }
    }

    fun selectMode(mode: StopMode) {
        if (mode == _state.value.mode && !_state.value.autoFallback) return
        _state.update { it.copy(mode = mode, autoFallback = false, loading = true) }
        viewModelScope.launch { loadFor(mode) }
    }

    private suspend fun load() = loadFor(StopMode.LIVE)

    private suspend fun loadFor(mode: StopMode) {
        if (mode == StopMode.LIVE) {
            val result = liveArrivals.forStop(stopId, clock().toInstant().toEpochMilli())
            val rows = result.getOrNull()
            if (result.isFailure || rows.isNullOrEmpty()) {
                _state.update { it.copy(mode = StopMode.ESTIMATES, autoFallback = true) }
                loadEstimates()
            } else {
                _state.update { it.copy(loading = false, liveRows = rows) }
            }
        } else {
            loadEstimates()
        }
    }

    private suspend fun loadEstimates() {
        val now = clock()
        val rows = estimatedArrivals.forStop(stopId, now.hour * 60 + now.minute, currentDayType(now.dayOfWeek))
        _state.update { it.copy(loading = false, estimatedRows = rows) }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.ui.stop.StopDetailViewModelTest"`
Expected: 6 tests PASS.

- [ ] **Step 5: Implement the screen**

```kotlin
// app/src/main/kotlin/com/cleanmove/ui/stop/StopDetailScreen.kt
package com.cleanmove.ui.stop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Accessible
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleanmove.domain.EstimatedStopArrival
import com.cleanmove.domain.LiveStopArrival
import com.cleanmove.domain.Reliability
import com.cleanmove.ui.line.formatMinuteOfDay
import com.cleanmove.ui.theme.LiveColor
import com.cleanmove.ui.theme.ScheduledColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopDetailScreen(onBack: () -> Unit, viewModel: StopDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.stopLabel) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp)) {
                SegmentedButton(
                    selected = state.mode == StopMode.LIVE,
                    onClick = { viewModel.selectMode(StopMode.LIVE) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Ao vivo") }
                SegmentedButton(
                    selected = state.mode == StopMode.ESTIMATES,
                    onClick = { viewModel.selectMode(StopMode.ESTIMATES) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Estimativas") }
            }
            if (state.autoFallback) {
                Text(
                    "Sem dados ao vivo no momento — mostrando estimativas.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.mode == StopMode.LIVE && state.liveRows.isEmpty() ->
                    EmptyState()
                state.mode == StopMode.ESTIMATES && state.estimatedRows.isEmpty() ->
                    EmptyState()
                state.mode == StopMode.LIVE -> LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.liveRows) { LiveRow(it) }
                }
                else -> LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.estimatedRows) { EstimatedRow(it) }
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text("Nenhum ônibus previsto agora", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun Reliability.toColor() = when (this) {
    Reliability.GREEN -> LiveColor
    Reliability.ORANGE -> ScheduledColor
    Reliability.RED -> androidx.compose.ui.graphics.Color(0xFFFF6B6B)
    Reliability.GRAY -> androidx.compose.ui.graphics.Color(0xFF6B7280)
}

@Composable
private fun LiveRow(arrival: LiveStopArrival) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(arrival.lineCode ?: arrival.rawLineLabel ?: "?", color = arrival.reliability.toColor(), style = MaterialTheme.typography.titleMedium)
            Column(Modifier.weight(1f)) {
                Text(arrival.lineName ?: "Linha ${arrival.rawLineLabel}", style = MaterialTheme.typography.bodyMedium)
                arrival.vehicleLabel?.let { Text("Veículo $it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (arrival.hasAccessibility) Icon(Icons.Filled.Accessible, "Acessível", Modifier, MaterialTheme.colorScheme.onSurfaceVariant)
                    if (arrival.hasAirConditioning) Icon(Icons.Filled.AcUnit, "Ar-condicionado", Modifier, MaterialTheme.colorScheme.onSurfaceVariant)
                    if (arrival.hasWifi) Icon(Icons.Filled.Wifi, "Wifi", Modifier, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("${arrival.minutesAway} min", color = LiveColor, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun EstimatedRow(arrival: EstimatedStopArrival) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(arrival.lineCode, color = ScheduledColor, style = MaterialTheme.typography.titleMedium)
            Text(arrival.lineName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text((if (arrival.isInterpolated) "≈" else "") + formatMinuteOfDay(arrival.minuteOfDay), color = ScheduledColor)
        }
    }
}
```

- [ ] **Step 6: Run the full suite and assemble**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL; all tests pass (this task doesn't wire the screen into navigation yet — that's Task 11 — so nothing exercises it end-to-end until then).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: stop detail screen (Ao vivo / Estimativas toggle, amenities, auto-fallback)" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 10: Add stop results to the Buscar screen

**Files:**
- Modify: `app/src/main/kotlin/com/cleanmove/ui/search/SearchViewModel.kt`, `ui/search/SearchScreen.kt`, `app/src/test/kotlin/com/cleanmove/ui/FakeCatalog.kt` (add `FakeStopCatalog`)
- Test: `app/src/test/kotlin/com/cleanmove/ui/search/SearchViewModelTest.kt` (extend existing file)

**Interfaces:**
- Consumes: `StopCatalog` (Task 4), `GeoStop` (Task 1).
- Produces: `SearchUiState` gains `val stops: List<GeoStop> = emptyList()`; `SearchViewModel` gains a `stopCatalog: StopCatalog` constructor param; `SearchScreen` gains `onStopClick: (Int) -> Unit` parameter.

- [ ] **Step 1: Write the failing tests (added to the existing `SearchViewModelTest.kt`)**

Add to `app/src/test/kotlin/com/cleanmove/ui/FakeCatalog.kt`:

```kotlin
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
    override suspend fun stopOrderWithinRoute(routeId: Int): List<GeoAssociation> = emptyList()
    override suspend fun tripsFor(routeId: Int): List<GeoTrip> = emptyList()
}
```

(add the matching imports: `com.cleanmove.data.geocontrol.static.GeoAssociation`, `GeoTrip`, `com.cleanmove.domain.GeoStop`, `com.cleanmove.domain.StopCatalog`)

Append to `SearchViewModelTest.kt`:

```kotlin
    private val stops = listOf(
        GeoStop(404, "110001", null, "Avenida Paulino Muller", "Vitória", -20.31, -40.31, false),
        GeoStop(405, "110002", null, "Rua do Caju", "Vitória", -20.32, -40.32, false),
    )

    @Test fun `stops load alongside lines and are filtered by the same query`() = runTest {
        val vm = SearchViewModel(FakeCatalog(lines), FakeStopCatalog(stops))
        vm.state.test {
            assertEquals(2, expectMostRecentItem().stops.size)
            vm.onQueryChange("caju")
            assertEquals(listOf(405), expectMostRecentItem().stops.map { it.id })
            cancel()
        }
    }

    @Test fun `a stop refresh failure alone is not an error while lines still loaded`() = runTest {
        val vm = SearchViewModel(FakeCatalog(lines), FakeStopCatalog(refreshResult = Result.failure(IOException())))
        vm.state.test {
            val s = expectMostRecentItem()
            assertFalse(s.error)
            assertEquals(2, s.lines.size)
            cancel()
        }
    }

    @Test fun `both sources failing with nothing cached is an error`() = runTest {
        val vm = SearchViewModel(FakeCatalog(emptyList(), Result.failure(IOException())), FakeStopCatalog(refreshResult = Result.failure(IOException())))
        vm.state.test {
            assertTrue(expectMostRecentItem().error)
            cancel()
        }
    }
```

(Update earlier tests in the same file that construct `SearchViewModel(FakeCatalog(...))` with one argument to also pass a `FakeStopCatalog()` second argument, since the constructor is gaining a required parameter — e.g. `SearchViewModel(FakeCatalog(refreshResult = ...), FakeStopCatalog())`.)

- [ ] **Step 2: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.ui.search.SearchViewModelTest"`
Expected: FAIL — compile errors (`SearchViewModel` doesn't take a second argument yet; `FakeStopCatalog` unresolved).

- [ ] **Step 3: Implement**

Edit `SearchViewModel.kt`:

```kotlin
data class SearchUiState(
    val query: String = "",
    val lines: List<Line> = emptyList(),
    val stops: List<GeoStop> = emptyList(),
    val loading: Boolean = true,
    val error: Boolean = false,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val catalog: LineCatalog,
    private val stopCatalog: StopCatalog,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val status = MutableStateFlow(RefreshStatus.Loading)

    val state: StateFlow<SearchUiState> = combine(
        query,
        query.flatMapLatest { catalog.observeLines(it) },
        query.flatMapLatest { stopCatalog.observeStops(it) },
        status,
    ) { q, lines, stops, s ->
        val empty = lines.isEmpty() && stops.isEmpty()
        SearchUiState(
            query = q, lines = lines, stops = stops,
            loading = s == RefreshStatus.Loading && empty,
            error = s == RefreshStatus.Failed && empty,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    init { refresh() }

    fun onQueryChange(value: String) { query.value = value }
    fun retry() = refresh()

    private fun refresh() {
        viewModelScope.launch {
            status.value = RefreshStatus.Loading
            val linesOk = catalog.refreshLinesIfStale().isSuccess
            val stopsOk = stopCatalog.refreshIfStale().isSuccess
            status.value = if (linesOk || stopsOk) RefreshStatus.Ready else RefreshStatus.Failed
        }
    }
}
```

(add imports `com.cleanmove.domain.GeoStop`, `com.cleanmove.domain.StopCatalog`)

Edit `SearchScreen.kt`: change the `else -> LazyColumn(...)` branch to two labeled sections, and add `onStopClick`:

```kotlin
@Composable
fun SearchScreen(onLineClick: (String) -> Unit, onStopClick: (Int) -> Unit, viewModel: SearchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = state.query, onValueChange = viewModel::onQueryChange,
            label = { Text("Buscar linha ou ponto") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        )
        when {
            state.loading -> Centered { CircularProgressIndicator() }
            state.error -> Centered {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Não foi possível carregar os dados.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = viewModel::retry) { Text("Tentar novamente") }
                }
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("Linhas", style = MaterialTheme.typography.titleMedium) }
                if (state.lines.isEmpty()) item { Text("Nenhuma linha encontrada", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else items(state.lines, key = { "line-${it.code}" }) { LineRow(it, onLineClick) }

                item { Text("Pontos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp)) }
                if (state.stops.isEmpty()) item { Text("Nenhum ponto encontrado", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else items(state.stops, key = { "stop-${it.id}" }) { StopRow(it, onStopClick) }
            }
        }
    }
}

@Composable
fun StopRow(stop: com.cleanmove.domain.GeoStop, onClick: (Int) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().clickable { onClick(stop.id) },
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stop.street ?: stop.code ?: "Ponto ${stop.id}", style = MaterialTheme.typography.bodyLarge)
            stop.municipality?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.ui.*"`
Expected: all `ui.*` tests PASS (existing ones updated per Step 1's note, new ones green).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: show stop results alongside line results in Buscar" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 11: Hilt wiring, navigation route, final verification

**Files:**
- Modify: `app/src/main/kotlin/com/cleanmove/di/AppModule.kt`, `ui/nav/CleanMoveApp.kt`

**Interfaces:**
- Consumes: everything from Tasks 1–10.
- Produces: a fully wired app; nav route `"stop/{stopId}"`.

- [ ] **Step 1: Wire the Hilt module**

Add to `AppModule.kt` (imports for each new type follow the existing pattern — one line per class, same as the rest of the file):

```kotlin
    @Provides @Singleton
    fun geocontrolStaticSource(): GeocontrolStaticSource = GeocontrolStaticApi(createGeocontrolStaticHttpClient())

    @Provides fun geoDao(db: AppDatabase): GeoDao = db.geoDao()

    @Provides @Singleton
    fun stopCatalog(source: GeocontrolStaticSource, geoDao: GeoDao): StopCatalog = GeoStaticCatalogRepository(source, geoDao)

    @Provides @Singleton
    fun lineCodeResolver(stopCatalog: StopCatalog, lineCatalog: LineCatalog): LineCodeResolver =
        LineCodeResolver(stopCatalog, lineCatalog)

    @Provides @Singleton
    fun liveArrivals(source: GeocontrolSource, resolver: LineCodeResolver, stopCatalog: StopCatalog): LiveArrivals =
        LiveArrivalsRepository(source, resolver, stopCatalog)

    @Provides @Singleton
    fun estimatedArrivals(stopCatalog: StopCatalog, geoDao: GeoDao, resolver: LineCodeResolver, lineCatalog: LineCatalog): EstimatedArrivals =
        EstimatedArrivalsRepository(stopCatalog, geoDao, resolver, lineCatalog)
```

- [ ] **Step 2: Wire the navigation route**

In `ui/nav/CleanMoveApp.kt`, change `SearchScreen(onLineClick = { nav.navigate("line/$it") })` to also pass `onStopClick = { nav.navigate("stop/$it") }`, and add a route after the existing `"line/{code}"` one:

```kotlin
                composable("stop/{stopId}", arguments = listOf(navArgument("stopId") { type = NavType.IntType })) {
                    com.cleanmove.ui.stop.StopDetailScreen(onBack = { nav.popBackStack() })
                }
```

(use a proper top-of-file import for `StopDetailScreen` instead of the inline qualified name shown here — matches how `LineDetailScreen`/`FavoritesScreen` are already imported)

- [ ] **Step 3: Run the full suite and assemble**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL; every test from Tasks 1–11 passes (Plan 1's 50 + `feat/geocontrol-client`'s 21 + this plan's new ones, roughly 100 total — read the actual total from the run, don't guess it in the report).

- [ ] **Step 4: Manual check on the emulator — UI and regression only, no live network**

No step here may call `gvbus-previsao.geocontrol.com.br` or `gvbus.geocontrol.com.br` for real — both remain unvalidated/blocked this session (Global Constraints). Scope the check to what works from an empty cache:

Install (`adb install -r` the assembled debug APK) and launch. Confirm:
- **Regression:** Buscar's "Linhas" section still lists real Ceturb lines and opening one still shows schedules/itinerary (this must be unaffected by today's changes).
- **New, offline-safe:** Buscar now shows a "Pontos" header below "Linhas" (likely "Nenhum ponto encontrado" with an empty Geocontrol cache — that's expected and fine, not a bug).
- **No crash:** the app doesn't crash on launch or on opening Buscar (confirms the Hilt graph and Room migration — version bump to 2 — are valid).

Do not attempt to open a stop detail screen end-to-end (there's nothing in the stop cache to tap into without a live fetch) — that verification waits until the Geocontrol hosts can actually be called, which is outside this session's capability. State this plainly in the task's completion notes; do not claim the live/estimates flow was verified.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore(di): wire stop search and stop detail into Hilt and navigation" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Handoff to future work

Map pins (the "Explorar" visual map), favoriting stops, lotação/crowding, and any live validation of either Geocontrol host remain open — see `docs/superpowers/specs/2026-10-05-stop-detail-design.md` §9 and `docs/spike-findings.md`.
