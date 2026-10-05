package com.cleanmove.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        LineEntity::class, ScheduleEntity::class, ItineraryEntity::class, SyncMeta::class,
        GeoStopEntity::class, GeoLineEntity::class, GeoRouteEntity::class, GeoAssociationEntity::class,
        GeoTripEntity::class, StopOffsetEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
    abstract fun geoDao(): GeoDao
}
