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
