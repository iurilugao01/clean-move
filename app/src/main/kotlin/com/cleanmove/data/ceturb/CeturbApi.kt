package com.cleanmove.data.ceturb

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

interface CeturbSource {
    suspend fun lines(): List<LineDto>
    suspend fun schedules(code: String): List<ScheduleDto>
    suspend fun itinerary(code: String): List<ItineraryDto>
}

class CeturbApi(
    private val client: HttpClient,
    private val baseUrl: String = BASE_URL,
) : CeturbSource {

    override suspend fun lines(): List<LineDto> =
        client.get("$baseUrl/ConsultaLinha/") { parameter("Tipo_Linha", "T") }.body()

    override suspend fun schedules(code: String): List<ScheduleDto> =
        client.get("$baseUrl/BuscaHorarios/$code").body()

    override suspend fun itinerary(code: String): List<ItineraryDto> =
        client.get("$baseUrl/BuscaItinerarios/$code").body()

    companion object {
        const val BASE_URL = "https://api.onibus.ceturb.es.gov.br/api"
    }
}

val ceturbJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

fun createCeturbHttpClient(): HttpClient = HttpClient(OkHttp) {
    expectSuccess = true
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 8_000
    }
    install(ContentNegotiation) {
        json(ceturbJson, ContentType.Any)
    }
}
