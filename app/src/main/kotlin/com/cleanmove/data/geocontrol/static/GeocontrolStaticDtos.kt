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
