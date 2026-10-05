package com.cleanmove.domain

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * How much to trust a live arrival estimate, mirroring the "confiabilidade" heuristic
 * the Geocontrol ÔnibusGV app uses: GREEN when the vehicle's GPS fix is fresh and the
 * estimate lines up with the scheduled departure; ORANGE/RED as the fix grows stale;
 * GRAY when the predicted departure is implausibly far from the device's own clock
 * (e.g. the bus hasn't started its trip yet, or the estimate is simply too old to trust).
 */
enum class Reliability { GREEN, ORANGE, RED, GRAY }

private const val ONE_HOUR_MS = 3_600_000L
private const val ONE_MINUTE_MS = 60_000L
private const val GRAY_THRESHOLD_MS = 5 * ONE_HOUR_MS - 1000
private const val RED_THRESHOLD_MS = 36 * ONE_MINUTE_MS - 1000
private const val ORANGE_THRESHOLD_MS = 22 * ONE_MINUTE_MS - 1000

/**
 * The device and Geocontrol's server clocks can disagree by whole hours (a misconfigured
 * device timezone is the common case) without that being a real data problem. This rounds
 * the gap to the nearest hour and returns the correction to add to a server timestamp to
 * make it comparable with the device's own `now`. Differences under 30 minutes round to
 * zero, so ordinary clock drift is not treated as a timezone mismatch.
 */
fun clampedHourDiffMs(deviceNowMs: Long, serverTimeMs: Long): Long {
    val hours = ((deviceNowMs - serverTimeMs).toDouble() / ONE_HOUR_MS).roundToLong()
    return -(hours * ONE_HOUR_MS)
}

/** Whole minutes from `deviceNowMs` until `horarioNaOrigemMs`, negative when already overdue. */
fun minutesUntil(horarioNaOrigemMs: Long, diffHorarioMs: Long, deviceNowMs: Long): Int =
    Math.floorDiv(horarioNaOrigemMs - diffHorarioMs - deviceNowMs, ONE_MINUTE_MS).toInt()

fun reliabilityOf(
    horarioNaOrigemMs: Long,
    horarioDaTransmissaoMs: Long,
    horarioDePartidaMs: Long,
    horarioDoServidorMs: Long,
    deviceNowMs: Long,
): Reliability {
    val diffHorarioMs = clampedHourDiffMs(deviceNowMs, horarioDoServidorMs)
    val distanciaOrigem = horarioNaOrigemMs - horarioDaTransmissaoMs
    val distanciaAgora = horarioDoServidorMs - horarioDaTransmissaoMs
    val distancia = maxOf(distanciaOrigem, distanciaAgora)

    return when {
        abs(deviceNowMs - horarioDePartidaMs - diffHorarioMs) > GRAY_THRESHOLD_MS -> Reliability.GRAY
        distancia > RED_THRESHOLD_MS -> Reliability.RED
        distancia > ORANGE_THRESHOLD_MS -> Reliability.ORANGE
        else -> Reliability.GREEN
    }
}
