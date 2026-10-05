package com.cleanmove.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [LineEntity::class, ScheduleEntity::class, ItineraryEntity::class, SyncMeta::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
}
