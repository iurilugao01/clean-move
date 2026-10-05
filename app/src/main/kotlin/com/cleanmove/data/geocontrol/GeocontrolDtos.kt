package com.cleanmove.data.geocontrol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Request bodies -------------------------------------------------------

@Serializable
data class PontoDeOrigemRequest(@SerialName("pontoDeOrigemId") val pontoDeOrigemId: Int)

@Serializable
data class PesquisarPontosDeParadaRequest(
    @SerialName("texto") val texto: String? = null,
    @SerialName("envelope") val envelope: List<Double>? = null,
)

@Serializable
data class ListaIdsRequest(@SerialName("listaIds") val listaIds: List<Int>)

@Serializable
data class IdRequest(@SerialName("id") val id: Int)

@Serializable
data class VeiculoIdsRequest(@SerialName("veiculoIds") val veiculoIds: List<Int>)

// Responses --------------------------------------------------------------
// Field names and types are reconstructed from the ÔnibusGV app's own (unminified) JS,
// not captured from a live response — see docs/spike-findings.md. Every field is optional
// with a safe default so one unexpected shape change degrades a single value, not the parse.

@Serializable
data class EstimativaDto(
    @SerialName("veiculoId") val veiculoId: Int? = null,
    @SerialName("itinerarioId") val itinerarioId: Int = 0,
    @SerialName("linhaId") val linhaId: Int = 0,
    @SerialName("veiculo") val veiculo: String? = null,
    @SerialName("horarioDePartida") val horarioDePartida: Long = 0,
    @SerialName("horarioNaOrigem") val horarioNaOrigem: Long = 0,
    @SerialName("horarioDaTransmissao") val horarioDaTransmissao: Long = 0,
    @SerialName("accessibility") val accessibility: Boolean = false,
    @SerialName("arCondicionado") val arCondicionado: Boolean = false,
    @SerialName("wifi") val wifi: String? = null,
)

@Serializable
data class EstimativasPorOrigemResponseDto(
    @SerialName("pontoDeOrigemId") val pontoDeOrigemId: Int = 0,
    @SerialName("horarioDoServidor") val horarioDoServidor: Long = 0,
    @SerialName("estimativas") val estimativas: List<EstimativaDto> = emptyList(),
)

@Serializable
data class PontoDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("identificador") val identificador: String? = null,
    @SerialName("descricao") val descricao: String? = null,
    @SerialName("logradouro") val logradouro: String? = null,
    @SerialName("municipio") val municipio: String? = null,
    @SerialName("longitude") val longitude: Double = 0.0,
    @SerialName("latitude") val latitude: Double = 0.0,
    @SerialName("terminal") val terminal: Boolean = false,
)

@Serializable
data class ListarPontosDeParadaResponseDto(
    @SerialName("pontosDeParada") val pontosDeParada: List<PontoDto> = emptyList(),
)

@Serializable
data class PesquisarPontosDeParadaResponseDto(
    @SerialName("pontosDeParada") val pontosDeParada: List<Int> = emptyList(),
)

@Serializable
data class BandeiraDto(
    @SerialName("localizacao") val localizacao: Double = 0.0,
    @SerialName("bandeira") val bandeira: String? = null,
)

@Serializable
data class ItinerarioDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("linhaId") val linhaId: Int = 0,
    @SerialName("identificadorLinha") val identificadorLinha: String? = null,
    @SerialName("observacaoLinha") val observacaoLinha: String? = null,
    @SerialName("bandeira") val bandeira: String? = null,
    @SerialName("bandeiras") val bandeiras: List<BandeiraDto> = emptyList(),
    @SerialName("wkt") val wkt: String? = null,
)

@Serializable
data class ListarItinerariosResponseDto(
    @SerialName("itinerarios") val itinerarios: List<ItinerarioDto> = emptyList(),
)

@Serializable
data class PacoteDto(@SerialName("localizacao") val localizacao: String? = null)

@Serializable
data class UltimaPosicaoResponseDto(
    @SerialName("pacotes") val pacotes: List<PacoteDto> = emptyList(),
)
