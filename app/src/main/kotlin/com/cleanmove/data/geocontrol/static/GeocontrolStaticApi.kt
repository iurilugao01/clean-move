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
