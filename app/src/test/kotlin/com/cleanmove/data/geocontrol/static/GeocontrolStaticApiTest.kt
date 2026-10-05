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
        val result = api.lines()
        assertEquals("https://example.test/pontual-api-web/listarLinhas", url)
        assertEquals("0015", result.single().codigo)
    }

    @Test
    fun `routes hits listarItinerarios`() = runTest {
        var url = ""
        val api = api("""[{"id":4804,"codigo":"010I","bandeira":"NOVA PALESTINA / FRADINHOS","linhaId":776}]""") { url = it }
        val result = api.routes()
        assertEquals("https://example.test/pontual-api-web/listarItinerarios", url)
        assertEquals(776, result.single().linhaId)
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
        val result = api.tripsNext7Days()
        assertEquals("https://example.test/pontual-api-web/listarViagensProximosSeteDias", url)
        assertEquals(5721, result.single().itinerarioId)
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
