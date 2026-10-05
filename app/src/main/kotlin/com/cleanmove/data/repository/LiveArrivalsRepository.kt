package com.cleanmove.data.repository

import com.cleanmove.data.geocontrol.GeocontrolSource
import com.cleanmove.data.geocontrol.toArrivals
import com.cleanmove.domain.LiveArrivals
import com.cleanmove.domain.LiveStopArrival
import com.cleanmove.domain.StopCatalog
import kotlinx.coroutines.CancellationException

class LiveArrivalsRepository(
    private val source: GeocontrolSource,
    private val resolver: LineCodeResolver,
    private val stopCatalog: StopCatalog,
) : LiveArrivals {

    override suspend fun forStop(stopId: Int, nowMs: Long): Result<List<LiveStopArrival>> = try {
        val arrivals = source.estimatesForStop(stopId).toArrivals(nowMs)
        Result.success(
            arrivals.map { a ->
                val line = resolver.resolve(a.linhaId)
                LiveStopArrival(
                    lineCode = line?.code,
                    rawLineLabel = if (line != null) null else stopCatalog.lineCode(a.linhaId) ?: "Linha ${a.linhaId}",
                    lineName = line?.name,
                    vehicleLabel = a.vehicleLabel,
                    minutesAway = a.minutesAway,
                    hasAccessibility = a.hasAccessibility,
                    hasAirConditioning = a.hasAirConditioning,
                    hasWifi = a.hasWifi,
                    reliability = a.reliability,
                )
            },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
