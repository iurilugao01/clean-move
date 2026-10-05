package com.cleanmove.data.geocontrol

import com.cleanmove.domain.Reliability
import com.cleanmove.domain.clampedHourDiffMs
import com.cleanmove.domain.minutesUntil
import com.cleanmove.domain.reliabilityOf

/**
 * A live arrival as Geocontrol reports it: keyed by its own `linhaId`/`itinerarioId`,
 * not yet joined to this app's Ceturb-based `Line.code`. That join needs the stop/route
 * catalog this client doesn't build yet (see docs/spike-findings.md, "Plan 2").
 */
data class GeocontrolArrival(
    val linhaId: Int,
    val itinerarioId: Int,
    val vehicleLabel: String?,
    val minutesAway: Int,
    val hasAccessibility: Boolean,
    val hasAirConditioning: Boolean,
    val hasWifi: Boolean,
    val reliability: Reliability,
)

data class GeocontrolStop(
    val id: Int,
    val code: String?,
    val description: String?,
    val street: String?,
    val municipality: String?,
    val longitude: Double,
    val latitude: Double,
    val isTerminal: Boolean,
)

data class LatLng(val latitude: Double, val longitude: Double)

fun EstimativasPorOrigemResponseDto.toArrivals(deviceNowMs: Long): List<GeocontrolArrival> {
    val diffHorarioMs = clampedHourDiffMs(deviceNowMs, horarioDoServidor)
    return estimativas.map { e ->
        GeocontrolArrival(
            linhaId = e.linhaId,
            itinerarioId = e.itinerarioId,
            vehicleLabel = e.veiculo,
            minutesAway = minutesUntil(e.horarioNaOrigem, diffHorarioMs, deviceNowMs),
            hasAccessibility = e.accessibility,
            hasAirConditioning = e.arCondicionado,
            hasWifi = !e.wifi.isNullOrBlank(),
            reliability = reliabilityOf(
                horarioNaOrigemMs = e.horarioNaOrigem,
                horarioDaTransmissaoMs = e.horarioDaTransmissao,
                horarioDePartidaMs = e.horarioDePartida,
                horarioDoServidorMs = horarioDoServidor,
                deviceNowMs = deviceNowMs,
            ),
        )
    }
}

fun PesquisarPontosDeParadaResponseDto.toStopIds(): List<Int> = pontosDeParada

fun PontoDto.toStop(): GeocontrolStop = GeocontrolStop(
    id = id,
    code = identificador?.trim()?.takeIf(String::isNotEmpty),
    description = descricao?.trim()?.takeIf(String::isNotEmpty),
    street = logradouro?.trim()?.takeIf(String::isNotEmpty),
    municipality = municipio?.trim()?.takeIf(String::isNotEmpty),
    longitude = longitude,
    latitude = latitude,
    isTerminal = terminal,
)

fun ListarPontosDeParadaResponseDto.toStops(): List<GeocontrolStop> = pontosDeParada.map { it.toStop() }

/**
 * Parses an OpenLayers-style WKT point, e.g. "POINT (-40.319 -20.317)" or "POINT Z (x y z)".
 * Returns null for any other geometry type or malformed input instead of throwing: a bad
 * vehicle position should drop that one pin, not break the screen.
 */
fun parseWktPoint(wkt: String): LatLng? {
    val match = Regex("""POINT\s*Z?\s*\(\s*([-\d.]+)\s+([-\d.]+)""", RegexOption.IGNORE_CASE)
        .find(wkt.trim()) ?: return null
    val longitude = match.groupValues[1].toDoubleOrNull() ?: return null
    val latitude = match.groupValues[2].toDoubleOrNull() ?: return null
    return LatLng(latitude = latitude, longitude = longitude)
}

fun UltimaPosicaoResponseDto.toVehiclePosition(): LatLng? =
    pacotes.firstOrNull()?.localizacao?.let { parseWktPoint(it) }
