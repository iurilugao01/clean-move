package com.cleanmove.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "lines")
data class LineEntity(@PrimaryKey val code: String, val name: String)

@Entity(tableName = "schedules", indices = [Index("lineCode")])
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lineCode: String,
    val dayType: String,
    val direction: Int,
    val terminalName: String,
    val minuteOfDay: Int,
    val notes: String,
)

@Entity(tableName = "itinerary", indices = [Index("lineCode")])
data class ItineraryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lineCode: String,
    val direction: Int,
    val sequence: Int,
    val street: String,
)

@Entity(tableName = "sync_meta")
data class SyncMeta(@PrimaryKey val key: String, val updatedAt: Long)
