package com.cleanmove.data.geocontrol

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * The live arrival/position source reverse-engineered from the ÔnibusGV app's own JS
 * (see docs/spike-findings.md). Unlike [com.cleanmove.data.ceturb.CeturbSource], none of
 * this has been validated against a real response — every call here can fail or return an
 * unexpected shape, and callers must treat that as the normal case, not an edge case.
 */
interface GeocontrolSource {
    suspend fun estimatesForStop(stopId: Int): EstimativasPorOrigemResponseDto
    suspend fun searchStops(text: String? = null, envelope: List<Double>? = null): PesquisarPontosDeParadaResponseDto
    suspend fun stops(ids: List<Int>): ListarPontosDeParadaResponseDto
    suspend fun routes(ids: List<Int>): ListarItinerariosResponseDto
    suspend fun vehiclePosition(vehicleIds: List<Int>): UltimaPosicaoResponseDto
}

class GeocontrolApi(
    private val client: HttpClient,
    private val baseUrl: String = BASE_URL,
) : GeocontrolSource {

    override suspend fun estimatesForStop(stopId: Int): EstimativasPorOrigemResponseDto =
        post("Previsao/obterEstimativasPorOrigem", PontoDeOrigemRequest(stopId))

    override suspend fun searchStops(text: String?, envelope: List<Double>?): PesquisarPontosDeParadaResponseDto =
        post("Previsao/pesquisarPontosDeParada", PesquisarPontosDeParadaRequest(text, envelope))

    override suspend fun stops(ids: List<Int>): ListarPontosDeParadaResponseDto =
        post("Previsao/listarPontosDeParada", ListaIdsRequest(ids))

    override suspend fun routes(ids: List<Int>): ListarItinerariosResponseDto =
        post("Previsao/listarItinerarios", ListaIdsRequest(ids))

    override suspend fun vehiclePosition(vehicleIds: List<Int>): UltimaPosicaoResponseDto =
        post("Previsao/obterUltimaPosicaoDoVeiculo", VeiculoIdsRequest(vehicleIds))

    private suspend inline fun <reified Req, reified Res> post(path: String, body: Req): Res =
        client.post("$baseUrl/$path") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()

    companion object {
        const val BASE_URL = "https://gvbus-previsao.geocontrol.com.br/pontual-previsao"
    }
}

val geocontrolJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

fun createGeocontrolHttpClient(): HttpClient = HttpClient(OkHttp) {
    expectSuccess = true
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 8_000
    }
    install(ContentNegotiation) {
        json(geocontrolJson, ContentType.Any)
    }
}
