package com.cleanmove.data.ceturb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LineDto(
    @SerialName("Descricao") val description: String = "",
    @SerialName("Linha") val code: String,
)

@Serializable
data class ScheduleDto(
    @SerialName("Desc_Terminal") val terminalName: String = "",
    @SerialName("Descricao_Hora") val dayDescription: String = "",
    @SerialName("Hora_Saida") val departureTime: String = "",
    @SerialName("Linha") val lineCode: String = "",
    @SerialName("Terminal_Seq") val terminalSeq: Int = 1,
    @SerialName("Tipo_Orientacao") val orientation: String? = null,
)

@Serializable
data class ItineraryDto(
    @SerialName("Desc_Via") val street: String = "",
    @SerialName("Linha") val lineCode: String = "",
    @SerialName("Sentido") val direction: String = "",
    @SerialName("Sequencia") val sequence: Int = 0,
)
