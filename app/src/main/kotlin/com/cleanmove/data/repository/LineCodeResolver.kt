package com.cleanmove.data.repository

import com.cleanmove.domain.Line
import com.cleanmove.domain.LineCatalog
import com.cleanmove.domain.StopCatalog

/**
 * Joins a Geocontrol `linhaId` to this app's Ceturb-based [Line], using the rule confirmed
 * in the spike: a Ceturb code minus its leading "0" equals Geocontrol's own code for 393 of
 * 396 lines. The few that don't match (and any `linhaId` not in the static cache at all)
 * simply resolve to null — callers show the raw Geocontrol label instead, never an error.
 */
class LineCodeResolver(
    private val stopCatalog: StopCatalog,
    private val lineCatalog: LineCatalog,
) {
    suspend fun resolve(geoLineId: Int): Line? {
        val geoCode = stopCatalog.lineCode(geoLineId) ?: return null
        return lineCatalog.lineByCode("0$geoCode")
    }
}
