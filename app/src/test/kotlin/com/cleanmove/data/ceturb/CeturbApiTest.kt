package com.cleanmove.data.ceturb

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
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CeturbApiTest {
    private val json = ceturbJson

    private fun api(contentType: String, body: String, status: HttpStatusCode = HttpStatusCode.OK, onRequest: (String) -> Unit = {}): CeturbApi {
        val engine = MockEngine { request ->
            onRequest(request.url.toString())
            respond(body, status, headersOf(HttpHeaders.ContentType, contentType))
        }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(json, ContentType.Any) }
        }
        return CeturbApi(client, baseUrl = "https://example.test/api")
    }

    @Test
    fun `lines requests the Transcol list and ignores unknown fields`() = runTest {
        var url = ""
        val api = api("application/json", """[{"Descricao":"NOVA PALESTINA / FRADINHOS","Linha":"0010","Extra":1}]""") { url = it }
        assertEquals(listOf(LineDto("NOVA PALESTINA / FRADINHOS", "0010")), api.lines())
        assertEquals("https://example.test/api/ConsultaLinha/?Tipo_Linha=T", url)
    }

    @Test
    fun `parses json even when the server sends a different content type`() = runTest {
        val api = api("text/html; charset=utf-8", """[{"Descricao":"X","Linha":"0010"}]""")
        assertEquals(1, api.lines().size)
    }

    @Test
    fun `schedules and itinerary hit the per-line endpoints and accept an empty array`() = runTest {
        val urls = mutableListOf<String>()
        val api = api("application/json", "[]") { urls += it }
        assertTrue(api.schedules("001").isEmpty())
        assertTrue(api.itinerary("001").isEmpty())
        assertEquals(
            listOf("https://example.test/api/BuscaHorarios/001", "https://example.test/api/BuscaItinerarios/001"),
            urls,
        )
    }

    @Test
    fun `null string fields fall back to defaults instead of failing the response`() = runTest {
        val api = api("application/json", """[{"Descricao":null,"Linha":"0010"}]""")
        assertEquals(listOf(LineDto("", "0010")), api.lines())
    }

    @Test
    fun `null fields in schedules and itinerary rows are tolerated`() = runTest {
        val schedules = api(
            "application/json",
            """[{"Desc_Terminal":null,"Descricao_Hora":null,"Hora_Saida":"06:00","Linha":"0010","Terminal_Seq":1,"Tipo_Orientacao":null}]""",
        ).schedules("0010")
        assertEquals(1, schedules.size)
        assertEquals("", schedules.single().terminalName)
        val steps = api("application/json", """[{"Desc_Via":null,"Linha":"0010","Sentido":"I","Sequencia":1}]""").itinerary("0010")
        assertEquals("", steps.single().street)
    }

    @Test(expected = Exception::class)
    fun `http errors surface as exceptions`() = runTest {
        api("application/json", "oops", HttpStatusCode.InternalServerError).lines()
    }
}
