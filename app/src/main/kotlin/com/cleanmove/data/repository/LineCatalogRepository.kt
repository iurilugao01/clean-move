package com.cleanmove.data.repository

import com.cleanmove.data.ceturb.CeturbSource
import com.cleanmove.data.ceturb.toDepartures
import com.cleanmove.data.ceturb.toDomain
import com.cleanmove.data.ceturb.toSteps
import com.cleanmove.data.local.CatalogDao
import com.cleanmove.data.local.ItineraryEntity
import com.cleanmove.data.local.LineEntity
import com.cleanmove.data.local.ScheduleEntity
import com.cleanmove.data.local.SyncMeta
import com.cleanmove.domain.DayType
import com.cleanmove.domain.ItineraryStep
import com.cleanmove.domain.Line
import com.cleanmove.domain.LineCatalog
import com.cleanmove.domain.LineDetail
import com.cleanmove.domain.ScheduledDeparture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

class LineCatalogRepository(
    private val source: CeturbSource,
    private val dao: CatalogDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DAY_MILLIS,
    private val cacheVersion: Int = 1,
) : LineCatalog {
    // Bump cacheVersion whenever mapping logic changes so previously cached rows are refetched.
    private val linesKey = "lines:v$cacheVersion"

    override fun observeLines(query: String): Flow<List<Line>> =
        dao.observeLines(query.trim()).map { rows -> rows.map { Line(it.code, it.name) } }

    override suspend fun refreshLinesIfStale(): Result<Unit> {
        if (isFresh(dao.updatedAt(linesKey))) return Result.success(Unit)
        return catching {
            val lines = source.lines().map { it.toDomain() }.map { LineEntity(it.code, it.name) }
            // An empty catalog is never real: keep the existing cache instead of wiping it for a day.
            check(lines.isNotEmpty()) { "Empty lines response" }
            dao.replaceLines(lines, SyncMeta(linesKey, now()))
        }
    }

    override suspend fun loadDetail(code: String): Result<LineDetail> {
        val line = dao.line(code)?.let { Line(it.code, it.name) }
            ?: return Result.failure(NoSuchElementException("Linha $code não encontrada"))
        val key = "detail:v$cacheVersion:$code"
        val cachedAt = dao.updatedAt(key)
        if (isFresh(cachedAt)) return Result.success(readDetail(line))

        val fetched = catching {
            coroutineScope {
                val schedules = async { source.schedules(code).toDepartures() }
                val itinerary = async { source.itinerary(code).toSteps() }
                val newSchedules = schedules.await()
                val newItinerary = itinerary.await()
                // A lone empty response must not erase a detail we already have (a line can be genuinely empty, but not once it had data).
                val hadData = dao.schedules(code).isNotEmpty() || dao.itinerary(code).isNotEmpty()
                check(!(newSchedules.isEmpty() && newItinerary.isEmpty() && hadData)) { "Empty detail response" }
                dao.replaceDetail(
                    code,
                    newSchedules.map { it.toEntity() },
                    newItinerary.map { ItineraryEntity(lineCode = it.lineCode.ifEmpty { code }, direction = it.direction, sequence = it.sequence, street = it.street) },
                    SyncMeta(key, now()),
                )
            }
        }
        return when {
            fetched.isSuccess -> Result.success(readDetail(line))
            cachedAt != null -> Result.success(readDetail(line))
            else -> Result.failure(fetched.exceptionOrNull()!!)
        }
    }

    override suspend fun lineByCode(code: String): Line? = dao.line(code)?.let { Line(it.code, it.name) }

    private fun isFresh(updatedAt: Long?): Boolean = updatedAt != null && now() - updatedAt < ttlMillis

    private suspend fun readDetail(line: Line): LineDetail = LineDetail(
        line = line,
        schedules = dao.schedules(line.code).map { it.toDomain() },
        itinerary = dao.itinerary(line.code).map { ItineraryStep(it.lineCode, it.direction, it.sequence, it.street) }
            .sortedWith(compareBy({ it.direction }, { it.sequence })),
    )

    private fun ScheduledDeparture.toEntity() = ScheduleEntity(
        lineCode = lineCode, dayType = dayType.name, direction = direction,
        terminalName = terminalName, minuteOfDay = minuteOfDay, notes = notes.sorted().joinToString(","),
    )

    private fun ScheduleEntity.toDomain() = ScheduledDeparture(
        lineCode = lineCode,
        dayType = runCatching { DayType.valueOf(dayType) }.getOrDefault(DayType.OTHER),
        direction = direction, terminalName = terminalName, minuteOfDay = minuteOfDay,
        notes = notes.split(",").filter { it.isNotEmpty() }.toSet(),
    )

    private suspend fun <T> catching(block: suspend () -> T): Result<T> =
        try { Result.success(block()) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
}
