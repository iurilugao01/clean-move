package com.cleanmove.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface GeoDao {
    @Query("SELECT * FROM geo_stops WHERE code LIKE '%'||:query||'%' OR street LIKE '%'||:query||'%' OR municipality LIKE '%'||:query||'%' ORDER BY street")
    fun observeStops(query: String): Flow<List<GeoStopEntity>>

    @Query("SELECT * FROM geo_stops WHERE id = :id")
    suspend fun stop(id: Int): GeoStopEntity?

    @Query("SELECT code FROM geo_lines WHERE id = :lineId")
    suspend fun lineCode(lineId: Int): String?

    @Query("SELECT lineId FROM geo_routes WHERE id = :routeId")
    suspend fun lineIdForRoute(routeId: Int): Int?

    @Query("SELECT DISTINCT routeId FROM geo_associations WHERE stopId = :stopId")
    suspend fun routesServing(stopId: Int): List<Int>

    @Query("SELECT * FROM geo_associations WHERE routeId = :routeId ORDER BY `order`")
    suspend fun associationsForRoute(routeId: Int): List<GeoAssociationEntity>

    @Query("SELECT * FROM geo_trips WHERE routeId = :routeId")
    suspend fun tripsForRoute(routeId: Int): List<GeoTripEntity>

    @Query("DELETE FROM geo_stops") suspend fun deleteStops()
    @Query("DELETE FROM geo_lines") suspend fun deleteLines()
    @Query("DELETE FROM geo_routes") suspend fun deleteRoutes()
    @Query("DELETE FROM geo_associations") suspend fun deleteAssociations()
    @Query("DELETE FROM geo_trips") suspend fun deleteTrips()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertStops(items: List<GeoStopEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertLines(items: List<GeoLineEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertRoutes(items: List<GeoRouteEntity>)
    @Insert suspend fun insertAssociations(items: List<GeoAssociationEntity>)
    @Insert suspend fun insertTrips(items: List<GeoTripEntity>)

    @Transaction
    suspend fun replaceAll(
        stops: List<GeoStopEntity>,
        lines: List<GeoLineEntity>,
        routes: List<GeoRouteEntity>,
        associations: List<GeoAssociationEntity>,
        trips: List<GeoTripEntity>,
        meta: SyncMeta,
    ) {
        deleteStops(); deleteLines(); deleteRoutes(); deleteAssociations(); deleteTrips()
        insertStops(stops); insertLines(lines); insertRoutes(routes); insertAssociations(associations); insertTrips(trips)
        putGeoMeta(meta)
    }

    @Query("SELECT updatedAt FROM sync_meta WHERE `key` = :key") suspend fun geoUpdatedAt(key: String): Long?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putGeoMeta(meta: SyncMeta)

    @Query("SELECT * FROM stop_offsets WHERE routeId = :routeId AND stopId = :stopId")
    suspend fun stopOffset(routeId: Int, stopId: Int): StopOffsetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putStopOffsets(items: List<StopOffsetEntity>)
}
