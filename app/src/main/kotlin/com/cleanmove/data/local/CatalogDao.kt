package com.cleanmove.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogDao {
    @Query("SELECT * FROM lines WHERE name LIKE '%' || :query || '%' OR code LIKE '%' || :query || '%' ORDER BY code")
    fun observeLines(query: String): Flow<List<LineEntity>>

    @Query("SELECT * FROM lines WHERE code = :code")
    suspend fun line(code: String): LineEntity?

    @Query("DELETE FROM lines") suspend fun deleteLines()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertLines(lines: List<LineEntity>)

    @Transaction
    suspend fun replaceLines(lines: List<LineEntity>, meta: SyncMeta) {
        deleteLines(); insertLines(lines); putMeta(meta)
    }

    @Query("SELECT * FROM schedules WHERE lineCode = :code") suspend fun schedules(code: String): List<ScheduleEntity>
    @Query("SELECT * FROM itinerary WHERE lineCode = :code") suspend fun itinerary(code: String): List<ItineraryEntity>
    @Query("DELETE FROM schedules WHERE lineCode = :code") suspend fun deleteSchedules(code: String)
    @Query("DELETE FROM itinerary WHERE lineCode = :code") suspend fun deleteItinerary(code: String)
    @Insert suspend fun insertSchedules(items: List<ScheduleEntity>)
    @Insert suspend fun insertItinerary(items: List<ItineraryEntity>)

    @Transaction
    suspend fun replaceDetail(code: String, schedules: List<ScheduleEntity>, itinerary: List<ItineraryEntity>, meta: SyncMeta) {
        deleteSchedules(code); deleteItinerary(code)
        insertSchedules(schedules); insertItinerary(itinerary)
        putMeta(meta)
    }

    @Query("SELECT updatedAt FROM sync_meta WHERE `key` = :key") suspend fun updatedAt(key: String): Long?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMeta(meta: SyncMeta)
}
