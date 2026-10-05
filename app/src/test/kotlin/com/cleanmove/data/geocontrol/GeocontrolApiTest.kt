package com.cleanmove.data.geocontrol

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeocontrolApiTest {
    private fun api(body: String, onRequest: (HttpRequestData) -> Unit = {}): GeocontrolApi {
        val engine = MockEngine { request ->
            onRequest(request)
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(geocontrolJson, ContentType.Any) }
        }
        return GeocontrolApi(client, baseUrl = "https://example.test/pontual-previsao")
    }

    @Test
    fun `estimatesForStop posts the stop id to the right endpoint`() = runTest {
        var url = ""
        var contentType = ""
        val api = api(
            """{"pontoDeOrigemId":404,"horarioDoServidor":1000,"estimativas":[]}""",
        ) { req ->
            url = req.url.toString()
            contentType = req.body.contentType.toString()
        }

        val result = api.estimatesForStop(404)

        assertEquals("https://example.test/pontual-previsao/Previsao/obterEstimativasPorOrigem", url)
        assertTrue(contentType.startsWith("application/json"))
        assertEquals(404, result.pontoDeOrigemId)
    }

    @Test
    fun `a numeric veiculo field is tolerated as a vehicle label string`() = runTest {
        val api = api(
            """{"pontoDeOrigemId":1,"horarioDoServidor":1000,"estimativas":[{"veiculo":1234,"linhaId":1,"itinerarioId":1}]}""",
        )

        val result = api.estimatesForStop(1)

        assertEquals("1234", result.estimativas.single().veiculo)
    }

    @Test
    fun `searchStops posts text and envelope and parses stop ids`() = runTest {
        val api = api("""{"pontosDeParada":[404,405]}""")
        val result = api.searchStops(text = "centro", envelope = listOf(-40.3, -20.3, -40.2, -20.2))
        assertEquals(listOf(404, 405), result.pontosDeParada)
    }

    @Test
    fun `stops posts the id list and accepts an empty array`() = runTest {
        val api = api("""{"pontosDeParada":[]}""")
        assertTrue(api.stops(listOf(404)).pontosDeParada.isEmpty())
    }

    @Test
    fun `vehiclePosition hits obterUltimaPosicaoDoVeiculo`() = runTest {
        var url = ""
        val api = api("""{"pacotes":[{"localizacao":"POINT (-40.3 -20.3)"}]}""") { url = it.url.toString() }
        val result = api.vehiclePosition(listOf(99))
        assertEquals("https://example.test/pontual-previsao/Previsao/obterUltimaPosicaoDoVeiculo", url)
        assertEquals("POINT (-40.3 -20.3)", result.pacotes.single().localizacao)
    }

    @Test(expected = Exception::class)
    fun `http errors surface as exceptions`() = runTest {
        val engine = MockEngine { respond("oops", HttpStatusCode.InternalServerError) }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(geocontrolJson, ContentType.Any) }
        }
        GeocontrolApi(client, baseUrl = "https://example.test/pontual-previsao").estimatesForStop(1)
    }
}
