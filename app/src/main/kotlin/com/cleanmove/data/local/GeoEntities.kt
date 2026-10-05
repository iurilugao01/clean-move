package com.cleanmove.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "geo_stops")
data class GeoStopEntity(
    @PrimaryKey val id: Int,
    val code: String?,
    val description: String?,
    val street: String?,
    val municipality: String?,
    val latitude: Double,
    val longitude: Double,
    val isTerminal: Boolean,
)

@Entity(tableName = "geo_lines")
data class GeoLineEntity(@PrimaryKey val id: Int, val code: String, val name: String)

@Entity(tableName = "geo_routes", indices = [Index("lineId")])
data class GeoRouteEntity(@PrimaryKey val id: Int, val code: String?, val destinationLabel: String?, val lineId: Int)

@Entity(tableName = "geo_associations", indices = [Index("routeId"), Index("stopId")])
data class GeoAssociationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeId: Int,
    val order: Int,
    val stopId: Int,
)

@Entity(tableName = "geo_trips", indices = [Index("routeId")])
data class GeoTripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeId: Int,
    val scheduledAtMs: Long,
    val estimatedArrivalMs: Long?,
)

@Entity(tableName = "stop_offsets", primaryKeys = ["routeId", "stopId"])
data class StopOffsetEntity(
    val routeId: Int,
    val stopId: Int,
    val offsetMinutes: Int,
    val sampleCount: Int,
    val computedAtMs: Long,
)
