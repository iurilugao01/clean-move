package com.cleanmove.data.geocontrol.static

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeocontrolStaticMappersTest {
    @Test
    fun `maps a stop, trimming blank optional fields to null`() {
        val dto = StaticPontoDto(
            id = 404, codigo = "110001", municipio = "Vitória",
            logradouro = "Avenida Paulino Muller", referencia = "  ",
            longitude = -40.3196039018834, latitude = -20.3170426845619,
            azimute = -14, terminal = false,
        )
        val stop = dto.toDomain()
        assertEquals(404, stop.id)
        assertEquals("110001", stop.code)
        assertEquals("Avenida Paulino Muller", stop.street)
        assertNull(stop.description)
    }

    @Test
    fun `maps a line`() {
        val line = StaticLinhaDto(id = 744, codigo = "0015", descricao = "Jockey de Itaparica/São Torquato").toDomain()
        assertEquals(GeoLine(744, "0015", "Jockey de Itaparica/São Torquato"), line)
    }

    @Test
    fun `maps a route with its destination label and owning line`() {
        val route = StaticItinerarioDto(id = 4804, codigo = "010I", bandeira = "NOVA PALESTINA / FRADINHOS", linhaId = 776).toDomain()
        assertEquals(GeoRoute(4804, "010I", "NOVA PALESTINA / FRADINHOS", 776), route)
    }

    @Test
    fun `maps an association`() {
        val assoc = AssociacaoDto(itinerarioId = 510, ordem = 1, pontoDeParadaId = 5244, embarque = true, desembarque = false).toDomain()
        assertEquals(GeoAssociation(510, 1, 5244), assoc)
    }

    @Test
    fun `maps a trip with both timestamps`() {
        val trip = ViagemDto(
            dataAgendada = 1_791_192_420_000, linhaId = 380, itinerarioId = 5721,
            dataChegadaEstimada = 1_791_193_762_000, veiculo = 21254, acessibilidade = true,
        ).toDomain()
        assertEquals(GeoTrip(5721, 1_791_192_420_000, 1_791_193_762_000), trip)
    }

    @Test
    fun `a trip missing dataChegadaEstimada keeps a null estimate instead of dropping the trip`() {
        val trip = ViagemDto(dataAgendada = 1_791_192_420_000, linhaId = 380, itinerarioId = 5721, dataChegadaEstimada = null).toDomain()
        assertEquals(null, trip!!.estimatedArrivalMs)
    }

    @Test
    fun `a trip with no scheduled time maps to null`() {
        assertNull(ViagemDto(dataAgendada = 0, linhaId = 380, itinerarioId = 5721).toDomain())
    }
}
